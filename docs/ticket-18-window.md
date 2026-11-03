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
- ExpressiveVocab remains `7660bf62cbe46c4ca282a6153817143269242ec9`, already
  pinning the candidate.
- actionlint PASS for both amended apps with the documented newer-schema
  `unexpected key "queue"` exclusion. Whitespace checks PASS.
- Each dedicated pin branch remains exactly one commit ahead of its remote
  default; Red Word Farmer now also includes the integrated app-checks parent.
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
