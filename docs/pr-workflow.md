# PR-first development and deployment

Make each change on a short-lived branch from the current integration branch. Open a
normal PR through your existing authenticated GitHub account, then enable GitHub native
squash auto-merge. Do not push normal changes directly to main/trunk.

```sh
gh pr create --base trunk --title "Describe the change" --body-file /path/to/pr-description.md
gh pr merge --auto --squash --match-head-commit "$(git rev-parse HEAD)"
```

Use `main` for ExpressiveVocab and `trunk` for CordKeeper, BigAndPip and mobile-delivery.
Keep the existing local pre-push review and deterministic/Beads hook chains. If the hook
requires a known remote base for a new branch, create the topic branch on GitHub from the
current integration commit before pushing changed commits; this publishes no new code.

## Checks and merge policy

`Pull request checks` runs the existing app verifier against GitHub's immutable PR merge
candidate. Setup and verification use the same source. Admission runs on Ubuntu and refuses
forks before using app runners. The required `PR checks` result passes only when admission
and verification both succeed; failures, cancellation and skipped work cannot pass it.
PR verification runs on fresh GitHub-hosted Apple Silicon Macs, separate from persistent
integration/signing runners. It receives no signing, receipt, upload or provider credentials.
The hosted image provides the configured Xcode 26.6 and iOS 26.5 simulator. PR edits,
including retargeting the base, rerun checks; prepare the title/body before opening when possible.
Each PR has its own cancellable verification group and cannot occupy the delivery queue.

BigAndPip additionally runs its Linux and Windows installer checks on every PR, with stable
required names. Its integration-push installer path filters are retained. The shared tool
requires its Check, engine matrix and composite smoke tests. Integration-only pushes and
PR events avoid duplicate branch-push verification.

Enable repository auto-merge and allow only squash merging. Protect main/trunk with required
PRs, zero required human approvals, required Actions checks and up-to-date source. Apply
rules to administrators, prevent force pushes/deletion and retain linear history. Native
required checks are the merge gate; Codex approval and conversation resolution are not.
If the target branch advances, update the topic branch and obtain fresh required checks.

Auto-merge must be enabled on each PR. Agents do this immediately through the developer's
existing `gh` login. When opening a PR on the web, select auto-merge with squash. Do not
introduce a workflow that merges with `GITHUB_TOKEN`: its resulting events can fail to
start downstream workflows. No personal-token secret or custom merge controller is needed.

Private organization repositories need a GitHub plan that supports protected branches and
native auto-merge, such as GitHub Team. A plan-related refusal is an outstanding setup step,
not permission to bypass the required checks or claim enforcement is active.

## Codex review

The installed Codex GitHub App reviews normal PRs and posts inline feedback. Use its managed
model; no provider API workflow, review-only bot PR or automatic finding-to-Issue bridge is
part of this workflow. Codex is advisory. CI-only auto-merge does not guarantee its review
finishes before merging. Useful follow-up issues can be created through normal human triage.

## Delivery after merge

The squash merge creates the integration commit. The existing integration CI verifies that
exact commit, then prepares one signed package. TestFlight Deploy uploads that run's exact
package and records the same source/digest. A PR package cannot substitute for the new
integration commit. Failure reporting, paused-delivery switches, App Store schedules,
receipt recovery and branch-safe numbering retain their existing contracts.

Manual branch TestFlight dispatch remains an explicit human-selected build/upload without
CI or AI prerequisites. Live manual validation stays pending until naturally exercised;
no experimental delivery is required for bookkeeping.
