# Ticket 9: cancelled superseded pushes

`StartRun.cancelSuperseded` writes `superseded by <tip>, not tested` before a
single cancellation request with the job token. The GitHub adapter requires HTTP
202 and bounds the request to 30 seconds. A monotonic 180-second deadline includes
request time; two-second interruptible sleeps wait for runner termination. There
is no successful return. Rejection or timeout writes a failed summary with the
paste-ready `permissions: actions: write` fix and throws. Explicit older-SHA
dispatch selection is unchanged.

`operation=none` is emitted before cancellation. Existing explicit operation guards
keep verification, archive, package upload, Apple upload and sweep work out of this
path. The evidence upload and final composite summary now use `!cancelled()` and
the operation guard instead of `always()`, preserving the explanation already
written. Workflow validation enforces these conditions. No duplicate supersession
checks were added to later delivery steps.

## Test-first evidence

After adding the cancellation seam with an unimplemented body,
`./gradlew test --tests delivery.StartRunTest` ran five tests: the two selection
tests passed and all three new cancellation tests failed at runtime. Then the
implementation passed those tests and `delivery.DeliveryToolTest` before the
composite guard refactor.

Final coverage includes summary-before-request ordering, exactly one POST with
the job token (not the receipt token), a 30-second request bound, interruptible
waiting, refusal, HTTP 200 without acceptance, a transient HTTP 503 without retry,
a simulated 180-second wall-clock timeout including request duration, failed
summary permissions guidance, no delivery API work, explicit older dispatches,
and mutation tests for the composite guards.

Final validation:

- `./gradlew check`: passed, 298 tests, zero failures/errors/skips; formatting,
  Detekt and workflow safety checks passed.
- `bash internal/actionlint.sh`: passed, including pinned checksum validation.
- `git diff --check`: passed.

## Hosted acceptance deferred

This assignment expressly authorizes local commits and prohibits pushing, merging
or creating PRs. A hosted run of these new sources therefore cannot be staged in
this turn: GitHub cannot execute an unpushed local commit. No new hosted cancellation
is claimed. The prior [self-cancellation spike](decisions/cancel-superseded-runs.md)
proved the job-token mechanism on hosted Ubuntu, including
[the interrupted wait run](https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41500128420)
which ended cancelled at both run and job levels, and the rejected read-only-token
control which failed.

The portfolio-acceptance ticket must prove the integrated implementation live:
queue an older push behind its newer branch tip, verify run and executing job both
end `cancelled`, inspect the pre-cancellation summary, and confirm verification,
archive, uploads, evidence upload and sweep never ran. Do this after an authorized
integration/push of this ticket's commit. Also exercise the caller policy's
`actions: write` fix. Local fakes do not establish hosted runner behavior.
