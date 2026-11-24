# Archive locally with an existing signing identity

`archive-local` builds and inspects a signed iOS archive/IPA from an exact local commit.
It uses the same project signing overlay, Release archive, App Store export, IPA identity,
binary policy, signature and entitlement checks as the delivery pipeline. It packages
available dSYMs and records the source and artifact hashes in `local-archive.json`.

This command is for local signing validation before delivery is configured. It does not
run `verification.commands`, launch UI tests, reserve a production version, write delivery
receipts, upload a build, or activate a release. No GitHub Actions event or workflow URL is
fabricated. The engine makes no GitHub or Apple API calls; Xcode may fetch build dependencies.
Use trusted source: an Xcode archive executes the project's build phases.

## Prerequisites

- Build the engine with JDK 21 using `./gradlew installDist`.
- Use macOS with the app's configured Xcode selected through `DEVELOPER_DIR`, and its exact
  configured `asc` version on `PATH`. The command checks both versions; it does not install
  tools or change the machine's Xcode selection.
- Check out the intended app commit and leave the source checkout clean, including untracked
  files. Run the command from its repository root. Config comes from that commit's
  `.github/delivery.json`, not `DELIVERY_CONFIG` or an uncommitted file.
- Supply a manually managed App Store provisioning profile matching the configured bundle
  and team, plus the full SHA-1 fingerprint of its existing valid Apple Distribution or
  iPhone Distribution identity. The corresponding private key must already be available
  in the user's keychain search list. `security find-identity -v -p codesigning` lists the
  available identity fingerprints. Keychain access may require the owner's authentication.
- Choose a new output directory outside the source checkout, with an existing parent.
  Existing outputs are refused so an attempt cannot overwrite retained artifacts.

`app_id` and `internal_group` may be empty strings for this command because archiving does
not use App Store Connect. Other configuration requirements still apply. Standard
`validate` and every delivery operation continue to reject blank Apple targets.
Any declared `build_environment` values must be available, just as for the shared archive
step; deployment credentials are excluded from the build environment.

## Invocation

From the app checkout, substitute your local paths, certificate fingerprint and explicit
diagnostic version/build values:

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  /path/to/mobile-delivery/build/install/mobile-delivery/bin/mobile-delivery archive-local \
  --sha "$(git rev-parse HEAD)" \
  --profile /path/to/App-Store.mobileprovision \
  --identity 0123456789ABCDEF0123456789ABCDEF01234567 \
  --version 26.11.01 \
  --build 1.1 \
  --output /path/to/artifacts/new-local-archive
```

All six options are required. `--sha` must be the full current commit SHA. Version and
build values use the shared engine's formats (`YY.MM.RR` and `count.attempt`); supplying
them here does **not** reserve them or make them suitable for a later production upload.
Do not set fake Actions variables. This mode refuses `GITHUB_ACTIONS=true`.

The engine creates a detached temporary source worktree, applies and restores the signing
overlay there, and removes it afterward. It takes the existing per-user signing lock,
validates a snapshot of the supplied profile, and temporarily installs that profile.
Previous profile bytes are restored, or a newly installed profile is removed, on normal
completion or a handled error. It never exports/imports keys, unlocks keychains, changes
their search list, or changes key access permissions. Forced termination can still leave
temporary profile/worktree state requiring operator cleanup; unrelated signing tools
do not participate in the engine's lock.

## Evidence and failure handling

The private output directory contains the archive, exported IPA, available symbols ZIP,
build/export logs and export options. Treat these as local artifacts, not files to commit.
Only after IPA validation and profile/worktree cleanup succeed does the engine atomically
write `local-archive.json` with:

- `kind: "local-archive"`, `verification: "not-run"`, `deliveryEligible: false`;
- the exact source SHA and SHA-256 of the committed configuration bytes;
- the explicitly requested version/build, selected certificate fingerprint and profile UUID;
- checked Xcode/asc versions, completion time, and IPA/symbol filenames, sizes and SHA-256s;
- an explicit reason when the archive contained no dSYMs.

This is a separate schema from the Actions artifact manifest. There is no `manifest.json`,
`artifact.json`, workflow URL, verification receipt, or claim of a passing test suite.
Automated delivery cannot consume this local record as its retained package manifest.
Keep the engine revision used for a local run alongside this evidence when reporting it.

Any failure exits nonzero and leaves no success record. Partial files and available logs
remain for diagnosis; use a different output directory for an explicitly chosen retry.
There are no automatic retries. Successful fixture tests of this command prove its control
flow and failure boundaries; they do not substitute for an actual signed app archive.
