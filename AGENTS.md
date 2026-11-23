# Working agreement

Read CONTRIBUTING.md before changes. Use a short-lived `codex/` branch and a normal PR
into `trunk`; do not push normal changes directly to the integration branch. Preserve
existing local risk-review hooks and test-first behavior changes.

Open the PR through the existing authenticated developer `gh` account with a clear title
and `--body-file`. Immediately enable native rebase auto-merge with
`gh pr merge --auto --rebase --match-head-commit "$(git rev-parse HEAD)"`.
Wait for required checks, fix failures and update the branch if the base advances. Do not
bypass checks or substitute a GitHub Actions merge controller. Zero human approvals are
required; Codex App review remains advisory and inline.

Follow docs/pr-workflow.md. The review-only bot-PR and automatic finding-Issue prototype
is retired. No provider API key or personal-token secret is part of the selected workflow.
