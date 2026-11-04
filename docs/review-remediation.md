# v1 review remediation

Recorded 2026-11-04. These changes landed on `trunk` as one squashed commit and
ship in v1.0.2. No tag movement or history rewrite was performed. Immutable release
history and consumers' SHA pins remain unchanged.

## Public documentation boundary

This change removes the consumer-specific
cutover, rehearsal and observation documents from the tracked public tree, and
replaces the closeout with an app-neutral owner-decision record. The four original
files are preserved with verified SHA-256 hashes under ignored
`build/review-remediation/private-evidence/`. They have not been pushed or copied
to a remote private repository; that transfer remains an owner delivery step.
Historical Git objects and existing release tags still contain the old documents.
The cleanup does not claim erasure from published history or indicate credential
exposure. The final tracked text scan found no private consumer identity or runner
names, and remaining documentation links resolve locally.

## Credential recovery

This change closes owned credential incidents
as completed after proof of successful use: authenticated API-key use, a validated
signing identity/profile, or a successful new receipt ref write. Recovery preserves
human notes and unrelated incidents. An unrelated sweep, failed validation, or
reading an existing receipt is insufficient. Issue-update errors warn without
failing successful delivery; future successful use can retry. No live secrets were
rotated for validation.

## Interrupted delivery evidence

This change restores `always()` on TestFlight
evidence upload while excluding `operation == 'none'`. Superseded runs select
`none` before requesting cancellation. Real deliveries interrupted by cancellation
can retain existing evidence if the runner/token still permit it. Forced shutdown
or expired authorization can still prevent upload. Final sweep and delivery side
effects retain their cancellation guards. Local policy tests and actionlint verify
the condition; no new live cancellation experiment was dispatched.

## Repository settings

The selected owner-settings remediation was applied through the GitHub API, then
independently read back. [Repository security settings](https://github.com/thinkfishhook/mobile-delivery/settings/security_analysis)
now enable secret scanning, push protection, vulnerability alerts and Dependabot
security updates. [Trunk protection](https://github.com/thinkfishhook/mobile-delivery/settings/branches)
blocks force pushes and deletion, and applies to administrators. The owner removed the
PR and required-check rules on 2026-11-04 to keep trunk-based development, so changes
land directly on `trunk`. Raw settings responses are retained under ignored
`build/review-remediation/`.

## Queue delay remains unresolved

The cancellation spike measured approximately 69–84 seconds before interruption.
A one-time read-only review sample found no naturally cancelled post-cutover
delivery to measure. No safe lower-latency protocol is verified: returning early
previously allowed later work, a shorter deadline produces failure rather than
faster cancellation, and force cancellation risks cleanup. The observation plan
is recorded in the [cancellation decision](decisions/cancel-superseded-runs.md#queue-delay-review-follow-up).
The queue-delay finding stays unresolved pending natural activity or a separately
authorized experiment. Documentation is not presented as a performance fix.

## Validation

- `./gradlew --no-daemon format check`: PASS on cached Zulu JDK 21.0.11;
  395 tests, zero failures/errors/skips, Detekt, formatting and workflow checks.
- Focused recovery/signing/API-key/receipt/policy tests: PASS. Initial new recovery
  tests failed to compile before the recovery function existed. Full-suite exact
  credential-closure wording was updated for the intentional recovery message.
- `bash internal/actionlint.sh`: PASS with pinned archive checksum verification.
- `python3 -B internal/test_release.py`: 5 PASS;
  `python3 -B internal/test_drain_check.py`: 9 PASS.
- Local documentation links, preserved-evidence hashes and `git diff --check`: PASS.
