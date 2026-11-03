# Ticket 45: staging candidate integration

## Preflight

On 2026-11-03 the clean Mission worktree was at
`d254d6a0d310afd78f3cbe1019b23b378e15a2c5`. A fresh fetch found staging
`origin/trunk` at `d9513475d70e21670ce906746d08e8e5bdb91434`.
`git merge-base --is-ancestor origin/trunk HEAD` succeeded: integration is a
fast-forward, with no rebase or merge commit needed.

Local checks passed before integration:

- `./gradlew check installDist`: 388 tests, no failures, errors or skips;
  formatting, Detekt and workflow safety checks passed.
- `bash internal/actionlint.sh`: passed with pinned-download checksum verified.
- `python3 -B internal/test_release.py`: 5 tests passed.
- `python3 -B internal/test_drain_check.py`: 9 tests passed.

This evidence commit intentionally advances the earlier candidate. The final
candidate SHA and exact hosted run URLs are recorded in the Mission implementation
submission after hosted validation, so recording them does not change the tested SHA.

## Integration boundary

Ticket 45 authorizes a normal fast-forward push of the Mission tip to
`thinkfishhook/mobile-delivery-public-staging` trunk, and small fixes if its hosted
checks fail. No force push, other branch, tag, repository rename or visibility
change belongs to this integration. The release date and app pins remain cutover work.

## Hosted evidence to inspect

All three workflows must succeed at the same final SHA, which must also equal
remote trunk:

- **Check** (`check.yml`): JVM tests, Detekt, formatting, workflow safety,
  release/drain helper tests and actionlint.
- **Engine self-test** (`self-test.yml`): engine installation on hosted Ubuntu
  and macOS, pinned asc installation, caller Java preservation and alternate
  installed Xcode selection on macOS.
- **Composite self-test** (`composite-self-test.yml`): disabled release/rollout
  behavior and credential-free TestFlight verification, including declared app
  build values, exclusion of undeclared values, Java preservation, verification
  totals and verified-SHA output without publication.

These workflows do not deliberately supersede a TestFlight push or run a failing
verification fixture. A green run therefore does not close the earlier live
superseded-cancellation or collapsed-output-rendering proofs. The JVM regression
tests exercise those code paths, but their hosted runner/UI acceptance remains
separate. Record only behavior actually exercised by the linked runs.
