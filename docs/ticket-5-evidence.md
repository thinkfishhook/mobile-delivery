# Ticket 5: failing-command output tail

Implemented in the isolated public-staging Mission checkout. Verification prints the
last 200 lines by default; the `Verification` constructor accepts a positive `tailLines`
limit. The saved summary adds `failingCommand` with the full command string, numeric
exit status (null when unavailable, such as a timeout), and raw tail string. The complete
verification log remains in the evidence directory. Report extraction retains this data
when it enriches the failure with test names.

Workflow-command tokens in console tails become `: :`; printed summary JSON uses
Unicode escapes for the same tokens. Saved JSON and the evidence log retain original
text. This also handles tokens after a text prefix and carriage-return line boundaries.
`CommandError` carries its log path and exit separately; presentation adds the existing
`; see <filename>` wording.

## Test-first evidence

Before implementation, `./gradlew test --tests delivery.VerificationTailTest` failed
both tail tests at the missing `::group::Output tail: sample-tests --debug` assertion.
After implementation the new tests and `XcresultFailureTest` passed; the log-path
refactor followed that green run. Coverage includes the exact 200-line boundary,
short logs, custom limits, complete log preservation, summary contents and safe console
JSON, embedded workflow commands, and unchanged displayed command errors.

Final checks:

- `./gradlew format check installDist`: passed; 276 tests, no failures.
- `bash internal/actionlint.sh`: passed with its pinned checksum verified.
- `git diff --check`: passed.

## Staging-checkout CLI rehearsal, 2026-11-02

Built the staging CLI with `installDist` and ran its `verify` task in
`build/ticket-5-rehearsal`. The fixture used `src/test/resources/delivery.json` with
commands set to `[["/bin/sh", "fail.sh"]]` and reports to `["junit"]`. Fixture executables
for `uname` and `xcodebuild` returned `arm64` and `Xcode 26.6`. `fail.sh` printed a compile
message, a Swift error to stderr, and a synthetic workflow annotation, then exited 65.
The actual process runner captured both streams. CLI exit was 1. Excerpt from
`build/ticket-5-rehearsal/run.log`:

```text
Running /bin/sh fail.sh
::group::Output tail: /bin/sh fail.sh
Running /bin/sh fail.sh
Compiling fixture
error: fixture.swift: missing symbol
: :error: :untrusted annotation
::endgroup::
```

Parsed `build/ticket-5-rehearsal/build/delivery/run-summary.json` and asserted the command
was `/bin/sh fail.sh`, exit was 65, and the raw tail retained `::error::untrusted annotation`.
Also asserted the console output contained no executable synthetic annotation token.

This is a local staging-checkout rehearsal, not a hosted Actions run. No push, workflow
dispatch, merge, or PR was performed. Hosted collapsed rendering remains to be confirmed
after authorized integration.
