# Upgrade to the split v1.1 contract

v1.1.0 is an additive minor release. The existing all-in-one `testflight`, maintenance,
release, rollout, schema-1 package and receipt readers remain supported. New CI and
standalone deployment reserve schema-2 package identities. Private app evidence belongs
in each app's migration ticket. Stable [v1.1.1](https://github.com/thinkfishhook/mobile-delivery/releases/tag/v1.1.1)
is `1050b2bf67c2e22289ee7452cf12bf089b6699ba`. Use that full SHA consistently for all
shared actions and a compatible CLI (v1.1.1 or newer). The previous v1.1.0 caller validator rejects
the corrected Checks-read grant. Package/identity readers remain compatible across this
patch. Current trunk templates pin the actual stable release; the tagged source retains
explicitly labelled implementation-candidate pins.

## Prepare and integrate

1. Read the release notes. Inventory every existing workflow/action pin, deterministic
   command, fresh report gate, app build value, binary check, runner, switch and schedule.
   Preserve these app-owned requirements. Start from `examples/xcode` (`main`) or
   `examples/kmp` (`trunk`); `examples/quickstart` supplies marked native/KMP configuration.
   Set all branch inputs and filters to the existing integration branch. Do not overwrite
   existing configuration with neutral example IDs. Retain App Store switches/schedules.
2. Replace the old automatic TestFlight push workflow with `ci.yml` named **Continuous
   Integration** and a separate `testflight-deploy.yml` named **TestFlight Deploy**.
   Disable/remove the old push trigger in the same integration change to avoid two
   producers. The former runs existing deterministic checks once, then prepares and retains
   one signed package. The latter consumes that exact successful run's bytes, without
   verification, archive or signing on the automatic path. Manual dispatch has no artifact
   form: GitHub's branch selector freezes the chosen SHA and builds/uploads without tests.
3. Keep all app resource operations in `ios-delivery`, `queue: max`,
   `cancel-in-progress: false`. Automatic admission requires the current integration head;
   an older success cannot bypass a newer pending, red or cancelled commit. Already accepted
   Apple uploads continue recovery. Explicit manual choices are not invalidated by branch
   movement. Do not add a main-only dispatch guard or CI/AI prerequisite to manual delivery.
4. Add independent boundary reporter jobs exactly as shown. Reporters run on Ubuntu
   even if operation setup fails, with Contents/Actions/Checks read and Issues write.
   The selected Codex App reviews normal PRs inline; no provider API workflow or secret
   is required. Its findings do not gate CI or deployment. The separate API-based action
   remains an optional supported feature described in hosted-review.md.
5. Use `pr-checks.yml` for normal same-repository PRs, including Dependabot. Admission
   rejects forks before app-runner use; required `PR checks` passes only after complete
   verification. Setup and shared verify use the immutable event merge candidate
   `github.sha`. Keep existing app checks, no PR signing/Apple credentials and no feature
   push duplicate. Configure native rebase auto-merge and required checks using
   [the PR workflow](pr-workflow.md). Retain the grouped dependency rule.
6. Validate with the CLI from the same compatible release as the action pins. Download
   the current stable release zip and verify its checksum. The v1.1.2 local review
   runtime includes scoped shallow-history checks and explicit immutable dependency context;
   these local-only fixes do not require changing v1.1.1 action pins. The v1.1.0 CLI rejects the corrected
   reporter permissions. From the app root:

   ```sh
   /absolute/mobile-delivery/bin/mobile-delivery callerChecks
   ```

   This is a structural check, not live verification. Run pinned actionlint and the app's
   existing local quality gates. `workflowChecks` remains the tool repository's own check.
   Where app builds need existing named values, retain `build_environment` and bind only
   those values on build/verification action steps; never add provider/delivery credentials
   to app commands or global/job environments.
7. Install Node 22+ and an authenticated supported provider CLI. Remove the paid AI stage
   from an older hook while retaining its deterministic/Beads work, then compose the
   packaged shared hook:

   ```sh
   node /absolute/mobile-delivery/share/review/cli.mjs install --provider=codex --model=CURRENT_MODEL
   ```

   Keep this immutable distribution available. Local review blocks unresolved broad or
   irreversible risks independently of priority; exact-key human acceptance is documented
   in [local risk review](local-risk-review.md). It is not a hosted deployment gate.

## Record acceptance honestly

Record the stable version/full SHA, app caller SHA, checks and structural validation.
Then observe a normal integration push: all configured deterministic checks pass once;
the retained manifest identifies its producing run/attempt, source, origin, reserved
version/build and IPA digest; automatic deployment consumes the same package/digest and
confirms availability in the configured internal TestFlight group. Link the receipt and
both run summaries. A green configuration check or a prepared IPA alone does not satisfy
live delivery acceptance. An existing product verification failure remains a real failure;
do not bypass checks to obtain evidence.

Shared fixtures cover manual branches and current-head supersession. Live manual
validation can remain explicitly pending until naturally exercised; do not deploy an
experimental branch solely for bookkeeping. Review findings/provider failures are fixture
validated independently of green deterministic CI. Record actual live AI outcomes separately
and never infer a clean review from missing credentials or inference failure.

The original v1 acceptance ledger uses v1.0.2 or a later stable full SHA as the upgrade
baseline. Preserve its historical observations; refresh evidence and link explicit owner
policy changes. Queue study is a nonblocking follow-up. Only close live criteria with
actual evidence or an explicit owner exception. See [portfolio acceptance](portfolio-acceptance.md).

## Roll back without reusing identities

Before the first schema-2 reservation, restore prior caller files and immutable stable
pins together. After schema-2 identities exist, **v1.0.2 and older stable releases cannot
safely read them**. Preserve all preparation ledger refs, receipts and retained bytes.
Never decrement/reuse build numbers, replace artifacts or move published version tags.

Prefer keeping v1.1's compatible engine readers while reverting app caller configuration
or pausing new preparation with the existing delivery switch. Let accepted uploads finish
processing/distribution recovery before changing their caller shape. Automatic missing or
expired packages fail explicitly; they are not rebuilt under the same sealed identity.
A rerun repairs receipts/distribution against Apple's existing build.

If the v1.1 caller path itself must be reverted, the prior reviewed compatibility bridge
`2a8f5e06c49be25dcf8ddd07e0467f2a8bde8106` supports schema-2 reservations and attempt-aware
package manifests. It is an implementation commit, **not an older stable release**. Use
its matching CI/deployment caller shape; remove `ai-review` and `report-failure` calls
which do not exist at that revision. Do not substitute the earlier v1.0.2/c999/6ae readers.
Validate the resulting callers and keep incident tracking active in the app. Resolve the
failure and return to a compatible stable release without rewriting identities. Public
App Store switches and schedules remain a separate owner decision throughout rollback.
