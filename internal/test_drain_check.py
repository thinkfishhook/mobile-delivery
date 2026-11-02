import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("drain_check", Path(__file__).with_name("drain_check.py"))
drain = importlib.util.module_from_spec(spec)
spec.loader.exec_module(drain)


def run(status="queued", number=1):
    return dict(id=number, html_url=f"https://github.com/owner/repo/actions/runs/{number}",
                status=status, head_sha="a" * 40)


class DrainTests(unittest.TestCase):
    def invoke(self, response, *options):
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(drain.subprocess, "run", side_effect=response) as gh, \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = drain.main(["--target", "owner/repo", "delivery.yml", *options])
        return result, output.getvalue(), errors.getvalue(), gh

    @staticmethod
    def empty(command, **kwargs):
        return subprocess.CompletedProcess(command, 0, json.dumps({"workflow_runs": [], "total_count": 0}), "")

    def test_drained_checks_all_four_statuses_and_is_read_only(self):
        result, output, errors, gh = self.invoke(self.empty)
        self.assertEqual(0, result)
        self.assertIn("Drained", output)
        self.assertEqual("", errors)
        self.assertEqual({"queued", "in_progress", "waiting", "pending"},
                         {parse_qs(urlsplit(call.args[0][2]).query)["status"][0] for call in gh.call_args_list})
        self.assertTrue(all(call.args[0][:2] == ["gh", "api"] for call in gh.call_args_list))

    def test_each_blocking_status_prints_repo_workflow_link_status_and_sha(self):
        for status in ("queued", "in_progress", "waiting", "pending"):
            with self.subTest(status=status):
                def response(command, **kwargs):
                    rows = [run(status)] if f"status={status}&" in command[2] else []
                    return subprocess.CompletedProcess(command, 0, json.dumps({"workflow_runs": rows}), "")
                result, output, _, _ = self.invoke(response)
                self.assertEqual(1, result)
                self.assertEqual(f"owner/repo delivery.yml https://github.com/owner/repo/actions/runs/1 {status} {'a' * 40}\n", output)

    def test_pagination_finds_blocker_on_second_page(self):
        def response(command, **kwargs):
            query = parse_qs(urlsplit(command[2]).query)
            rows = []
            if query["status"] == ["queued"]:
                rows = [run(number=i) for i in range(100)] if query["page"] == ["1"] else [run(number=100)]
            return subprocess.CompletedProcess(command, 0, json.dumps({"workflow_runs": rows}), "")
        result, output, _, gh = self.invoke(response)
        self.assertEqual(1, result)
        self.assertEqual(101, len(output.splitlines()))
        self.assertTrue(any("page=2" in call.args[0][2] for call in gh.call_args_list))

    def test_gh_error_fails_closed_even_after_empty_responses(self):
        count = 0
        def response(command, **kwargs):
            nonlocal count
            count += 1
            return self.empty(command) if count < 4 else subprocess.CompletedProcess(command, 1, "", "HTTP 403")
        result, output, errors, _ = self.invoke(response)
        self.assertNotEqual(0, result)
        self.assertNotIn("Drained", output)
        self.assertIn("403", errors)

    def test_invalid_json_missing_runs_and_api_result_cap_fail_closed(self):
        for body in ("not json", "{}", '{"workflow_runs": [], "total_count": 1001}',
                     '{"workflow_runs": [{"status": null}]}'):
            result, output, errors, _ = self.invoke(lambda command, **kwargs: subprocess.CompletedProcess(command, 0, body, ""))
            self.assertNotEqual(0, result)
            self.assertNotIn("Drained", output)
            self.assertTrue(errors)

    def test_multiple_targets_are_all_checked(self):
        result, _, _, gh = self.invoke(self.empty, "--target", "owner/another", "release.yaml")
        self.assertEqual(0, result)
        self.assertEqual(8, gh.call_count)
        self.assertTrue(any("repos/owner/another/actions/workflows/release.yaml/runs?" in call.args[0][2]
                            for call in gh.call_args_list))

    def test_process_timeout_and_missing_gh_fail_closed(self):
        for error in (FileNotFoundError("gh is missing"), subprocess.TimeoutExpired("gh api", 30)):
            def response(command, **kwargs):
                raise error
            result, output, errors, _ = self.invoke(response)
            self.assertNotEqual(0, result)
            self.assertNotIn("Drained", output)
            self.assertTrue(errors)

    def test_wait_times_out_without_real_sleep(self):
        elapsed = [0.0]
        def sleep(seconds):
            elapsed[0] += seconds
        def response(command, **kwargs):
            return subprocess.CompletedProcess(command, 0, json.dumps({"workflow_runs": [run()]}), "")
        with patch.object(drain.time, "monotonic", side_effect=lambda: elapsed[0]), \
                patch.object(drain.time, "sleep", side_effect=sleep):
            result, output, errors, _ = self.invoke(response, "--wait", "--timeout", "3", "--interval", "2")
        self.assertNotEqual(0, result)
        self.assertEqual(3, elapsed[0])
        self.assertIn("timed out", errors)
        self.assertNotIn("Drained", output)

    def test_wait_succeeds_when_next_poll_is_empty(self):
        elapsed = [0.0]
        def response(command, **kwargs):
            rows = [run()] if elapsed[0] == 0 else []
            return subprocess.CompletedProcess(command, 0, json.dumps({"workflow_runs": rows}), "")
        with patch.object(drain.time, "monotonic", side_effect=lambda: elapsed[0]), \
                patch.object(drain.time, "sleep", side_effect=lambda seconds: elapsed.__setitem__(0, elapsed[0] + seconds)):
            result, output, _, _ = self.invoke(response, "--wait", "--timeout", "3", "--interval", "1")
        self.assertEqual(0, result)
        self.assertIn("Drained", output)


if __name__ == "__main__":
    unittest.main()
