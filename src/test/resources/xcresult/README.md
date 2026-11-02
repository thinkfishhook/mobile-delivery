# Real Xcode result summaries

Captured on 2026-10-19 from a throwaway neutral Swift package, `ResultFixture`,
using XCTest on macOS (not an iOS simulator). The user approved the installed
Xcode 27.1 in place of the examples' Xcode 26.6 for this validation.

- Xcode 27.1, build 27A9275
- `xcresulttool version 25115, schema version: 0.4.0 (legacy commands format version: 3.60)`
- macOS 27.0.1, build 26A434, arm64

The package used Swift tools version 6.0, a macOS 14 minimum, one library target
and one test target. Its library defined `public func sampleValue() -> Int { 42 }`.
The test source was:

```swift
import XCTest
@testable import ResultFixture

final class ResultFixtureTests: XCTestCase {
    func testValue() { XCTAssertEqual(sampleValue(), 42) }
    func testSecondValue() { XCTAssertGreaterThan(sampleValue(), 0) }
}
```

From the package directory, the passing bundle was produced with:

```sh
xcodebuild test -scheme ResultFixture -destination 'platform=macOS' \
  -derivedDataPath DerivedData -resultBundlePath passing.xcresult \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

The other cases used that command with these changes:

| Bundle | Change | Exit | Tests | Failures |
| --- | --- | --- | --- | --- |
| `passing.xcresult` | None | 0 | 2 | 0 |
| `passing-second.xcresult` | Set result path and add `-only-testing:ResultFixtureTests/ResultFixtureTests/testValue` | 0 | 1 | 0 |
| `failing.xcresult` | Set result path and change the equality's expected value from 42 to 41 | 65 | 2 | 1 |
| `zero.xcresult` | Set result path and add `-only-testing:ResultFixtureTests/NoSuchTests`; retain the failing source | 0 | 0 | 0 |

Each committed JSON file is the corresponding real bundle's output from:

```sh
xcrun xcresulttool get test-results summary --path passing.xcresult --compact
```

The JSON was pretty-printed and each hardware `deviceId` replaced with
`fixture-device`. All other fields, including counts and failure details, are
unchanged. Only summaries are committed; the sample package, derived data, logs,
and bundles were deleted after extraction.

`XcresultTest` exercises the parser and the verification pipeline with these
captured responses. Two passing bundles retain separate totals summing to three
tests. A failed or zero-test second bundle blocks verification even when the
first passes and the test command exits successfully. The zero-test capture
demonstrates why a successful `xcodebuild` exit alone is insufficient. These
fixtures validate the summary format and gate; live caller bundles remain part
of each app's first delivery.

## Compact failing-test tree fixture

`test-failures.json` is synthetic, neutral `get test-results tests` JSON, shaped
against the installed xcresulttool JSON schema (0.4.0). It contains a unit failure
with a source location, a bare assertion without location, a passing test, and a
UI failure nested inside a device and test-case run, as in parallel execution.
No result bundles are committed.

Run the fixture verification path with:

```sh
./gradlew test --tests delivery.XcresultFailureTest --rerun-tasks
```

The test captures and asserts workflow commands, then prints the fixture-run
output into `build/test-results/test/TEST-delivery.XcresultFailureTest.xml`.
For example, even though the fake build exits 65 and writes no assertion text:

```text
::error title=SampleTests/ValueTests/testValue(),file=Tests/ValueTests.swift,line=12::XCTAssertEqual failed: (1) is not equal to (2)
::error title=SampleUITests/ScreenTests/testOpen()::Failed to find button
```

The parser emits one annotation per failed test case, combining repeated
assertion messages for that case. At most nine test annotations are emitted:
eight named tests plus an overflow count when necessary, leaving one of GitHub's
ten error annotations per step for the existing command error. Unreadable bundles
are logged without replacing the original error or hiding later bundles.
