# Design: holds on bad TestFlight builds

Status: manual holds are implemented. Automation is outside the delivery tool.

## Where a hold lives

A hold is an issue labelled `testflight-hold`, filed from the issue form
`.github/ISSUE_TEMPLATE/testflight-hold.yml`. The form has two required fields:
- **Build**, for example `26.09.01/404.1`. It resolves to a commit through the build's
  `deploy/testflight/<build>` receipt tag.
- **Reason**, for example a crash report or tester feedback.

An issue beats the alternatives:

| Option | Why not |
| --- | --- |
| Tag `hold/…` | Lifting it deletes the tag, which loses the audit trail. It also needs the Workflows-permission token, and is awkward to create from a phone. |
| Deployments or Releases | API-only, and both were already abandoned. |
| Delivery record | Retired in Phase 6. |
| Expire the build in App Store Connect | Irreversible, removes the build from testers, and blocks only that one build. It stays a valid emergency lever (expired builds are already ineligible). Documented, not automated. |

Issues also give you the GitHub phone app, discussion and history.

## What a hold blocks

A bug lives in code, not in one build. A hold on the build for commit `H` blocks every
build whose commit descends from `H` (including `H`), until a fix lands:

- **Fixed:** the issue is closed by a trunk commit `F` (`Fixes #42`). GitHub records `F` as
  `commit_id` on the issue's `closed` event. Builds whose commit contains `F` become
  eligible. Builds between `H` and `F` stay blocked.
- **False alarm:** the issue is closed any other way by a person. The hold is lifted
  entirely.
- **Closed by a bot without a commit:** the hold stays in force. Automation can add holds
  but never lift them.
- **No record of who closed it:** if the issue's events show no `closed` event by a person,
  the hold stays in force. Only evidence of a person lifts a hold.

Builds older than `H` are unaffected. If one of them is still newer than the last App
Store release, the next release (scheduled or manual) may promote it instead.

## When holds are read

App Store selection, scheduled or manual, lists labelled issues with `state=all`, because a
closed hold still blocks the builds between `H` and its fix. It reads them again
immediately before submitting. Holds have no age cutoff: fresh descendants can still
contain an old bug, and an explicit retry can rebuild a commit before an old fix.
The hold check is one more named eligibility rule
(`is not held`), evaluated last so it only runs for builds that pass the cheaper rules.
Builds are checked newest first and selection stops at the first eligible one, so older
builds are never checked. When no hold can block anything, no build's commit is read at all.

**Fail closed.** An open hold whose build cannot be parsed or has no receipt blocks every
App Store release. Scheduled runs skip with an action, and manual runs stop. A GitHub read
that fails while resolving a hold stops the run red: it never makes a hold look malformed,
and never lifts one.

**After submission.** If the version waiting for review, or the version in a phased
rollout, is built from a held commit, the sweep reports an action. For review: remove the
submission from review in App Store Connect. For a rollout: dispatch `pause`. That action
opens an incident. Nothing changes Apple automatically.

**Sweeps** list open holds in their summary. A hold is a known state, so the run stays
green.

Costs:
- One issue listing per App Store selection.
- One events read per closed hold, to get its fix commit.
- Nothing per sweep beyond one listing of open holds.

## Setup (you)

- `gh label create testflight-hold --color B60205 --description "Blocks App Store release of a TestFlight build and later builds"`
- `gh label create ios-delivery --color 5319E7 --description "Delivery incident"`. The API
  usually creates missing labels on issue creation, but creating it once removes the doubt.
