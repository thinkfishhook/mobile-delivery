"""Exercise real side-by-side Xcode selection without changing the runner default."""

import json
import os
from pathlib import Path
import subprocess


def version(environment):
    result = subprocess.run(
        ["xcodebuild", "-version"], env=environment, text=True, capture_output=True
    )
    lines = result.stdout.splitlines()
    return lines[0] if result.returncode == 0 and lines else None


default = version(os.environ)
if not default:
    raise SystemExit("Cannot read the hosted runner's default Xcode version")
for app in sorted(Path("/Applications").glob("Xcode*.app")):
    selected = version(dict(os.environ, DEVELOPER_DIR=str(app / "Contents/Developer")))
    if selected and selected.startswith("Xcode ") and selected != default:
        fixture = json.loads(Path("src/test/resources/delivery.json").read_text())
        fixture["xcode"] = selected.removeprefix("Xcode ")
        config = Path(os.environ["RUNNER_TEMP"]) / "xcode-selection-config.json"
        config.write_text(json.dumps(fixture))
        subprocess.run(
            ["mobile-delivery", "selectXcode"],
            env=dict(os.environ, DELIVERY_CONFIG=str(config)),
            check=True,
        )
        with open(os.environ["GITHUB_ENV"], "a") as output:
            output.write(f"EXPECTED_XCODE={selected}\nORIGINAL_XCODE={default}\n")
        break
else:
    raise SystemExit(f"Hosted smoke test requires a non-default Xcode besides {default}")
