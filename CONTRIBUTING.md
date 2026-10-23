# Contributing

Use trunk-based development: make one small, complete change at a time and integrate it
frequently into `trunk`. Link the issue and acceptance evidence. Keep commits atomic;
separate unrelated fixes and configuration changes. Avoid long-lived branches.

For behavior changes, write a failing automated test first, implement the smallest complete
fix, and refactor while tests stay green. Cover observable behavior and failure boundaries,
including interrupted delivery and credential routing. Use neutral fixtures and synthetic
credentials. App-specific adoption evidence belongs in the app's repository.

## Local checks

Install JDK 21 and use the checked-in Gradle wrapper:

```sh
./gradlew test --tests delivery.ToolsTest  # focused feedback while developing
./gradlew format                          # Kotlin formatting
./gradlew check                           # required before integration
```

`check` runs the automated tests, Detekt, formatting checks and workflow safety checks.
Keep `check.yml` green on each integrated change. See the README's development section
for actionlint and its narrowly scoped compatibility exceptions. Tests must not require
real deployment credentials or upload builds.

## Run the engine locally

Build the CLI and run its repository workflow checks from this checkout:

```sh
./gradlew installDist
build/install/mobile-delivery/bin/mobile-delivery workflowChecks
```

The launcher is in `build/install/mobile-delivery/bin/`. App phases run with the app
checkout as the working directory and read its `.github/delivery.json`; normally the
GitHub Actions entry points supply their operation context and credential environment.
Use the README's operation requirements when exercising a phase deliberately. `bootstrap`
changes repository secrets, and delivery/release phases can change Apple or GitHub state;
they are not substitutes for local tests.

Report security vulnerabilities privately using [SECURITY.md](SECURITY.md). Contributions
are distributed under the [MIT license](LICENSE).

## Release a version

Add a dated version section to `CHANGELOG.md`, integrate it on `trunk`, and wait for
Check and the hosted engine/composite tests to pass. A version tag must point to that
tested commit. For example, a prerelease rehearsal uses:

```sh
git tag -a v1.0.0-rc.1 -m 'Prepare v1.0.0-rc.1'
git push origin v1.0.0-rc.1
```

The Release workflow reruns checks, builds `installDist`, and attaches a reproducible
`mobile-delivery-<tag>.zip` plus its SHA-256 checksum to a GitHub Release. Notes come from
that version's changelog section. Prereleases stay marked as prereleases and never move
a major tag. A stable release advances its matching major tag (`v1` for `v1.2.3`) only
after publication succeeds, without moving backwards or across unrelated history.
Let the workflow own major tags; never replace a published version tag or release asset.

For local bootstrap, verify the downloaded zip against its `.sha256` file, unzip it,
install JDK 21, and run `mobile-delivery/bin/mobile-delivery bootstrap` with the documented
arguments from the app checkout. Gradle is not needed for the downloaded CLI.

Do not cut v1.0.0 until the publication, sample onboarding and app adoption acceptance
checks are complete. Publishing a tool release does not enable App Store automation
in apps that use it.
