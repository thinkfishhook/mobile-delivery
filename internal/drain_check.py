#!/usr/bin/env python3
"""Read-only drain check for selected GitHub Actions workflows (Python standard library)."""
import argparse
import json
import math
import re
import subprocess
import sys
import time
from urllib.parse import quote


STATUSES = ("queued", "in_progress", "waiting", "pending")


def positive_seconds(value):
    seconds = float(value)
    if not math.isfinite(seconds) or seconds <= 0:
        raise argparse.ArgumentTypeError("must be a finite positive number of seconds")
    return seconds


def blocking_runs(targets, deadline=None):
    seen = set()
    for repository, workflow in targets:
        for status in STATUSES:
            page = 1
            while True:
                remaining = 30 if deadline is None else min(30, deadline - time.monotonic())
                if remaining <= 0:
                    raise TimeoutError("drain check timed out")
                path = (f"repos/{repository}/actions/workflows/{quote(workflow, safe='')}/runs"
                        f"?status={status}&per_page=100&page={page}")
                result = subprocess.run(["gh", "api", path], text=True, capture_output=True, timeout=remaining)
                if result.returncode:
                    raise RuntimeError(f"GitHub drain API failed for {repository} {workflow}: {result.stderr.strip()}")
                response = json.loads(result.stdout)
                rows = response["workflow_runs"]
                if not isinstance(rows, list):
                    raise ValueError("GitHub workflow_runs must be a list")
                # Filtered Actions queries expose at most 1,000 results. Never call a
                # truncated result drained, even if the last accessible page is empty.
                if response.get("total_count", 0) > 1000:
                    raise ValueError(f"GitHub result limit exceeded for {repository} {workflow}")
                for run in rows:
                    state = run["status"]
                    if state not in (*STATUSES, "completed", "requested"):
                        raise ValueError("GitHub run has an unknown status; cannot prove drain")
                    if state not in STATUSES:
                        continue  # A run may complete between the query and response.
                    fields = [run["html_url"], state, run["head_sha"]]
                    if any(not isinstance(value, str) or not value or any(c.isspace() for c in value) for value in fields):
                        raise ValueError("GitHub run is missing a valid URL, status or head SHA")
                    key = (repository, workflow, run["id"])
                    if key not in seen:
                        seen.add(key)
                        yield " ".join([repository, workflow, *fields])
                if len(rows) < 100:
                    break
                if page >= 10:
                    raise ValueError(f"GitHub pagination limit reached for {repository} {workflow}; cannot prove drain")
                page += 1


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", nargs=2, action="append", required=True,
                        metavar=("OWNER/REPO", "WORKFLOW.yml"),
                        help="repository and workflow file name; repeat for every workflow to drain")
    parser.add_argument("--wait", action="store_true", help="poll until drained or timeout")
    parser.add_argument("--timeout", type=positive_seconds, default=600,
                        help="overall wait timeout in seconds (default: 600)")
    parser.add_argument("--interval", type=positive_seconds, default=15,
                        help="seconds between polls (default: 15)")
    args = parser.parse_args(arguments)
    for repository, workflow in args.target:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            parser.error("each target repository must be owner/repo")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+\.ya?ml", workflow):
            parser.error("each target workflow must be a .yml or .yaml file name")
    deadline = time.monotonic() + args.timeout if args.wait else None
    try:
        while True:
            blocked = False
            for line in blocking_runs(args.target, deadline):
                blocked = True
                print(line, flush=True)
            if deadline is not None and time.monotonic() >= deadline:
                raise TimeoutError("drain check timed out")
            if not blocked:
                print("Drained: no queued, in_progress, waiting or pending runs in the selected workflows.")
                return 0
            if not args.wait:
                return 1
            time.sleep(min(args.interval, deadline - time.monotonic()))
    except (OSError, RuntimeError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        print(f"Drain check stopped: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
