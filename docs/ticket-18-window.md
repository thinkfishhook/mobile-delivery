# Ticket 18 authorized cutover window

## Authorization

Owner go-ahead received in this Mission conversation on 2026-11-03, recorded
at 18:42 UTC before remote mutation:

> I authorize the whole ticket 18 cutover window, including its marked remote steps and local pin amendments in the CordKeeper and Red Word Farmer Mission worktrees.

This supersedes the rehearsal's lack of authorization, not its historical observations.
The owner retains repository rename, visibility and private vulnerability reporting
changes. No other remote activity is authorized outside ticket 18's marked steps.

## Preparation

- Exact candidate `0d08ff34013dda8e96784962cce586b506085475` exported with
  `git archive` into ignored `build/ticket-18/candidate` and built with Zulu JDK
  21.0.11: `./gradlew --no-daemon installDist --console=plain`, PASS.
- Reused tickets 14/15/17 caller harnesses, changing only the CLI executable to
  that exact candidate. All four jobPolicy and four validate checks PASS in
  each app (24 checks total). Synthetic secret-presence flags only; no live
  Apple/credential operations. Logs/harnesses: ignored `build/ticket-18/`.
  CordKeeper initially lacked PyYAML under system Python; reran successfully
  with its existing `build/ticket-14/venv/bin/python`.
- CordKeeper's four pins refreshed; local amended commit
  `772279571ba029c560beef327a9253e5bc5728a7`.
- Red Word Farmer dedicated pin branch rebased onto integrated app-checks
  trunk `a5fc26474d986fc7b461beca6fe7c57b09e0e01b`, then four pins refreshed;
  local amended commit `306b64098ee9943027e5d5d4286bb509fda8d807`.
- The original rehearsal recorded ExpressiveVocab at
  `7660bf62cbe46c4ca282a6153817143269242ec9`, already pinning the candidate.
  Fresh final ranges below account for the subsequent allowed ticket 49 commits.
- actionlint PASS for both amended apps with the documented newer-schema
  `unexpected key "queue"` exclusion. Whitespace checks PASS.
- Original rehearsal ranges were one re-pin commit each. Red Word Farmer now
  includes the integrated app-checks parent. The fresh final range inspection
  below supersedes that original range record for ExpressiveVocab.
- At 18:43:12–18:43:36 UTC ran the rehearsal's full 15-target drain command
  with `--wait --timeout 5400 --interval 30`: exit 0, exact output:

  ```text
  Drained: no queued, in_progress, waiting or pending runs in the selected workflows.
  ```

- Owner subsequently reported Fish-Hook-001 through Fish-Hook-003 all Idle.
  Reminded owner to keep producers paused; runner idle alone is not a producer lock.
- At 18:44 UTC began 30-second GitHub polling, bounded to 90 minutes.
  Original clean repository ID is `1431647751`; private original is `1424383675`.
  Poll requires those identities at public mobile-delivery and unarchived private
  mobile-delivery-archive, plus enabled private vulnerability reporting.
  Raw timestamped observations are in ignored `build/ticket-18/owner-poll.jsonl`.
- Owner informed that repository changes can begin and app activity must remain
  paused through re-pin merges. No owner repository change observed yet.

Release dating will change the candidate; final pin SHAs and checks must be
recorded again after dating and before pushing the app branches.

## Owner changes observed

- Refreshed full drain check, 19:19:55–19:20:19 UTC: exit 0, same drained
  output as above; no runs cancelled.
- 19:20:23 UTC: original private repository ID observed at mobile-delivery-archive,
  private and unarchived.
- 19:20:54 UTC: clean repository ID observed at mobile-delivery, still private.
  Archive Actions access API returned `access_level=organization`.
- 19:21:59 UTC: clean repository PUBLIC, archive PRIVATE and unarchived;
  `GET repos/thinkfishhook/mobile-delivery/private-vulnerability-reporting`
  returned `enabled=true`. Owner changes complete. Poll succeeded within limit.
- Dated `[1.0.0]` as 2026-11-03. The dated commit will become the final release
  candidate only after local and exact-SHA hosted checks pass.

## Final candidate and release

- Final dated candidate: `61b8c7c330c9b361dbf99be81d0e080f602d1bc5`.
  Local `./gradlew --no-daemon check installDist` PASS; Gradle retained 388
  passing tests, zero failures/errors/skips; documentation-only checks reused
  test outputs. actionlint PASS; release-helper tests 5 PASS, drain-helper tests 9 PASS.
- Pushed candidate to public trunk, fast-forward from `0d08ff3`. The only three
  new commits are this ticket's rehearsal, authorization and dated release commits.
  Public heads API lists only trunk; clean-history root remains
  `dfe3aad73ac4a80ac26d29d1d2ba7eb06dece431`.
- All three hosted workflows completed SUCCESS on exact candidate:
  [Check](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41702774196),
  [Engine self-test](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41702774191),
  [Composite self-test](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41702774111).
  macOS jobs initially queued for roughly ten minutes before running successfully.
- Annotated `v1.0.0` created at the candidate and pushed after hosted checks passed.
  [Release workflow](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41704577075)
  completed SUCCESS. [Stable release](https://github.com/thinkfishhook/mobile-delivery/releases/tag/v1.0.0)
  published at 19:37 UTC with CLI zip and `.zip.sha256`. Downloaded both assets;
  SHA-256 matches `1e873550188486abe661e82c1d8157be04e761fced629840284d3df09aead66c`.
  ZIP CRC passed, containing launcher, Windows launcher and six runtime jars.
  `GET git/ref/tags/v1` confirms lightweight commit ref at exact candidate.
- Exact final candidate exported and CLI built again in ignored
  `build/ticket-18/final-candidate`; all 24 app caller checks PASS again.
  actionlint PASS for all three apps with documented concurrency.queue schema exclusion.

## Final app push ranges before publication

Fresh live-default queries confirm clean app worktrees and these entire ranges:

- CordKeeper: default `06210e9e10b4b3e8fe70d1d5c428356876e7df26`, exactly one
  commit `70daa6e87469ddae8437f4a6e3624a6055634f61` (re-pin and original ticket 14
  permission/Dependabot changes).
- Red Word Farmer: default `a5fc26474d986fc7b461beca6fe7c57b09e0e01b`, exactly one
  commit `a5ff5ec1fc8e1a4d8aaf704f497bb1f2c2e47468` (re-pin and required permission).
  Unrelated local trunk/Mission commits are excluded.
- ExpressiveVocab: default `a644ce91fd52ac27ee03b95e7b2a33f5f40265c8`, four commits:
  `7660bf62cbe46c4ca282a6153817143269242ec9` (original re-pin),
  `bd32796bbad713f87d1dbb0ce59aa5cfa15e1bed` (allowed Gradle-cache fix),
  `2a2327d0cc8bcbf4e014264e88f9230fa45965ac` (ticket 49 evidence),
  `07edc21efa948d727e62eb5bd89563dd3dc75fd5` (dated-candidate pin refresh).
  An initial amend mistakenly included the pin refresh in the evidence commit;
  immediately restored the exact original evidence commit and created the separate
  pin commit above, before any app push.

All 12 final references pin `61b8c7c330c9b361dbf99be81d0e080f602d1bc5 # v1.0.0`.
Read-only Actions queries across all three apps found zero runs created between
19:19:55 UTC and the pre-publication snapshot, so no observed rename-related run failure.

## App merges and resumption

- [CordKeeper PR 128](https://github.com/thinkfishhook/CordKeeper/pull/128) merged
  19:39:57 UTC, merge `4f95c62b002db3b4874cec6da38498218c7b9de8`.
- [Red Word Farmer PR 198](https://github.com/thinkfishhook/Red-Word-Farmer/pull/198)
  merged 19:40:33 UTC, merge `e9979f735bcc111a450625f48508034f145198bd`.
- [ExpressiveVocab PR 15](https://github.com/thinkfishhook/ExpressiveVocab/pull/15)
  merged 19:40:47 UTC, merge `35d37bab324fd6afc8b003a8485deaa7ccf728d3`.
- PR bodies contain archive rollback pins. Merged normally without admin/bypass
  options. RWF/EV PR CI was still pending at merge; offline caller checks and exact
  tool checks were green, and first post-merge deliveries are watched below.
- Verified all 12 references by reading each workflow from the live default branch
  via GitHub contents API. Informed owner pushes and dispatches may resume.
- Updated assigned clone origin to `https://github.com/thinkfishhook/mobile-delivery.git`.

## Quickstart pins, test first

- Extended QuickstartTest to require all four actions in the overlay and README,
  eight references total, identical full SHA and exact `# v1.0.0` comments.
- Initial test harness Path list construction failed with NoSuchFileException;
  corrected it before the meaningful red run. Meaningful red: AssertionError
  expected `[v1.0.0]`, actual `[v1 preview (unreleased)]`.
- Updated four overlay references and README examples to the released SHA, removed
  obsolete future-cutover wording from README and quickstart instructions.
- `./gradlew --no-daemon format check installDist` PASS; 389 tests, zero
  failures/errors/skips; formatting, Detekt and workflow checks PASS.
  actionlint PASS; release helper tests 5 PASS; drain helper tests 9 PASS.
  Full red/green logs retained in ignored `build/ticket-18/`.

## Final watched outcomes

Quickstart commit `b31a7c5296ac2876ffe5be3db2b8f498ba2cff79` was pushed to public
trunk. Its Check and Engine self-test succeeded. Composite self-test attempt 1
failed because the hosted macOS job was never acquired: zero steps and no runner
name, with annotation "The job was not acquired by Runner of type hosted even
after multiple attempts" and notices explicitly citing macOS arm64 capacity.
Retried only the unsuccessful job. Attempt 2 completed SUCCESS at 20:03:47 UTC:
[Composite self-test](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41706026752).
No source change or app rollback was required. This was after the already-green
checks on the immutable v1.0.0 release candidate.

All three first delivery-branch pushes on v1.0.0 completed SUCCESS, with
`IOS_DELIVERY_ENABLED=true` and `DELIVERY_ACTION_SHA=61b8c7c330c9b361dbf99be81d0e080f602d1bc5`
in their logs. Receipt tags independently queried through GitHub's tags API
peel to their exact re-pin merge commits:

| App and first run | Runner | Job start–completion (UTC) | Receipt tag | Queue wait from run creation |
| --- | --- | --- | --- | --- |
| [CordKeeper 41705377422](https://github.com/thinkfishhook/CordKeeper/actions/runs/41705377422) | Fish-Hook-002 | 19:40:04–21:31:26 | `deploy/testflight/26.11.01/1556.1` | 4s |
| [Red Word Farmer 41705472450](https://github.com/thinkfishhook/Red-Word-Farmer/actions/runs/41705472450) | Fish-Hook-003 | 19:55:24–20:22:15 | `deploy/testflight/26.11.01/757.1` | 14m48s |
| [ExpressiveVocab 41705510026](https://github.com/thinkfishhook/ExpressiveVocab/actions/runs/41705510026) | Fish-Hook-001 | 20:22:48–20:58:28 | `deploy/testflight/26.11.01/130.1` | 41m58s |

All dates above are 2026-11-03. Queue contention coincided with the EV branch/PR/main
CI workloads on the same three Macs. Record this for portfolio queue-impact
acceptance; it is not a general performance/capacity guarantee. The watcher
recorded all three successful outcomes by 21:31:37 UTC; the final fresh API
verification and evidence collection occurred at approximately 22:12–22:15 UTC.
No app rollback was needed. Full run/job JSON and masked hosted logs remain in
ignored `build/ticket-18/`; no large evidence artifact download was needed.

The 22:15 UTC Actions snapshot of all three apps since 19:19:55 UTC contains only
successful runs: CK delivery and an automatic Dependabot update; RWF delivery and
two App checks runs; EV delivery and three CI runs. No observed rename-related
workflow failure. No delivery was cancelled.

[Public acceptance tracking issue #1](https://github.com/thinkfishhook/mobile-delivery/issues/1)
is open, listing remaining acceptance checks. Updated the three first-delivery
items as complete with run and receipt evidence. Scheduled sweeps, full Dependabot
upgrade proof, real-red/red-streak/cancellation/trigger proofs, seven-day billing
and final archive handoff remain portfolio-follow-up work, not ticket 18 claims.

Final verification: public repo remains PUBLIC with vulnerability reporting enabled;
archive remains PRIVATE/unarchived with organization Actions access; v1 still
points at the v1.0.0 candidate; public trunk is the quickstart commit above;
assigned clone origin uses the public repository URL. This final evidence-only
update is committed locally for Mission submission and does not move public trunk
or the release tags.
