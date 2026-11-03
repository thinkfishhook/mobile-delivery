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

Prepare a version section in `CHANGELOG.md`, integrate it on `trunk`, and wait for
Check and the hosted engine/composite tests to pass on the exact candidate SHA. Set the
date in the stable version section during cutover, then validate the resulting commit
before tagging it. Never replace a published version tag or release asset.

A local candidate rehearsal can read the pending stable section (for example `[1.0.0]`)
without setting its date early. Run from a clean checkout, using an unused throwaway tag;
this packages locally and does not publish:

```sh
./gradlew check installDist
bash internal/actionlint.sh
python3 -B internal/test_release.py
git tag v1.0.0-rc
GITHUB_REF_NAME=v1.0.0-rc GITHUB_REF=refs/tags/v1.0.0-rc GITHUB_SHA="$(git rev-parse HEAD)" python3 -B internal/release.py package
git tag -d v1.0.0-rc
```

Inspect `build/release/plan.json`, `notes.md`, the CLI zip and its `.zip.sha256` file.
An explicitly dated candidate section takes precedence over the pending stable section.
Stable tags always require their own dated section. Push a version tag only as part of
an approved release cutover, after the final commit's hosted checks pass.

The Release workflow reruns checks, builds `installDist`, and attaches a reproducible
`mobile-delivery-<tag>.zip` plus its SHA-256 checksum to a GitHub Release. Notes come from
the selected changelog section. Prereleases stay marked as prereleases and never move
a major tag. A stable release advances its matching major tag (`v1` for `v1.2.3`) only
after publication succeeds, without moving backwards or across unrelated history.
Let the workflow own major tags; never replace a published version tag or release asset.

For local bootstrap, verify the downloaded zip against its `.sha256` file, unzip it,
install JDK 21, and run `mobile-delivery/bin/mobile-delivery bootstrap` with the documented
arguments from the app checkout. Gradle is not needed for the downloaded CLI.

The v1.0.0 cutover follows the approved drain, repository move and app pinning plan.
Publishing a tool release does not enable App Store automation in apps that use it.

For post-release portfolio validation, follow the [acceptance evidence guide](docs/portfolio-acceptance.md).
Keep live observations separate from configuration checks and local fixture results.

## Moving a pinned repository

Before renaming a repository used by pinned workflow actions, pause the callers' delivery
triggers and stop dispatching new runs. From this tool's checkout, use Python 3 and an
authenticated `gh` CLI with Actions read access to every caller repository:

```sh
python3 internal/drain_check.py \
  --target owner/client-one testflight-delivery.yml \
  --target owner/client-one delivery-maintenance.yml \
  --target owner/client-two ship.yml \
  --wait --timeout 600 --interval 15
```

Repeat `--target owner/repo workflow.yml` for every workflow that can call the pinned
repository, including release, rollout and maintenance workflows. Use workflow file
names, not display names. The tool only reads GitHub Actions state; it never cancels runs
or changes triggers. It prints each blocker as repository, workflow, run link, status and
head SHA. Queued, in-progress, waiting and pending runs block the move.

Without `--wait`, exit 0 means every selected workflow was drained when checked; exit 1
means blockers remain. `--wait` polls until drained (exit 0) or the timeout expires.
API errors, incomplete responses and timeouts exit 2, so a failed read never proves drain.
The timeout covers API calls as well as polling, and each API call is bounded to 30 seconds.
Leave triggers paused through the move: this is a point-in-time check, not a lock against
new runs. After a successful check, move the repository, update the callers' pinned
references, validate them, then restore triggers.
