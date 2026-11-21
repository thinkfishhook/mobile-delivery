# Reporting CI and deployment failures

Add a reporting job to each **Continuous Integration** and **TestFlight Deploy** workflow.
It runs after the operation, including checkout/toolchain/configuration/engine-install
failure. It needs no app checkout, Gradle or Xcode. `report-failure` uses the runner's
Node 24 action runtime. If the operation ID and displayed name are both `ci`:

```yaml
report:
  if: always()
  needs: ci
  runs-on: ubuntu-latest
  timeout-minutes: 5
  permissions: { contents: read, actions: read, checks: read, issues: write }
  concurrency: { group: mobile-delivery-reporting, queue: max, cancel-in-progress: false }
  steps:
    - uses: thinkfishhook/mobile-delivery/report-failure@FULL_RELEASE_SHA
      with:
        role: ci
        operation-job: ci
        integration-branch: trunk
```

For deployment use `role: deploy`, `needs: deploy` and `operation-job: deploy`. If the
operation has `name:`, use that exact displayed name for `operation-job`. Matrix jobs
require separate named operation/report pairs. Pin this action to the same compatible
stable full SHA as the other shared actions. The reporter job must have no environment
with signing, Apple or provider credentials. Local workflow policy rejects extra setup
steps, unsafe queues, missing operation dependencies and broader reporter permissions.

The same reporting queue is required across reporter jobs in a repository. It serializes
issue read/create/update; never use a run-specific group or cancel pending reporters.
Lost create responses are read back by owned marker before any retry. Damaged ownership
metadata or duplicate owned issues fails visibly and requires reconciliation.

Reports query the authoritative run and exact attempt's jobs/annotations. A newer rerun
invalidates an older reporter. CI, automatic deployment and manual deployment have separate
workflow/branch identities; AI findings use a different namespace. Issues include selected
SHA/ref, mode, run/attempt and upstream link, failed phases, bounded structured diagnostics
and named tests, reproduction guidance and the next safe step. The same diagnostics appear
in the reporter's rendered Actions summary. No raw logs are copied. Known credentials and
token forms are redacted; app commands must still avoid printing secrets.

Only the owned status block is replaced. Human prose/comments, assignments and triage
labels survive. The latest 20 attempts retain regression history. Rerunning an already
resolved exact attempt preserves that deliberate resolution. Owner credential/receipt
record failures carry `needs-owner`; ordinary test failures do not acquire it. Owner alerts
close only after the named credential/record was checked. Confirmed current-head CI can
resolve its CI incident. Deployment recovery additionally needs an explicit engine signal
after internal TestFlight availability; a skipped upload cannot resolve it. Manual success
can resolve its own manual incident, never an integration CI/automatic incident.

Legacy red-streak markers remain intact. Moving verification into CI does not close them.
Only receipt-confirmed CI-origin delivery at current integration head resolves the legacy
streak. Manual delivery and recovery of an older accepted build cannot certify newer trunk.
Existing owner incidents still resolve through successful use of their exact credential.

Cancelled/superseded/skipped jobs create no test incident. A completed timeout or startup
failure is an infrastructure failure. If GitHub never schedules the reporter (for example
a cancelled workflow or an operation that never reaches terminal state), it cannot observe
that loss. Inspect Actions and rerun reporting after the operation concludes. Scheduled
maintenance remains available for Apple/receipt reconciliation. Never retry an uncertain
upload blindly.

Issue/API/auth failures fail the reporting step with an explicit summary; no incident or
recovery success is claimed. Restore permission/service and rerun that job. Neutral fixtures
cover early setup, checks/preparation/deployment, cancellation, timeout, owner classification,
recovery, human content and lost responses. No broken production run is needed.

Developer checks require Node 22 or newer alongside JDK 21. `./gradlew check` runs the
dependency-free reporter fixtures, without GitHub writes, inference or Apple access.

The annotation endpoint requires `checks: read` in addition to Actions read. Without it,
GitHub returns HTTP 403 before classification/publication. Keep this read-only grant on
the independent reporter; Checks write is unnecessary. The validator rejects both the
missing grant and broader write access. See the [GitHub annotation API permissions](https://docs.github.com/en/rest/checks/runs#list-check-run-annotations).
