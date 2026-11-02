# Self-cancellation of superseded runs

Status: **Draft — live evidence blocked by the current no-push instruction.**
Ticket: 22. Prepared 2026-11-02. No spike runs have been dispatched; this record
is not an approval to implement self-cancellation.

## Questions and evidence still required

| Question | Live evidence required | Result |
| --- | --- | --- |
| Does a composite using the job token yield `cancelled`? | Run URL, exact SHA, cancellation response, final run and job conclusions | Pending |
| When does the runner stop, and which later/post steps run? | UTC request/response/signal timestamps, ordinary and `always()` step outcomes, custom post marker and actual cache post logs | Pending |
| Return, sleep or poll; what if permission is missing? | Separate return/sleep/poll runs plus an `actions: read` negative control | Pending |
| Is a `queue: max` group released promptly? | Cancelling run plus a follower observed pending before cancellation; compare predecessor completion and follower job start | Pending |

A local shell test cannot establish any of these hosted-runner outcomes. Do not
substitute documentation, a PAT-driven cancellation, or a normal CLI cancellation
for a composite calling with its own `GITHUB_TOKEN`.

## Documented contract, not spike observations

The [cancel endpoint](https://docs.github.com/en/rest/actions/workflow-runs#cancel-a-workflow-run)
requires Actions write permission and returns HTTP 202 for an accepted request.
The caller job must grant `permissions: { actions: write }`; the checkout fixture
also needs `contents: read`. A composite cannot grant itself additional permissions.
A 202 response alone does not prove the run finished cancelled.

GitHub's [cancellation process](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-cancellation)
re-evaluates conditions. An `always()` condition can keep work running. For a
cancelled process, the runner sends SIGINT, waits 7.5 seconds, sends SIGTERM, then
waits 2.5 seconds before killing its process tree. The server has a five-minute
cancellation timeout. These are documented bounds, not measured delivery latency.

[Concurrency documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#concurrency)
allows up to 100 pending jobs/runs with `queue: max`, without
`cancel-in-progress: true`. It does not establish a release-latency guarantee.

## Prepared experiment and authorization boundary

Local disposable fixtures are prepared under ignored `build/ticket22-spike/`:

- A dispatch-only `.github/workflows/composite-self-test.yml`, hosted Ubuntu,
  four-minute job timeout, isolated `cancel-spike-<group>` job concurrency group.
- A composite supporting return immediately, sleep 120 seconds, poll every two
  seconds for 120 seconds, permission-denied and follower modes.
- UTC request/response and signal markers; normal and `always()` steps inside
  and after the composite; a custom JavaScript `post-if: always()` marker; and
  a real pinned cache action with a fresh per-run cache key and a file to save.
- Separate write/read-permission jobs. Only the selected job runs. No deployment
  credentials, app workflows, receipt tags or shared delivery concurrency groups.

To execute, approval must explicitly permit pushing temporary branch
`spike/ticket-22-self-cancel`, six workflow dispatches, and deleting that branch.
The branch snapshot should contain the prepared composite and workflow, removing
other workflow files **on that temporary branch only** so a push does not run the
existing macOS self-tests. No experiment code belongs on `trunk`.

Staging currently registers `composite-self-test.yml` on its default branch,
`trunk`. Reuse that registered workflow filename on the temporary branch and
select it with `gh workflow run composite-self-test.yml --ref spike/ticket-22-self-cancel`.
This avoids adding a new workflow to `trunk`: a wholly new dispatch workflow
requires [default-branch registration](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).
Verify dispatch accepts the branch version before continuing; do not change the
default branch or publish workflow code to `trunk` as a fallback.

Dispatch return, sleep, poll and denied separately. Then dispatch poll with
`delay=45` and a unique group, wait for its ENTER marker, and dispatch follower
with the same group. Verify the follower is pending before CANCEL_REQUEST;
otherwise repeat that pair rather than claiming queue evidence. Collect run/job
JSON and logs using `gh run view --json` / `gh run view --log` after termination.
Record each run URL and measured intervals in the table above; retain logs before
deleting the temporary branch. Do not delete workflow runs or evidence.

## Provisional implementation hypothesis

Subject to those observations: publish superseded evidence before requesting
cancellation, request cancel once with the job token, then wait interruptibly in a
bounded poll loop. A 120-second limit with a clear error is a candidate, not a
measured recommendation. Fail immediately on permission rejection and name the
required `actions: write` grant. Do not return success and allow delivery steps to
race cancellation. Check actual ordinary/always/post behavior before choosing
composite guards, evidence handling or cache-save policy. No required evidence
should rely solely on cleanup running after cancellation.

Replace this hypothesis with the measured decision before marking ticket 22 done.
