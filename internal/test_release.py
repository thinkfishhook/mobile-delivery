import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release.py"))
release = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = release
spec.loader.exec_module(release)


class FakeGitHub:
    def __init__(self):
        self.calls = []
        self.tags = []
        self.previous = None
        self.ancestor = True
        self.fail_release = False

    def stable_tags(self, major):
        self.calls.append("tags")
        return self.tags

    def tag_target(self, tag):
        self.calls.append("read-major")
        return self.previous

    def is_ancestor(self, base, head):
        return self.ancestor

    def release(self, plan):
        self.calls.append("release")
        if self.fail_release:
            raise RuntimeError("asset upload failed")

    def move_tag(self, tag, sha, previous):
        self.calls.append((tag, sha, previous))


class ReleaseTests(unittest.TestCase):
    def test_versions_and_changelog_require_an_explicit_release_section(self):
        self.assertTrue(release.version("v0.9.0-rc.1").prerelease)
        self.assertFalse(release.version("v1.2.3").prerelease)
        for invalid in ("v1", "1.2.3", "v01.2.3", "v1.2.3-rc.01", "v1.2.3\n", "v1.2.3/other"):
            with self.assertRaises(ValueError):
                release.version(invalid)
        text = "# Changelog\n\n## [Unreleased]\n\nFuture work.\n\n## [0.9.0-rc.1] - 2026-10-23\n\n### Added\n- CLI.\n\n## [0.8.0] - 2026-10-20\n\nOlder work.\n"
        self.assertEqual("### Added\n- CLI.", release.notes(text, "v0.9.0-rc.1"))
        with self.assertRaises(ValueError):
            release.notes(text, "v1.0.0")

    def test_prereleases_never_read_or_move_a_major_tag(self):
        github = FakeGitHub()
        release.publish({"tag": "v0.9.0-rc.1", "sha": "a" * 40}, github)
        self.assertEqual(["release"], github.calls)

    def test_stable_major_moves_only_after_successful_release_and_never_backwards(self):
        plan = {"tag": "v1.2.3", "sha": "a" * 40}
        github = FakeGitHub()
        github.previous = "b" * 40
        release.publish(plan, github)
        self.assertEqual(["tags", "read-major", "release", ("v1", "a" * 40, "b" * 40)], github.calls)
        for case in ("newer", "unrelated", "failed"):
            github = FakeGitHub()
            github.tags = ["v1.3.0"] if case == "newer" else []
            github.previous = "b" * 40
            github.ancestor = case != "unrelated"
            github.fail_release = case == "failed"
            with self.assertRaises((ValueError, RuntimeError)):
                release.publish(plan, github)
            self.assertFalse(any(isinstance(call, tuple) for call in github.calls))

    def test_cli_zip_is_reproducible_preserves_launchers_and_contains_only_distribution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "mobile-delivery"
            (source / "bin").mkdir(parents=True)
            (source / "lib").mkdir()
            launcher = source / "bin/mobile-delivery"
            launcher.write_text("#!/bin/sh\nexec java Example\n")
            launcher.chmod(0o755)
            (source / "lib/engine.jar").write_bytes(b"synthetic jar")
            (root / "unrelated-secret").write_text("must stay outside the archive")
            first, second = root / "one.zip", root / "two.zip"
            release.archive(source, first)
            os.utime(launcher, (1900000000, 1900000000))
            release.archive(source, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as zipped:
                self.assertEqual(["mobile-delivery/bin/mobile-delivery", "mobile-delivery/lib/engine.jar"], zipped.namelist())
                self.assertTrue((zipped.getinfo("mobile-delivery/bin/mobile-delivery").external_attr >> 16) & 0o111)


if __name__ == "__main__":
    unittest.main()
