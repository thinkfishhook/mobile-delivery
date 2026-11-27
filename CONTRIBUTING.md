# Contributing

Follow the [PR workflow](docs/pr-workflow.md) and preserve existing local pre-push
hooks. Link the issue and acceptance evidence when applicable. For behavior changes,
write a failing automated test first, implement the smallest complete fix, and
refactor with tests green. Use neutral fixtures and synthetic credentials.

## Local checks

Use JDK 21, Node 22 or newer, Python 3 and the checked-in Gradle wrapper.

```sh
./gradlew check
bash internal/actionlint.sh
```

See the [Check workflow](.github/workflows/check.yml) for the required commands.
Use `./gradlew format` to apply Kotlin formatting. For a native readback of the
signing overlay, run `./gradlew checkXcodeProjectFormat` on macOS with Xcode 27.2+.

## Release a version

Integrate a dated version section in [CHANGELOG.md](CHANGELOG.md). Before pushing an
authorized release tag, verify that Check and the hosted engine and composite
self-tests passed on the exact commit. Let the [Release workflow](.github/workflows/release.yml)
publish the assets and manage the major tag. Keep published version tags and assets
immutable. Record app adoption evidence in the app's repository.

Before renaming a repository used by pinned workflows, pause the callers' triggers.
Use `python3 internal/drain_check.py --help` and check every caller workflow by filename.
Keep triggers paused through the rename and pin updates, then validate before restoring
them.

Report vulnerabilities privately using [SECURITY.md](SECURITY.md).
