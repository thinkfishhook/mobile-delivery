# Ticket 18: cutover rehearsal, 2026-11-03

## Authorization and disposition

This is a rehearsal only. The owner approved beginning ticket 18, but explicitly
excluded pushing, merging and PR creation. No separate whole-window go-ahead has
been given. No remote state was changed, no runs were cancelled, and no repository
renames, release tags, changelog dates or example pins were changed.

The cutover is **blocked**, not complete. Two app branches need their candidate
pins refreshed. The assignment restricts writes to the staging Mission worktree,
so this rehearsal inspected the app worktrees read-only rather than amending them.
App-worktree write scope must be resolved before those amendments and their
candidate-CLI checks. A new drain check is required after the owner stops pushes
and dispatches; this rehearsal does not establish an exclusive window.

## Candidate

At 2026-11-03T17:50 UTC, `git ls-remote origin refs/heads/trunk` returned
`0d08ff34013dda8e96784962cce586b506085475`, also the local starting HEAD.
The GitHub Actions API query filtered by this head SHA confirmed completed/success:

| Workflow | Run |
| --- | --- |
| Check | https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41643999106 |
| Engine self-test | https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41643999038 |
| Composite self-test | https://github.com/thinkfishhook/mobile-delivery-public-staging/actions/runs/41643999021 |

All six constituent check runs also returned success with that exact head SHA.
The `[1.0.0]` changelog section remains undated. Dating it during the window will
create a new candidate that needs hosted checks and another app pin refresh.
This evidence commit is local and is not the release candidate.

## App branches and complete push ranges

Queried each live remote default branch with `gh repo view`, `git ls-remote` and
GitHub's branch API. Used `git log --format='%H %s' <live-default-sha>..<pin-branch>`;
these ranges do not rely on stale origin tracking refs. App worktrees were clean.

| App | Live default SHA | Dedicated pin branch | Entire range |
| --- | --- | --- | --- |
| CordKeeper | `06210e9e10b4b3e8fe70d1d5c428356876e7df26` (trunk) | `mission/ticket-14-v1-pins` | `da1973a8b29fe26edc740b05e8d23e6ac3bc383d` — Pin mobile-delivery v1.0.0 for the public cutover |
| Red Word Farmer | `a5fc26474d986fc7b461beca6fe7c57b09e0e01b` (trunk) | `mission/ticket-15-v1-pin` | `30fea0d32c21427f4cc7a9c22c2b887cb5647582` — Pin mobile-delivery v1.0.0 for the public cutover |
| ExpressiveVocab | `a644ce91fd52ac27ee03b95e7b2a33f5f40265c8` (main) | `mission/ticket-17-v1-pins` | `7660bf62cbe46c4ca282a6153817143269242ec9` — Pin mobile-delivery v1.0.0 for cutover |

Each range is exactly one re-pin commit; no unrelated commit is in those ranges.
In particular, do not push Red Word Farmer's original Mission branch or local trunk,
which retains unrelated work. Its active checkout is currently an evidence branch,
`mission/ticket-47-integrate-app-checks`, not the dedicated pin branch. Its pin branch
is also behind remote trunk by the one app-checks commit `a5fc2647`; refresh onto
current trunk when updating its pins so integrated app-checks/Dependabot work survives.
ExpressiveVocab's prior evidence and non-pin work are already on remote main.

Inspected all four action references on each dedicated pin branch:

- CordKeeper: all four still pin `d254d6a0d310afd78f3cbe1019b23b378e15a2c5`
  with `# v1.0.0`; **refresh required**. Prior rollback SHA:
  `bd1948140122a08cdb5b15c876c3a451c8edcf6b`.
- Red Word Farmer: all four still pin `d254d6a0d310afd78f3cbe1019b23b378e15a2c5`
  with `# v1.0.0`; **refresh required**. Prior rollback SHA:
  `0045a541475c2205c55f64557b8d0213574c93ce`.
- ExpressiveVocab: all four pin the final candidate
  `0d08ff34013dda8e96784962cce586b506085475` with `# v1.0.0`.
  Prior rollback SHA: `b28d3c3b7549f75988efc25880f1648a4ef336fd`.

No pin changes were made, so candidate-CLI checks for refreshed pins remain pending.

## Drain rehearsal

Ran from the assigned staging worktree, approximately 17:50:59–17:51:40 UTC:

```sh
python3 internal/drain_check.py \
  --target thinkfishhook/CordKeeper testflight-delivery.yml \
  --target thinkfishhook/CordKeeper delivery-maintenance.yml \
  --target thinkfishhook/CordKeeper app-store-release.yml \
  --target thinkfishhook/CordKeeper app-store-rollout.yml \
  --target thinkfishhook/Red-Word-Farmer ios-delivery.yml \
  --target thinkfishhook/Red-Word-Farmer delivery-maintenance.yml \
  --target thinkfishhook/Red-Word-Farmer ios-public.yml \
  --target thinkfishhook/Red-Word-Farmer app-store-rollout.yml \
  --target thinkfishhook/Red-Word-Farmer app-checks.yml \
  --target thinkfishhook/Red-Word-Farmer desktop-packages.yml \
  --target thinkfishhook/ExpressiveVocab testflight.yml \
  --target thinkfishhook/ExpressiveVocab maintenance.yml \
  --target thinkfishhook/ExpressiveVocab app-store-release.yml \
  --target thinkfishhook/ExpressiveVocab app-store-rollout.yml \
  --target thinkfishhook/ExpressiveVocab ci.yml
```

Exit **1**, exact output:

```text
thinkfishhook/ExpressiveVocab app-store-rollout.yml https://github.com/thinkfishhook/ExpressiveVocab/actions/runs/41688188036 in_progress aadec7dcdb154aa6f8f3f139a3462800e7adbb78
```

At 17:51:59 UTC, `gh run view` independently confirmed this workflow_dispatch run
was in progress (created 17:51:03 UTC). Nothing was cancelled. Rehearsal unit checks:
`python3 -B internal/test_drain_check.py` — **9 tests passed**, exit 0.

## Repository state and remaining work

At approximately 17:50–17:51 UTC, `gh repo view ... --json name,visibility`
reported both `thinkfishhook/mobile-delivery` and
`thinkfishhook/mobile-delivery-public-staging` PRIVATE, with their original names.
`gh repo view thinkfishhook/mobile-delivery-archive --json name,visibility,isArchived`
could not resolve that repository. No name swap is established. Origin remains
`https://github.com/thinkfishhook/mobile-delivery-public-staging.git`.

Before resuming: obtain the owner's explicit whole-window go-ahead and app edit
scope, refresh the two stale app branches and validate their callers, then stop
pushes/dispatches and run the full drain command with `--wait`. Only after successful
drain should the owner rename the repositories, make the clean repository public
and enable private vulnerability reporting. Poll those changes during the authorized
window. Release publication, exact-tag checks/assets/v1 verification, app PR merges,
first deliveries, quickstart test-first changes and the public tracking issue remain
unperformed. Do not treat this rehearsal as ticket completion.
