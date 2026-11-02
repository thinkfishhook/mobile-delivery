# Ticket 23: isolate startRun selection

The characterization milestone (`20bdede`) was committed before changing production
code. `DeliveryToolHarness` now simulates rejected ancestry and mismatched commit
resolution. Tests pin a push at the tip, the exact superseded summary and no-op
output, explicit selection of an older SHA, rejection outside the baseline or
branch, and full-SHA/exact-resolution requirements. `./gradlew check` and
`bash internal/actionlint.sh` passed for that milestone.

`StartRun.select` now owns startRun's commit selection, exact-commit and ancestry
validation, health-tip lookup, and typed `Superseded(sha, tip)` / `Proceed(sha)`
result. Direct selection tests exercise these values independently of workflow
execution. `DeliveryTool.execute` delegates the enabled startRun path before the
other mode handlers. Disabled-delivery gates stay in their original position.

Operation/retry interpretation remains after successful selection, inside the
existing delivery error boundary. This preserves the distinct summaries for
selection failures and delivery/retry failures. A shared reporting method retains
the same failure annotations and summary contents. Superseded pushes still finish
completed and output `operation=none`; this ticket does not add cancellation.
Other modes retain their existing selection, tip refresh and execution behavior.

Validation after extraction: `./gradlew check` (289 tests; zero failures/errors/skips,
plus Detekt, formatting and workflow safety checks), `bash internal/actionlint.sh`,
and Git diff whitespace checks. No live delivery, push, merge or PR is needed.

Next opportunities, deliberately deferred:

- Replace mode strings with a `Mode` enum at the input boundary.
- Give each mode its own handler, continuing the startRun separation.
