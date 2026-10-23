#!/usr/bin/env python3
"""Real app-command probe for the credential-free hosted composite smoke test."""
import os
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


class BuildEnvironment(unittest.TestCase):
    def test_declared_value_reaches_the_app(self):
        self.assertEqual(os.environ.get("ANALYTICS_KEY"), "declared-build-value")

    def test_undeclared_value_does_not_reach_the_app(self):
        self.assertTrue("UNDECLARED_SENTINEL" not in os.environ)

    def test_deployment_credentials_do_not_reach_the_app(self):
        for name in (
            "ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_PRIVATE_KEY", "GH_TOKEN",
            "GITHUB_TOKEN", "IOS_RECEIPT_TOKEN", "IOS_DISTRIBUTION_P12_BASE64",
            "IOS_DISTRIBUTION_P12_PASSWORD", "IOS_PROFILE_BASE64",
        ):
            self.assertTrue(name not in os.environ, f"{name} must not reach app commands")


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(BuildEnvironment)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    report = ET.Element("testsuite", tests=str(result.testsRun), failures=str(len(result.failures)), errors=str(len(result.errors)), skipped=str(len(result.skipped)))
    for test, failure in result.failures + result.errors:
        case = ET.SubElement(report, "testcase", name=str(test))
        ET.SubElement(case, "failure").text = failure
    destination = Path("build/composite-probe/TEST-environment.xml")
    destination.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(report).write(destination)
    raise SystemExit(0 if result.wasSuccessful() else 1)
