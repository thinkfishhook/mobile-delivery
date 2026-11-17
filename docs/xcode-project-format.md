# Signing overlays for Xcode project formats

The archive path accepts a project wrapper containing exactly one
`project.pbxproj` or `project.xcproj`. It does not rename or convert the project
between formats. Both files, neither file, unsupported structures and ambiguous
application targets/configurations fail before the archive callback.

The legacy plist path retains its existing `plutil` conversion and overlays the
selected application's Release configuration only. The hierarchical JSON path
accepts comments/trailing commas and selects the configured target by name and
application product type. It writes six Release settings: manual signing style,
identity, team, provisioning profile specifier, marketing version and build number.
Other targets, packages, files, phases, project settings and unrelated platform
settings remain unchanged in the parsed tree.

Xcode 27.2 readback exposed a precedence issue: a common value could shadow its
`[config=Release]` counterpart. The overlay expands common values for those six
managed keys into the project's declared configurations, then substitutes Release.
Debug/custom configurations retain their common values. Existing exact Release
values are replaced. SDK/architecture/wildcard conditions on managed keys, or a
common value combined with a non-Release override, are rejected as ambiguous.
This intentionally bounds support instead of guessing precedence. Unknown required
capabilities are rejected. Configuration objects retain their xcconfig references.

The original file bytes are restored in `finally`, including comments, line endings
and whitespace, on normal completion, archive exceptions and legacy conversion
failure. This is in-process cleanup, not a guarantee against SIGKILL or power loss;
delivery uses disposable source snapshots. No second project file is created.

## Reproduce

```sh
./gradlew check
./gradlew checkXcodeProjectFormat  # macOS, Xcode 27.2 or later
bash internal/actionlint.sh
```

The explicit native probe uses a neutral disposable project and local Swift
package. It only runs `xcodebuild -showBuildSettings`, verifying iOS Release's
six values, macOS Debug's original values/conditional bundle identifier, the
unrelated resource target, and exact-byte restoration. It performs no signing,
archive, upload, UI test or permission mutation. Regular tests cover both formats,
source preservation, success/failure cleanup and unsupported/ambiguous input.

Fixture shape is based on Apple's public hierarchical format, with invented app,
package, bundle, phase and target identifiers. It contains no downstream app source.
Sources: [Apple's format overview](https://developer.apple.com/documentation/xcode/updating-your-xcode-project-configuration-file-format),
[schema and configuration model at inspected revision](https://github.com/apple/xcode-project-format/tree/0a7242a207038472e4a36b29bffb135b94792410/Sources/Library/Schema).

Local tests and settings readback establish format/overlay support. They do not
establish a signed downstream archive, TestFlight upload or compatible public
action pin. Consumers must use the reviewed published full SHA after this change
is separately authorized for publication.

## Local verification, 2026-11-17

- Java 21.0.8, Gradle 9.3.1, Xcode 27.2 (27B52), macOS arm64.
- Legacy characterization passed before and after extracting the project seam.
- The first hierarchical fixture failed on the missing legacy filename, before
  implementation. Xcode readback then caught the common/Release precedence issue;
  expanding managed values into explicit configurations fixed it.
- `./gradlew check checkXcodeProjectFormat`: passed. 400 tests in 55 suites,
  zero failures/errors/skips; Detekt, formatting and workflow contracts passed.
- Native probe: all six iOS Release values, original macOS Debug settings,
  unrelated resource target and original project bytes verified.
- `bash internal/actionlint.sh`: passed using its checksum-verified pinned tool.
- `git diff --check`: passed. No credentials, private downstream source,
  live signing, Apple upload or remote repository mutation was involved.
