# PR workflow

Branch from the current integration head. In this repository, use a short-lived
`codex/` branch and target `trunk`. Preserve the local pre-push hook chain.

Push the topic branch, then open a normal PR using the existing developer GitHub login
and immediately enable native rebase auto-merge:

```sh
gh pr create --base trunk --title "Describe the change" --body-file /path/to/pr-description.md
gh pr merge --auto --rebase --match-head-commit "$(git rev-parse HEAD)"
```

Enable repository auto-merge and allow rebase merging only. Protect the integration
branch with required PRs, required checks, up-to-date branches and linear history.
Apply protection to administrators and prevent force pushes and deletion on that branch.
Require zero human approvals. In this repository, require the Check, engine matrix and
composite smoke checks. Apps should require the `PR checks` result in the
[caller example](https://github.com/thinkfishhook/mobile-delivery/blob/trunk/examples/quickstart/.github/workflows/pr-checks.yml) and their existing
app-specific checks.

Use the installed Codex GitHub App for advisory inline review. Required Actions checks
gate the merge, so it can complete before review. Use GitHub's native auto-merge without
a custom merge controller, a provider API secret or a personal-token secret. If the
GitHub plan prevents branch protection or auto-merge, resolve that setup before merging.

Wait for the required checks and fix failures on the topic branch. If the integration
branch advances or GitHub cannot rebase a green PR, rebase onto the current integration
head and push the updated topic branch with `--force-with-lease`. Re-enable auto-merge
for the new head using the command above and wait for fresh checks.
