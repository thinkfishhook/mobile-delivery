# Ticket 25: validated signing inputs before keychain sessions

`SigningInputs.read` checks secret presence, decodes the P12/profile bytes, parses
the profile using `security cms -D`, and applies the existing `validateProfile`
checks. Its private constructor admits only validated inputs, carrying the raw
bytes, parsed plist and validated provisioning metadata. It has no generated
value logging. No expiry-policy or error-message changes were added.

`Signing.withIdentity` loads these inputs under the existing signing lock before
passing them to `signingSession`. Both `archive` and `verifySigning` already enter
through `withIdentity` (the latter with `diagnostic = true`), so they share this
single input path. Decoding and profile validation no longer live in the keychain
session. The private temporary directory is still removed last, under the lock.
The cleanup sequence remains installed-profile restoration/removal, search-list
restoration, keychain deletion, then temporary-directory deletion. Password-bearing
commands continue to use `security -i` standard input and the same filtered
environment. The signing lock implementation is unchanged.

## Characterization and red/green evidence

The starting implementation already parsed and validated the profile before any
keychain command. The requested no-keychain-on-invalid-input assertion therefore
passed before extraction; this was verified rather than introducing an artificial
failure or claiming a new behavior fix.

1. Added `SigningTest` characterization of exact successful command order; profile
   restoration with and without a previous profile; cleanup after archive action
   failure and P12 import failure; the existing undecodable-base64 error; expired
   and wrong-app profile errors; no keychain commands for invalid profiles in both
   archive and diagnostic modes. Focused SigningTest/SigningDiagnosticsTest passed.
2. Before the characterization commit, `./gradlew check` passed (302 tests) and
   `bash internal/actionlint.sh` passed.
3. Added tests directly against the proposed `SigningInputs.read` boundary. The
   focused test command failed at `compileTestKotlin` with three unresolved
   `SigningInputs` references, before the type existed. This was a compile-time
   red, not a runtime behavior failure.
4. Extracted the value/loader and passed it into `signingSession`; focused signing,
   diagnostics and lock tests passed. Added CMS decoding failure coverage for both
   modes, checking its original error and temporary-input cleanup.

## Final validation

- `./gradlew check`: passed; **305 tests**, zero failures/errors/skips, including
  SigningTest (16), SigningDiagnosticsTest (2), SigningLockTest (3). Formatting,
  Detekt and workflow safety checks passed.
- `bash internal/actionlint.sh`: passed with pinned checksum verification.
- `git diff --check`: passed.

All credential fixtures are synthetic; `RecordingCommands` fakes the native
security tools. No real keychain, signing credential, archive, Apple upload or
hosted workflow was exercised by this refactor. No push/merge/PR is authorized.
Commit SHAs are included in the Mission implementation submission.
