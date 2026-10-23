"""Resolve the engine's JDK without exporting Java settings to the caller."""

import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request


def resolve(config, env, system, architecture):
    probe_env = {key: env[key] for key in ("PATH", "HOME", "TMPDIR", "LANG") if key in env}

    def valid(home):
        binary = home / "bin/java"
        if not binary.is_file():
            return False
        try:
            result = subprocess.run([str(binary), "-version"], env=probe_env, text=True, capture_output=True)
        except OSError:
            return False
        return result.returncode == 0 and re.search(r'version "21(?:\.|\")', result.stdout + result.stderr)

    variable = "JAVA_HOME_21_ARM64" if architecture in ("arm64", "aarch64") else "JAVA_HOME_21_X64"
    if env.get(variable):
        home = Path(env[variable])
        if valid(home):
            return home.resolve()
    jdk = config.get("jdk", {})
    asset = jdk.get("assets", {}).get(f"{system}/{architecture}")
    if not asset:
        raise RuntimeError(f"No pinned engine JDK for {system}/{architecture}; configure {variable} with JDK 21")
    sha = asset["sha256"]
    if not re.fullmatch(r"[a-f0-9]{64}", sha):
        raise RuntimeError("Pinned JDK checksum must be a full SHA-256 digest")
    if not asset["url"].startswith("https://cdn.azul.com/zulu/bin/"):
        raise RuntimeError("Pinned JDK must use the official Azul HTTPS download origin")
    if not env.get("RUNNER_TOOL_CACHE"):
        raise RuntimeError(f"Set RUNNER_TOOL_CACHE or provide {variable} for engine JDK 21")
    cache = Path(env["RUNNER_TOOL_CACHE"]) / "mobile-delivery-jdk"
    target = cache / f"{system}-{architecture}-{sha}"

    def cached():
        marker = target / ".java-home"
        if marker.is_file():
            home = (target / marker.read_text()).resolve()
            if home.is_relative_to(target.resolve()) and valid(home):
                return home
        raise RuntimeError(f"Cached engine JDK is incomplete at {target}; remove that cache entry and retry")

    if target.exists():
        return cached()
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="download-", dir=cache) as directory:
        stage = Path(directory)
        archive = stage / "jdk.tar.gz"
        with urllib.request.urlopen(asset["url"], timeout=120) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
        digest = hashlib.sha256()
        with archive.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != sha:
            raise RuntimeError("Engine JDK checksum mismatch; no downloaded code was extracted or executed")
        contents = stage / "contents"
        contents.mkdir()
        # Only the checksum-verified vendor archive reaches tar or its java executable.
        subprocess.run(["tar", "-xzf", str(archive), "-C", str(contents)], env=probe_env, check=True)
        homes = [binary.parent.parent for binary in contents.rglob("bin/java") if valid(binary.parent.parent)]
        if len(homes) != 1:
            raise RuntimeError("Pinned JDK archive must contain exactly one working JDK 21")
        (contents / ".java-home").write_text(str(homes[0].relative_to(contents)))
        try:
            contents.rename(target)
        except FileExistsError:
            # Another job sharing the tool cache may have finished the same verified JDK.
            pass
    return cached()


if __name__ == "__main__":
    try:
        config = json.loads(Path(sys.argv[1]).read_text())
        print(resolve(config, os.environ, platform.system(), platform.machine()))
    except Exception as error:
        print(f"Engine JDK setup failed: {error}", file=sys.stderr)
        sys.exit(1)
