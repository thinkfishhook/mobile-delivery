# Self-cancellation of superseded runs

Decision: **use the job token to request cancellation, then wait interruptibly.**
Tested 2026-11-02 in `thinkfishhook/mobile-delivery-public-staging` on hosted
`ubuntu-latest` (Ubuntu 26.04.1, runner 2.338.2). Self-cancellation does produce
`cancelled` for both the run and its executing job. Cancellation is asynchronous;
returning immediately allows later delivery work to race it.

## Live evidence

All six dispatches tested temporary commit
`e46b0830ea8985286acb0c4a5575a8815efa7203`. The composite used
`github.token` as `GH_TOKEN` for
`POST /repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/cancel`.
No PAT or outside cancellation was used. The workflow had job-level
`concurrency: { group: cancel-spike-<group>, queue: max, cancel-in-progress: false }`.

| Mode / run | API response | Final run / executing job | What happened |
| --- | --- | --- | --- |
| [Poll](https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41500123892) | 202 | cancelled / cancelled | Runner reported cancellation 84.2 s after response; ordinary later steps skipped |
| [Sleep](https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41500128420) | 202 | cancelled / cancelled | Runner reported cancellation after 83.8 s; ordinary later steps skipped |
| [Return immediately](https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41500133086) | 202 | cancelled / cancelled | Ordinary later steps inside and after composite ran; cache saved before final cancellation |
| [Read-only token](https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41500137085) | 403 | failure / failure | `Resource not accessible by integration`; explicit permission error; ordinary later steps skipped |
| [Queued canceller](https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41500160433) | 202 | cancelled / cancelled | Interrupted after 68.7 s; follower started two seconds after job completion |
| [Follower](https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41500210670) | No cancellation request | success / success | Same group, observed pending before predecessor cancellation |

The two waiting runs logged HTTP 202 at 18:23:43.704Z / 18:23:43.767Z and
`The operation was canceled` at 18:25:07.919Z / 18:25:07.583Z. Their executing
jobs finished at 18:25:16Z / 18:25:15Z, approximately 92 / 91 seconds after the
response. Polls continued to report `in_progress` until interruption. No signal
trap marker was emitted, so the logs do not measure signal delivery separately.
The returning run finished its job about four seconds after HTTP 202, but both
ordinary sentinel steps and a real cache save had already executed.

For the queue test, a saved API snapshot at 18:24:20.284Z showed the follower
run and its write job `pending`, while the predecessor held the same job-level
`queue: max` group. The predecessor requested cancellation at 18:25:24.360Z,
received HTTP 202 at 18:25:25.091Z, reported interruption at 18:26:33.790Z
(68.7 seconds later), and finished its job at 18:26:37Z. The follower job started
at 18:26:39Z, its first runner step at 18:26:40Z. **The group released promptly
once the job finished (about two seconds), but not immediately on HTTP 202.**
Total accepted-request-to-follower-job-start latency was about 74 seconds.

These are observed timings from a small spike, not a service-level guarantee.

## Implementation recommendation

1. Mark the operation superseded and write its explanation/evidence **before**
   cancellation. Leave explicit operation guards on every delivery step; the API
   response alone does not prevent subsequent work.
2. Grant the caller job `permissions: { actions: write, contents: read }` for this
   experiment's checkout and cancellation. **`actions: write` is the cancellation
   grant**; retain any additional permissions the real caller needs. Pass the job
   token into the cancellation command. A composite cannot elevate permissions.
3. Request cancellation once. Check HTTP 202; on 403, fail immediately with
   `Grant the caller job permissions: actions: write`. Report other rejected or
   failed requests explicitly rather than silently treating them as superseded.
4. After acceptance, stay in that step with an interruptible two-second sleep
   loop, bounded by a **180-second wall-clock deadline**, then fail loudly if the
   runner has not stopped it. Sleeping and polling both worked; prefer sleeping
   to repeated API reads, since polling offered no earlier cancellation signal.
   Bound request duration too. Do not use a fixed iteration count as a wall-clock
   limit: HTTP calls add time. Do not exit success just because cancellation was
   accepted, or wrap the wait in `always()` / ignore cancellation signals.

180 seconds gives margin over the measured 69–84-second interruption delays.
It is a proposed implementation limit, not a separately tested timeout path or a
promise that cancellation always arrives within it.

## Evidence and cleanup caveats

In both waiting runs, `if: always()` steps inside **and** after the composite ran.
A custom JavaScript `post-if: always()` probe and checkout cleanup also ran. The
pinned `actions/cache` v6.1.0 post-save was **skipped** after cancellation; the
return-immediately control **saved** its fresh per-run cache. Do not rely on cache
saving, evidence upload, or all cleanup being suppressed just because a run is
grey. Keep essential evidence before the request; bound any necessary always/post
cleanup and explicitly guard delivery side effects.

GitHub's [cancellation process](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-cancellation)
re-evaluates conditions and may keep `always()` work running. Its signal and
five-minute server timeout rules are not a substitute for the measured end-to-end
delay. The [cancel API](https://docs.github.com/en/rest/actions/workflow-runs#cancel-a-workflow-run)
documents Actions write and HTTP 202; the negative control above verifies the
missing-permission behavior with the actual job token.

The temporary branch reused the already registered dispatch workflow filename
`composite-self-test.yml`; all its other workflow files were removed **only in
that branch snapshot**, preventing push-triggered macOS runs. The experiment used
no app secrets, deployments or production concurrency groups. Logs and run/job
JSON were collected locally under ignored `build/ticket22-evidence/`; links above
remain the live evidence. No throwaway workflow or composite is included in the
Mission implementation branch.

The remote and local `spike/ticket-22-self-cancel` branches were deleted after
all six run/job records and logs were collected. No workflow changes were made
to `trunk`. Only this final decision record is intended for integration.

## Queue-delay review follow-up

The measured 69–84-second cancellation delay remains a known queue cost, not a
verified performance fix. A read-only review-time sample found no natural
post-cutover cancelled delivery from which to measure a production improvement.
Returning immediately is unsafe: the experiment above executed later steps and
saved cache before cancellation. A shorter deadline would fail runs rather than
make GitHub cancel them faster. Force cancellation could also suppress needed
cleanup. None of these alternatives is enabled without a new approved experiment.

For the first naturally superseded run, collect the cancellation request time,
runner interruption, job completion, next same-group job start and conclusion,
and confirm no verification/archive/upload work ran. Compare queued depth and
waits with normal tip deliveries on the shared runner pool. Keep the raw consumer
run links and identities in private operational records. The queue comparison and
any change to the cancellation protocol remain unresolved live follow-up.
