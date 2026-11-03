# Portfolio acceptance evidence

Collect normal app activity after a release rather than manufacturing failures,
extra builds or maintenance dispatches. Keep the public tracking issue open until
every live criterion is linked. Record UTC observation times, exact caller and tool
SHAs, run attempt, event type, job runner and any limits of the observation.
Configuration and fixture tests support live evidence; they do not replace it.

## Delivery, maintenance and triggers

For each app, link the successful delivery-branch **push** run and receipt ref.
Peel annotated receipt tags to their commit and compare it with the delivered SHA.
Read the caller workflow at that SHA to verify all four composite pins. Verify the
tool SHA in the delivery log as well. For maintenance, require `event=schedule`, a
green sweep and the released pin; a hand-dispatched sweep does not qualify.

Use the Actions API to inspect the receipt tag as a branch filter, URL-encoding the
entire tag name. Record the query time and zero matching runs, and inspect every
workflow's push filters at the receipt commit. Also review the complete runs in
the receipt's time window for unexpected tag events. A zero result is a
point-in-time observation, not proof that future tags cannot trigger workflows.

For Dependabot, link each app's successful update job and inspect its log for
public-source fetches and repository-access errors. For an upgrade, verify that
one grouped PR changes all four composite pins to the same new full SHA. Count
workflow runs per PR head commit and event, accounting for run attempts separately;
multiple jobs in one CI run are not duplicate CI runs. Check branch pushes as well
as `pull_request` events before declaring the duplicate-trigger criterion met.

## Failure and recovery

Link a real failed verification run's annotations and step summary, both naming
the failing tests without an artifact download. Link the single red-streak issue,
its opening, subsequent update and completed closure, including the fixing commit
and receipt-confirmed build. Read it as an agent: can you identify the failures,
suspect commits, reproduction commands and evidence from that issue alone? Record
any missing information. Owner-only incidents need `needs-owner`; ordinary test
failures do not. Historical per-commit issues are not red-streak lifecycle proof.

A superseded run must conclude `cancelled`, with logs showing supersession and no
verification or delivery. A manually cancelled run or unrelated CI concurrency
cancellation does not prove the delivery supersession behavior.

## Self-hosted operation and billing

Inventory every workflow at the observed app SHA. For each workflow expected to
run self-hosted, link actual job records and runner labels/names. A self-hosted
`runs-on` setting alone does not prove it ran. Keep workflows with no execution in
the period pending. Record selected Xcode output alongside `delivery.json`.

The organization billing usage endpoint can supply daily usage, for example:

```sh
gh api --paginate 'organizations/OWNER/settings/billing/usage?year=2026&month=11&day=4'
```

Repeat for seven consecutive **complete UTC days after migration**, replacing the
owner and dates. Preserve query timestamps, pagination and the report's repository,
SKU, unit and quantity fields. Filter to the app and hosted macOS minute SKUs; sum
`quantity`, not `netAmount`. Free or discounted hosted minutes still count as
hosted usage. A partial migration day with earlier hosted work cannot count as a
zero-minute day. Billing may arrive late: recheck completed days and distinguish
missing/inaccessible data from confirmed zero usage. Do not publish the whole
organization report when only one app's rows are needed.

## Queue impact and handoff

Use comparable before/after UTC windows and report sample counts per app, workflow
and runner. Subtract run creation from the first relevant job's start for an
end-to-end wait, consistently in both windows. For dependent jobs that includes
earlier work; do not describe it as pure runner queue time. Compare equivalent
entry jobs and record concurrent load, cancelled runs and outstanding runs. Do not
infer a capacity improvement from one batch or mix hosted and self-hosted samples.

Prepare a follow-up changelog locally. Publishing still requires the owner's
explicit go-ahead, required local checks, and hosted checks on the exact candidate
as described in [Contributing](../CONTRIBUTING.md). After publication, confirm
assets, the stable major tag and a real grouped upgrade PR before closing tracking.
Keep the rollback archive available until all acceptance evidence is complete.
The owner archives it; if rollback is still wanted, first confirm that an archived
private repository can continue serving actions.
