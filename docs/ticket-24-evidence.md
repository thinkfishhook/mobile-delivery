# Ticket 24: TestFlight caller permission contract

Test-first run: `JobPolicyTest` and `WorkflowChecksTest` ran 18 tests with seven
expected failures before production changes. The new cases exposed acceptance of
TestFlight `actions: read`, warnings for its new write grant, acceptance of broader
maintenance permissions, and missing caller-policy enforcement in workflow checks.

Behavior milestone: `3229a0a` requires TestFlight's exact minimum:
`permissions: { contents: read, actions: write, issues: write }`.
Maintenance remains contents read/actions read/issues write; release remains
contents read/actions read/issues read; rollout remains contents read only.

The prior implementation warned about broader grants. To meet the ticket's explicit
rejection requirement, broader grants now fail with the caller location and exact
correction, including maintenance with actions write. Tests were adjusted for this
intentional enforcement change; the other modes' minimum grants did not change.

`CompositeChecks` now checks local composite callers through `JobPolicy`, invoked
by `WorkflowChecks`, avoiding a duplicate permission contract. The quickstart
TestFlight caller, composite self-test caller and README permission rows agree.
The subsequent refactor replaces nested minimum-permission conditionals with one
ordered table row per caller mode, preserving paste-ready permission ordering.

Both the behavior milestone and table refactor passed `./gradlew check` and
`bash internal/actionlint.sh`. Final verification: 293 tests, zero failures/errors/
skips; JobPolicyTest 8, WorkflowChecksTest 10, QuickstartTest 2. Detekt, formatting,
workflow safety and Git whitespace checks passed. No app workflows, remote state,
actual token grants or cancellation behavior were changed by this ticket.
