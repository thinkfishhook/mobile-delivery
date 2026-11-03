# Ticket 19: initial portfolio observation

## Status and boundary

Started 2026-11-03, snapshot 22:18–22:22 UTC. **Incomplete:** normal app activity
and seven complete billing days are still required. No push, tag, PR, merge,
workflow dispatch or manufactured failure is authorized or performed for this
ticket. The local `[1.0.1]` docs-only candidate remains undated until an approved
release window. No app-neutral behavior defect was found in this initial sample.

[Public tracking issue #1](https://github.com/thinkfishhook/mobile-delivery/issues/1)
remains open. Existing cutover proof is in [ticket 18](ticket-18-window.md).
This ticket adds a reusable [acceptance evidence guide](portfolio-acceptance.md).

## Independently refreshed delivery evidence

GitHub Actions run/job APIs and receipt ref/tag APIs confirm:

| App / successful push | Self-hosted runner | Receipt (all under `deploy/testflight/`) | Peeled caller SHA |
| --- | --- | --- | --- |
| [CordKeeper](https://github.com/thinkfishhook/CordKeeper/actions/runs/41705377422) | Fish-Hook-002 | `26.11.01/1556.1` | `4f95c62b002db3b4874cec6da38498218c7b9de8` |
| [Red Word Farmer](https://github.com/thinkfishhook/Red-Word-Farmer/actions/runs/41705472450) | Fish-Hook-003 | `26.11.01/757.1` | `e9979f735bcc111a450625f48508034f145198bd` |
| [ExpressiveVocab](https://github.com/thinkfishhook/ExpressiveVocab/actions/runs/41705510026) | Fish-Hook-001 | `26.11.01/130.1` | `35d37bab324fd6afc8b003a8485deaa7ccf728d3` |

These are the first v1 deliveries previously verified against tool SHA
`61b8c7c330c9b361dbf99be81d0e080f602d1bc5`. Tagger times are respectively
21:27:19, 20:17:28 and 20:54:47 UTC on November 3. Each app's
`GET repos/thinkfishhook/APP/actions/runs?branch=URL_ENCODED_RECEIPT&per_page=100`
returned `total_count: 0`. Complete run lists since cutover show no extra run at
these receipt pushes. Current workflow inventories use branch-only push filters
or have no push trigger. Receipt exclusion is observed for these three receipts;
it is not a guarantee about future workflow edits.

## Dependabot and Xcode

[CordKeeper update job](https://github.com/thinkfishhook/CordKeeper/actions/runs/41705387861)
succeeded. Its log shows HTTP 200 public `mobile-delivery` fetches, all four 1.0.0
actions checked, `Nothing to update for Dependency Group: 'mobile-delivery'`, and
`Finished job processing`. No repository-access error was observed.
Red Word Farmer and EV have no post-cutover update job in this snapshot; their
success and grouped upgrade proof remain pending. Existing EV PR 13 updates
upload-artifact and is not mobile-delivery grouped-upgrade evidence.

Live default-branch `.github/delivery.json` in all three apps has `xcode: 26.6`.
The first delivery logs collected in ticket 18 show configured Xcode selection;
the three delivery run links above are the runner evidence. All EV workflows
except manual rollout now specify self-hosted macOS ARM64. Its three observed
post-pin CI runs also used Fish-Hook-001/003:
[branch](https://github.com/thinkfishhook/ExpressiveVocab/actions/runs/41705397068),
[PR](https://github.com/thinkfishhook/ExpressiveVocab/actions/runs/41705482638),
[main](https://github.com/thinkfishhook/ExpressiveVocab/actions/runs/41705510000).
Maintenance and App Store release have not run at the v1 pin; full workflow
execution coverage remains pending. These human-authored branch/PR runs do not
prove Dependabot runs once per commit.

## Billing and queue baseline

Read-only `GET organizations/thinkfishhook/settings/billing/usage?year=2026&month=11&day=3`
succeeded. Its EV row at `2026-11-03T01:03:19Z` reports **15 minutes** of
`Actions macOS 3-core`, with net amount zero due to discount. Thus November 3
cannot count as a zero-hosted-macOS day. Earliest prospective complete UTC window:
November 4–10, queried after November 10 completes and rechecked for billing lag.
The authenticated billing report is linked through the
[organization billing page](https://github.com/organizations/thinkfishhook/settings/billing/usage);
raw organization usage stays in ignored local evidence, not this public document.

The three first-delivery end-to-end waits are 4s, 14m48s and 41m58s respectively
(run creation to delivery-job start). EV's entry CI jobs in its first branch run
started after 3s; main CI's entry job started after 19m01s amid the cutover batch.
This establishes a contention sample, not a before/after migration comparison.
Comparable pre-migration baseline and a normal post-migration sample remain needed.

## Remaining acceptance and authorization

No post-cutover scheduled sweep, real failed verification, red-streak lifecycle or
superseded delivery cancellation exists in the sampled app run lists. Historical
pre-v1 failures and unrelated cancelled EV CI runs do not meet those criteria.
Owner-only labels and issue actionability must be assessed when a live v1 incident
occurs. Do not force events merely to check boxes.

Publication, exact-candidate hosted checks, release assets, moved `v1`, grouped
Dependabot PR and final tracking closure remain pending. The owner must explicitly
authorize pushing public trunk and tagging v1.0.1. Ticket 18's cutover permission
was scoped to ticket 18. The rollback window stays open; no archive recommendation
is made before the live criteria are complete.

Raw read-only API responses and the Dependabot log are in ignored
`build/ticket-19/` for resumption. Local validation and commit SHA are recorded in
the Mission conversation; no completion submission will be made while acceptance
is pending.

## Local milestone validation

- `./gradlew --no-daemon check` with cached Zulu JDK 21.0.11: PASS;
  existing test outputs reused for documentation changes, 389 tests recorded,
  zero failures/errors/skips. Formatting, Detekt and workflow checks passed.
- `bash internal/actionlint.sh`: PASS, pinned archive checksum verified.
- `python3 -B internal/test_release.py`: 5 tests PASS.
- `git diff --check`: PASS.

No new behavior tests are needed for this documentation-only candidate. Its stable
changelog section intentionally has no release date yet; stable packaging must
wait for dating and final validation during the authorized release window.
