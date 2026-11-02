# Ticket 21: typed incident subjects

Characterization commit: `2d1d863` (`test: characterize incident keys and sweep closure rules`).
The characterization tests were added and run against the existing string-based
implementation before refactoring. Both required checks passed at that commit.

`IncidentSubject` now models beta commits, App Store versions, review submissions,
holds, receipts, audit, App Store read failures, and incident-sync failures. Unknown
historical keys retain their existing fallback behavior. `SweepFinding.operationKey`
and the `Incidents.sync` closure predicate use the sealed type. Titles, superseded
checks, and sweep closure checks are exhaustive `when` expressions.

The custom serializer writes the original string key, without a discriminator or
object wrapper. The same boundary decoder reads persisted JSON and existing issue
markers. Finding producers construct subjects directly; domain decisions no longer
parse prefixes. Summary text explicitly uses the subject's key.

Validation:

- Characterization: `./gradlew format test --tests delivery.IncidentsTest --tests delivery.SweepTest` passed before the refactor.
- Characterization milestone: `./gradlew check` and `bash internal/actionlint.sh` passed.
- Final refactor: `./gradlew format check` passed: 279 tests, zero failures, errors or skips; includes Detekt, formatting and workflow checks.
- Final `bash internal/actionlint.sh` passed, including pinned checksum verification.
- `git diff --check` passed.
- Source scan found no `incidentTitle`, incident-key `startsWith` branches, or prefix parsing on `operationKey`.

Tests pin exact incident titles, complete opening/closing status blocks and markers,
close reasons and superseded wording for every subject kind. Finding JSON and summary
text retain the old strings byte-for-byte, and deserialization returns the expected
typed subject. The closure matrix covers audit on/off, App Store readable/unreadable,
and receipts missing, present, expired, processing, or absent from Apple.

No GitHub or Apple state was changed; no push, merge, or PR was performed.
