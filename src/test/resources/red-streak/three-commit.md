Commits tested red:
- aaaaaaaaaaaa Add settings persistence — Run: https://github.com/owner/app/actions/runs/10
- bbbbbbbbbbbb Handle empty preferences — Run: https://github.com/owner/app/actions/runs/11
- cccccccccccc Refresh settings view — Run: https://github.com/owner/app/actions/runs/12

## Failing tests
- **still failing** app.SettingsTest.saves — expected saved — SettingsTest.kt:42
- **new** app.LaunchTest.opens — missing screen — LaunchTest.kt:19

## Suspect commits
Range: dddddddddddddddddddddddddddddddddddddddd..aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

[Compare](https://github.com/owner/app/compare/dddddddddddddddddddddddddddddddddddddddd...aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)

- eeeeeeeeeeee Refactor store — untested
- aaaaaaaaaaaa Add settings persistence

Commits marked untested were superseded and never verified.

## Failing command

```sh
./gradlew :app:jvmTest
```

Exit code: 1

<details>
<summary>Output tail</summary>

```text
2 tests failed
See test results
```

</details>

## Reproduce

```sh
./gradlew :app:jvmTest --tests app.SettingsTest.saves --tests app.LaunchTest.opens
```

## Evidence

[Run](https://github.com/owner/app/actions/runs/12/attempts/1)

[ios-delivery-12-1](https://github.com/owner/app/actions/runs/12/artifacts/99)

Result paths inside the artifact:
- `delivery/cccccccccccccccccccccccccccccccccccccccc/verification/app/build/test-results/jvmTest`

## Done when

a commit on `trunk` delivers to TestFlight, which closes this issue automatically.

## Data

```json
{
    "schemaVersion": 1,
    "app": "com.example.app",
    "branch": "trunk",
    "firstRed": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "tested": [
        {
            "sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "subject": "Add settings persistence",
            "runUrl": "https://github.com/owner/app/actions/runs/10",
            "runAttempt": 1
        },
        {
            "sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "subject": "Handle empty preferences",
            "runUrl": "https://github.com/owner/app/actions/runs/11",
            "runAttempt": 1
        },
        {
            "sha": "cccccccccccccccccccccccccccccccccccccccc",
            "subject": "Refresh settings view",
            "runUrl": "https://github.com/owner/app/actions/runs/12",
            "runAttempt": 1
        }
    ],
    "failures": [
        {
            "failure": {
                "test": "app.SettingsTest.saves",
                "message": "expected saved",
                "file": "SettingsTest.kt",
                "line": 42
            },
            "status": "still failing"
        },
        {
            "failure": {
                "test": "app.LaunchTest.opens",
                "message": "missing screen",
                "file": "LaunchTest.kt",
                "line": 19
            },
            "status": "new"
        }
    ],
    "suspectRange": {
        "base": "dddddddddddddddddddddddddddddddddddddddd",
        "firstRed": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "commits": [
            {
                "sha": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "subject": "Refactor store",
                "untested": true
            },
            {
                "sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "subject": "Add settings persistence",
                "untested": false
            }
        ],
        "fromBaseline": false
    },
    "reproduce": [
        "./gradlew :app:jvmTest --tests app.SettingsTest.saves --tests app.LaunchTest.opens"
    ],
    "links": {
        "run": "https://github.com/owner/app/actions/runs/12/attempts/1",
        "artifact": "https://github.com/owner/app/actions/runs/12/artifacts/99",
        "artifactName": "ios-delivery-12-1",
        "reports": [
            "delivery/cccccccccccccccccccccccccccccccccccccccc/verification/app/build/test-results/jvmTest"
        ],
        "compare": "https://github.com/owner/app/compare/dddddddddddddddddddddddddddddddddddddddd...aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "failingCommand": {
        "command": "./gradlew :app:jvmTest",
        "exit": 1,
        "tail": "2 tests failed\nSee test results"
    },
    "artifactAvailable": true,
    "testedCount": 3,
    "truncation": [],
    "updateKey": ""
}
```
