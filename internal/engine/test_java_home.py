import hashlib
import importlib.util
import io
import tarfile
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("engine_java", Path(__file__).with_name("java-home.py"))
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


class JavaHomeTest(unittest.TestCase):
    def test_existing_platform_jdk_is_used_without_a_download_or_environment_change(self):
        for system, arch, variable in [("Darwin", "arm64", "JAVA_HOME_21_ARM64"), ("Linux", "x86_64", "JAVA_HOME_21_X64")]:
            with tempfile.TemporaryDirectory() as directory:
                home = Path(directory) / "jdk 21"
                self.java(home)
                env = {variable: str(home), "JAVA_HOME": "/callers/jdk", "PATH": "/usr/bin:/bin"}
                original = dict(env)
                with patch.object(engine.urllib.request, "urlopen") as download:
                    self.assertEqual(home.resolve(), engine.resolve({}, env, system, arch))
                    download.assert_not_called()
                self.assertEqual(original, env)

    def test_fallback_is_checksum_verified_cached_and_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = self.archive()
            config = self.config(archive)
            env = {"RUNNER_TOOL_CACHE": directory, "PATH": "/usr/bin:/bin"}
            with patch.object(engine.urllib.request, "urlopen", return_value=io.BytesIO(archive)) as download:
                home = engine.resolve(config, env, "Linux", "x86_64")
                self.assertTrue(home.is_relative_to(Path(directory).resolve()))
                self.assertTrue((home / "bin/java").is_file())
                download.assert_called_once()
            with patch.object(engine.urllib.request, "urlopen") as download:
                self.assertEqual(home, engine.resolve(config, env, "Linux", "x86_64"))
                download.assert_not_called()

    def test_bad_checksum_is_rejected_before_extracting_or_running_downloaded_code(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.config(self.archive())
            with patch.object(engine.urllib.request, "urlopen", return_value=io.BytesIO(b"changed")), patch.object(engine.subprocess, "run") as execute:
                with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
                    engine.resolve(config, {"RUNNER_TOOL_CACHE": directory}, "Linux", "x86_64")
                execute.assert_not_called()

    def test_wrong_existing_java_version_does_not_override_the_pinned_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            wrong = Path(directory) / "wrong"
            self.java(wrong, "17")
            archive = self.archive()
            env = {"JAVA_HOME_21_X64": str(wrong), "RUNNER_TOOL_CACHE": directory, "PATH": "/usr/bin:/bin"}
            with patch.object(engine.urllib.request, "urlopen", return_value=io.BytesIO(archive)):
                self.assertNotEqual(wrong, engine.resolve(self.config(archive), env, "Linux", "x86_64"))

    @staticmethod
    def java(home, version="21"):
        binary = home / "bin/java"
        binary.parent.mkdir(parents=True)
        binary.write_text(f'#!/bin/sh\necho \'openjdk version "{version}.0.11"\' >&2\n')
        binary.chmod(0o755)

    @staticmethod
    def archive():
        content = b'#!/bin/sh\necho \'openjdk version "21.0.11"\' >&2\n'
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w:gz") as archive:
            entry = tarfile.TarInfo("zulu/bin/java")
            entry.mode = 0o755
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
        return output.getvalue()

    @staticmethod
    def config(archive):
        return {"jdk": {"version": "21.0.11", "assets": {"Linux/x86_64": {"url": "https://cdn.azul.com/zulu/bin/fixture.tar.gz", "sha256": hashlib.sha256(archive).hexdigest()}}}}


if __name__ == "__main__":
    unittest.main()
