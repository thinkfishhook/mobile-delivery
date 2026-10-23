#!/usr/bin/env python3
"""Package the checked-out release tag and publish without replacing released assets."""
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


@dataclass(frozen=True)
class Version:
    numbers: tuple
    prerelease: bool

    @property
    def major(self):
        return f"v{self.numbers[0]}"


def version(tag):
    numeric = r"(0|[1-9][0-9]*)"
    identifiers = r"[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*"
    match = re.fullmatch(rf"v{numeric}\.{numeric}\.{numeric}(?:-({identifiers}))?(?:\+{identifiers})?", tag)
    if not match:
        raise ValueError("Release tags must be semantic versions such as v1.0.0 or v0.9.0-rc.1")
    prerelease = match[4]
    if prerelease and any(part.isdigit() and len(part) > 1 and part.startswith("0") for part in prerelease.split(".")):
        raise ValueError("Numeric prerelease identifiers must not have leading zeroes")
    return Version(tuple(int(match[i]) for i in (1, 2, 3)), bool(prerelease))


def notes(changelog, tag):
    version(tag)
    match = re.search(rf"^## \[{re.escape(tag[1:])}\] - \d{{4}}-\d{{2}}-\d{{2}}\s*\n(.*?)(?=^## |\Z)", changelog, re.MULTILINE | re.DOTALL)
    if not match or not match[1].strip():
        raise ValueError(f"Add a non-empty dated CHANGELOG.md section for [{tag[1:]}] before tagging")
    return match[1].strip()


def archive(source, destination):
    if not (source / "bin/mobile-delivery").is_file():
        raise ValueError("Run ./gradlew installDist before packaging the CLI")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for path in sorted(source.rglob("*")):
            if path.is_symlink():
                raise ValueError("The CLI distribution must contain files, not symbolic links")
            if not path.is_file():
                continue
            entry = zipfile.ZipInfo((Path(source.name) / path.relative_to(source)).as_posix())
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (0o100755 if path.stat().st_mode & 0o111 else 0o100644) << 16
            output.writestr(entry, path.read_bytes())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def publish(plan, github):
    selected = version(plan["tag"])
    if not re.fullmatch(r"[a-f0-9]{40}", plan["sha"]):
        raise ValueError("Release source must be a full commit SHA")
    previous = None
    if not selected.prerelease:
        if any(version(tag).numbers > selected.numbers for tag in github.stable_tags(selected.major)):
            raise ValueError("A newer stable version already exists; do not move the major tag backwards")
        previous = github.tag_target(selected.major)
        if previous and not github.is_ancestor(previous, plan["sha"]):
            raise ValueError("The major tag is not an ancestor of this release; reconcile the history before publishing")
    github.release(plan)
    if not selected.prerelease and previous != plan["sha"]:
        github.move_tag(selected.major, plan["sha"], previous)


class GitHub:
    def __init__(self, repository):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("GITHUB_REPOSITORY must identify the release repository")
        self.repository = repository

    def api(self, path, *arguments, missing=False):
        result = subprocess.run(["gh", "api", f"repos/{self.repository}/{path}", *arguments], text=True, capture_output=True)
        if result.returncode:
            if missing and "HTTP 404" in result.stderr:
                return None
            raise RuntimeError(f"GitHub release API failed: {result.stderr.strip()}")
        return json.loads(result.stdout)

    def release(self, plan):
        tag, asset = plan["tag"], Path(plan["asset"])
        existing = self.api(f"releases/tags/{tag}", missing=True)
        if existing:
            if existing["draft"] or existing["prerelease"] != version(tag).prerelease:
                raise ValueError("Existing release state differs; inspect it before retrying")
            with tempfile.TemporaryDirectory() as directory:
                subprocess.run(["gh", "release", "download", tag, "--repo", self.repository, "--pattern", asset.name, "--dir", directory], check=True)
                if digest(Path(directory) / asset.name) != digest(asset):
                    raise ValueError("Published CLI differs from this build; create a new version instead of replacing the asset")
            return
        command = ["gh", "release", "create", tag, "--repo", self.repository, "--verify-tag", "--title", tag, "--notes-file", plan["notes"], str(asset), plan["checksum"]]
        if version(tag).prerelease:
            command += ["--prerelease", "--latest=false"]
        subprocess.run(command, check=True)

    def stable_tags(self, major):
        tags = subprocess.check_output(["git", "tag", "--list", f"{major}.*"], text=True).splitlines()
        stable = []
        for tag in tags:
            try:
                if not version(tag).prerelease:
                    stable.append(tag)
            except ValueError:
                continue
        return stable

    def tag_target(self, tag):
        ref = self.api(f"git/ref/tags/{tag}", missing=True)
        if ref is None:
            return None
        if ref["object"]["type"] != "commit":
            raise ValueError("The moving major tag must be a lightweight commit ref; inspect the existing tag before changing it")
        return ref["object"]["sha"]

    def is_ancestor(self, base, head):
        return subprocess.run(["git", "merge-base", "--is-ancestor", base, head]).returncode == 0

    def move_tag(self, tag, sha, previous):
        if previous:
            self.api(f"git/refs/tags/{tag}", "--method", "PATCH", "-f", f"sha={sha}", "-F", "force=true")
        else:
            self.api("git/refs", "--method", "POST", "-f", f"ref=refs/tags/{tag}", "-f", f"sha={sha}")


def package():
    tag = os.environ["GITHUB_REF_NAME"]
    version(tag)
    if os.environ.get("GITHUB_REF") != f"refs/tags/{tag}":
        raise ValueError("Release packaging runs only for the exact pushed version tag")
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    tagged = subprocess.check_output(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], text=True).strip()
    event = subprocess.check_output(["git", "rev-parse", "--verify", os.environ["GITHUB_SHA"] + "^{commit}"], text=True).strip()
    if sha != tagged or sha != event:
        raise ValueError("The checked-out commit must match both the pushed tag and GitHub event")
    release_notes = notes(Path("CHANGELOG.md").read_text(), tag)
    directory = Path("build/release").resolve()
    asset = directory / f"mobile-delivery-{tag}.zip"
    archive(Path("build/install/mobile-delivery"), asset)
    note_file = directory / "notes.md"
    note_file.write_text(release_notes + "\n")
    checksum = asset.with_suffix(".zip.sha256")
    checksum.write_text(f"{digest(asset)}  {asset.name}\n")
    plan = dict(tag=tag, sha=sha, asset=str(asset), notes=str(note_file), checksum=str(checksum))
    (directory / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    print(f"Packaged {asset.name} from {sha}")


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["package"]:
            package()
        elif sys.argv[1:] == ["publish"]:
            publish(json.loads(Path("build/release/plan.json").read_text()), GitHub(os.environ["GITHUB_REPOSITORY"]))
        else:
            raise ValueError("Choose package or publish")
    except Exception as error:
        sys.exit(f"Release stopped: {error}")
