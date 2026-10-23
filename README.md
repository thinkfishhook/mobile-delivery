# mobile-delivery

Four GitHub Actions verify an iOS app, deliver it to TestFlight, reconcile delivery state,
and manage App Store releases. They support native Xcode and Kotlin Multiplatform apps.
Your repository owns the jobs, runners and secrets; each composite owns its delivery steps.

## Quick start

Start with delivery disabled. A push will build and test your app and retain its evidence.
It will not upload a build. The current prerelease is private: callers need organization
access until the clean-history public launch is complete.

You need an existing iOS app with passing automated tests, an App Store Connect API key,
an Apple distribution certificate exported as a password-protected P12, an App Store
provisioning profile, and a runner. Use `macos-26` or a self-hosted Mac with Git, `gh`,
Python 3, the configured Xcode and simulators. App builds keep the runner's Java settings;
configure any JDK your own Gradle build needs before the composite. The delivery engine
manages its own JDK 21. Install and authenticate the GitHub CLI locally (`gh auth login`).

1. **Copy the overlay.** From a tool checkout, copy the files into your app root:

   ```sh
   cp -R /path/to/mobile-delivery/examples/quickstart/. /path/to/app/
   ```

   Obtain the tool with `gh repo clone thinkfishhook/mobile-delivery` if needed. Review
   existing files with the same names before copying. Open the copied
   `DELIVERY-QUICKSTART.md` and edit every `# CHANGE ME` value. The default JSON is native
   Xcode; for KMP first copy `.github/delivery.kmp.example.json` over `.github/delivery.json`.
   JSON markers are inside placeholder strings: replace the whole value, including the
   marker. Choose the branch, app IDs, baseline commit, installed Xcode/simulator, test
   commands and public notes. The four action references are already pinned together.

2. **Create the environment and disable both deployment switches.** Run from the app
   checkout. Give a self-hosted runner group access to the app if applicable. For the
   current private tool, its owner must also allow organization callers under the tool's
   Settings → Actions → General → Access. No separate tool-download secret is needed.

   ```sh
   gh api --method PUT 'repos/{owner}/{repo}/environments/ios-delivery'
   gh variable set IOS_DELIVERY_ENABLED --body false
   gh variable set IOS_APPSTORE_ENABLED --body false
   ```

   Use one location for switches: remove conflicting environment-level overrides if
   these repository variables already exist alongside them.

3. **Add the environment secrets.** These commands prompt privately or read local files;
   do not put credentials into committed files or command arguments. Use your actual
   `.p8`, `.p12` and `.mobileprovision` paths:

   ```sh
   gh secret set ASC_KEY_ID --env ios-delivery
   gh secret set ASC_ISSUER_ID --env ios-delivery
   gh secret set ASC_PRIVATE_KEY --env ios-delivery < AuthKey.p8
   base64 < distribution.p12 | tr -d '\n' | gh secret set IOS_DISTRIBUTION_P12_BASE64 --env ios-delivery
   gh secret set IOS_DISTRIBUTION_P12_PASSWORD --env ios-delivery
   base64 < App.mobileprovision | tr -d '\n' | gh secret set IOS_PROFILE_BASE64 --env ios-delivery
   gh secret set IOS_RECEIPT_TOKEN --env ios-delivery
   ```

   `IOS_RECEIPT_TOKEN` is restricted to this **app repository**, with Contents and
   Workflows read/write permissions for receipt tags. Keep its expiry recorded. The
   default job token handles ordinary API reads and issue updates. If builds use existing
   app secrets, list their names in `build_environment` and bind them as `env:` on the
   TestFlight composite step; see [named build values](#secrets-and-variables).
   Disabled verification does not require deployment secrets, so you may defer them
   until enabling TestFlight; any declared app build values are still required.

4. **Commit and push to the configured delivery branch.** Open its **TestFlight** run.
   Success means the configured reports contain tests with no failures or skips and the
   evidence artifact is retained. No archive or upload should run. Missing Xcode or
   simulator errors mean the configured version needs an installed match; queue or
   permission errors include YAML to paste into the caller. You now have the first green
   run with delivery disabled.

## Enable TestFlight

Add any deployment secrets deferred above. Run the signing diagnostic, then enable the
switch when you are ready for every eligible branch push to deliver an internal build:

```sh
gh workflow run maintenance.yml --ref main -f operation=verify-signing
gh variable set IOS_DELIVERY_ENABLED --body true
gh workflow run testflight.yml --ref main -f operation=deliver
```

Replace `main` with your configured branch and wait for the diagnostic to pass before
turning on the switch. Confirm the build is available to the configured internal group,
the package and evidence artifacts are retained, and a `deploy/testflight/…` receipt tag
points to the delivered commit. Keep `IOS_APPSTORE_ENABLED=false` until the next section.
Use the [retry procedure](#beta-delivery) for an interrupted or already attempted commit.

## Enable the App Store

Make the **first App Store release manually** in App Store Connect and wait for it to
ship. It provides the approved metadata the automation copies. Resolve any unfinished
manual draft or review submission, then run the read-only preflight:

```sh
gh workflow run maintenance.yml --ref main -f operation=app-store-preflight
```

Replace `main` with your branch. Read its candidate, hold and metadata findings and
resolve blockers. Confirm the schedule in `app-store-release.yml`, your receipt token's
validity and retained symbols. Only then enable App Store changes:

```sh
gh variable set IOS_APPSTORE_ENABLED --body true
```

The next scheduled release may submit an eligible build. For a deliberate first automated
submission, use `gh workflow run app-store-release.yml --ref main -f operation=promote
-f reason="First automated release" -f rollout=phased` as one command. Rollout controls
live in `app-store-rollout.yml` and use their own queue. See the
[release acceptance steps](#enabling-app-store-release) for receipts and rollout checks.

## Upgrading

The overlay includes weekly Dependabot updates for GitHub Actions and groups the four
mobile-delivery references. Keep all four at the same full commit SHA with its version
comment. Read [CHANGELOG.md](CHANGELOG.md), review the proposed diff, and run your app's
verification before integrating. A stable major tag is available after v1 publication;
full SHA pins keep the reviewed version fixed. The CLI zip on each release is for local
bootstrap; the composites install and cache their engine automatically.

## Where configuration belongs

| Setting | Where it belongs |
| --- | --- |
| Delivery branch | Each composite's `with: branch` and the TestFlight push filter |
| Runner, environment, permissions, concurrency, timeout | Caller job |
| Operation, SHA, retry evidence, reason, rollout | Dispatch form forwarded as `dispatch: ${{ toJSON(inputs) }}` |
| Push filters, schedule/time zone, dispatch form, run title | Caller workflow |
| App IDs, team, internal group, baseline, Xcode, verification and notes | `.github/delivery.json` |
| Required/forbidden executable strings | Optional `binary_checks` in `.github/delivery.json` |
| Marketing-version month time zone | `timezone` in `.github/delivery.json` |
| Deployment switches | Repository or `ios-delivery` environment variables |
| App build environment names | `build_environment` in JSON; existing values bound in the TestFlight step's `env:` |
| Apple, signing, receipt and app credentials | `ios-delivery` environment secrets, individually bound by the caller |

Caller filenames are up to each app. Queue discovery reads each run's exact workflow
revision and recognizes the pinned composite. The four copy-in callers are in
[`examples/quickstart`](examples/quickstart).

All delivery and release jobs in an app share `ios-delivery` with `queue: max` and
`cancel-in-progress: false`; rollout controls use `ios-rollout`. Queues are per repository.
Signing sessions also share a per-user file lock when Mac runners use the same account.
The tool keeps no separate state database: decisions come from receipt tags, App Store
Connect, retained artifacts, issues and git.

### Optional release-binary checks

The shared tool always verifies IPA identity, archive paths, code signatures and signing
entitlements. App-specific public strings can also be checked in the main executable:

```json
"binary_checks": {
  "required_strings": ["example-production-marker"],
  "forbidden_strings": ["example-test-marker"]
}
```

Omit this section when the app needs no executable-string checks. Every required string
must appear, and no forbidden string may appear, in UTF-8, UTF-16LE or UTF-16BE at any byte
alignment. Use public literals already embedded in the app, never private credentials.
Configuration kept in an Info.plist or another resource should be validated by the app's
build checks. When migrating an app with existing binary-string requirements, put those
requirements in its configuration in the same change as the action-pin update.

### Native Xcode test reports

Create shared `UnitTests` and `UITests` test plans in the app's scheme. Each plan must
select at least one test, and the CI simulator must be installed on the chosen runner.
The native example runs `xcodebuild test` once per plan with separate
`-resultBundlePath build/tests/UnitTests.xcresult` and
`-resultBundlePath build/tests/UITests.xcresult` arguments. List both bundles in
`verification.test_reports`. The tool removes stale bundles before testing, checks each
bundle independently and keeps both summaries in the evidence. A missing, empty, failing
or skipped-test bundle blocks delivery. Match the scheme, workspace and simulator names
to the app; omit `ios.workspace` and use `-project` when no workspace is needed.

Each composite builds or restores the engine at its pinned revision, validates the caller's
queue and permissions, and owns credential boundaries, phase ordering and retention.
The caller does not duplicate those internal steps.

The engine uses JDK 21 without changing the caller's `JAVA_HOME` or Java on `PATH`.
Engine installation uses the runner's `JAVA_HOME_21_ARM64` or `JAVA_HOME_21_X64`, or downloads the
Zulu JDK pinned with its checksum in `src/main/resources/tool.json` into `RUNNER_TOOL_CACHE`.
Only the engine's own `bin` directory is added to `PATH`; app build commands keep the
caller's Java environment. Python 3 is required for this setup. Compiled engines are cached
by source content, runner OS and architecture; the app still selects the source with its SHA pin.

## Configuration (`.github/delivery.json`)

Unknown keys are refused, so a misspelt setting cannot fall back to a default.
The delivery branch is set only in the caller's required `with: branch` input.
Delete any old `branch` key from `delivery.json`; validation reports its new location.

| Key | Meaning |
| --- | --- |
| `app_id`, `bundle_id`, `team_id` | The App Store Connect app, its bundle ID and the signing team. |
| `internal_group` | TestFlight internal group ID that receives every build. |
| `baseline` | The first commit deliveries may select; older commits are never built. |
| `timezone` | Time zone that sets the YY.MM month in marketing versions (`YY.MM.RR`). |
| `xcode` | Exact Xcode version (`xcodebuild -version`). Verification refuses any other. |
| `ios.project` | The `.xcodeproj` holding the app target. Its signing is overlaid for the archive. |
| `ios.workspace` | Optional. Archive through this workspace (CocoaPods, several projects). |
| `ios.scheme`, `ios.target` | Scheme to archive; application target whose Release configuration is signed. |
| `ios.name` | File name for the archive and IPA. |
| `build_environment` | Optional list of app build variable names. Bind these names on the TestFlight composite step; all declared values must be present, including when delivery is disabled. |
| `verification.commands` | Commands (argument lists, no shell) run in order in a clean snapshot of the commit. They get an allowlisted environment plus the declared build values, never deployment credentials. |
| `verification.test_reports` | JUnit report directories (`TEST-*.xml`) or `.xcresult` bundles the commands write. Each must report at least one test, no failures and no skips. They are deleted before the commands run. |
| `verification.evidence` | Optional. More directories kept with the run's evidence. |
| `tester_notes` | TestFlight "What to Test" text. `Commit <sha>` is appended. |
| `release_notes.directory` | Default `release-notes.d`. Markdown files added or changed here since the last delivery become the release notes. |
| `release_notes.fallback` | Required public text chosen by the app. Used when no notes changed. |
| `incident_marker` | Default `mobile-delivery`. Owner marker in incident issue bodies; change it only while no incident is open. |
| `android_packages` | Optional `sdkmanager` packages installed before verification (KMP apps). |

Versions outside `YY.MM.RR` do not participate in marketing-version or release-history
comparisons. The run summary lists them in `ignoredVersions`, and preflight names them.
Existing drafts and unresolved submissions still block changes until resolved in App Store
Connect; ignoring their numbering does not take over someone's work.

The asc release (and its checksums) ships with the tool in `src/main/resources/tool.json`.

## Secrets and variables

| Name | Kind | Reaches |
| --- | --- | --- |
| `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY` | environment secrets | Apple-facing steps |
| `IOS_DISTRIBUTION_P12_BASE64`, `IOS_DISTRIBUTION_P12_PASSWORD`, `IOS_PROFILE_BASE64` | environment secrets | the archive step and the signing diagnostic |
| `IOS_RECEIPT_TOKEN` | environment secret | steps that write receipt tags and Releases |
| Names declared in `build_environment` | existing app environment secrets | verification and the archive |
| `IOS_DELIVERY_ENABLED`, `IOS_APPSTORE_ENABLED` | repository or environment variables | in-job gates for delivery and App Store changes |

Use existing individual secrets directly. For example, declare
`"build_environment": ["ANALYTICS_KEY", "ANALYTICS_HOST"]` in `delivery.json`, then add
these bindings to the TestFlight composite step:

```yaml
env:
  ANALYTICS_KEY: ${{ secrets.ANALYTICS_KEY }}
  ANALYTICS_HOST: ${{ secrets.ANALYTICS_HOST }}
```

The composite validates these declared values directly; no secret-presence JSON, combined
secret or secret duplication is needed.
Declared names cannot set runner variables or delivery credentials (`ASC_*`, `IOS_*`,
`GH_*`, `GITHUB_*`, `ACTIONS_*`, `RUNNER_*`, `DELIVERY_*`). Undeclared values are excluded
from app subprocesses.

## Operation requirements

Each caller job invokes one composite. The composite owns full-history checkout without
persisted credentials, cached engine installation, operation validation, evidence and
summaries. Bind its named inputs and forward the dispatch form as `dispatch`.
The job supplies the runner, `ios-delivery` environment, queue and permissions.
Validation reports configuration, operation, switch and missing-secret errors together;
messages name the file or input to correct and never print secret values.

In the table, **ASC** means `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY`;
**signing** means `IOS_DISTRIBUTION_P12_BASE64`, `IOS_DISTRIBUTION_P12_PASSWORD`,
`IOS_PROFILE_BASE64`. These and `IOS_RECEIPT_TOKEN` belong to the `ios-delivery`
environment. Declared app build values reach only app builds and verification.

| Operation | Additional inputs | Environment secrets | Switch | Runner |
| --- | --- | --- | --- | --- |
| `deliver` | Optional full `sha` | ASC, signing, `IOS_RECEIPT_TOKEN` | `IOS_DELIVERY_ENABLED` | Caller's arm64 Mac with pinned Xcode |
| `retry` | Full `sha`, `expected_attempt` (build number or `none`), `reason`; optional `new_attempt=true` to rebuild a delivered attempt | ASC, signing, `IOS_RECEIPT_TOKEN` | `IOS_DELIVERY_ENABLED` | Caller's arm64 Mac with pinned Xcode |
| `sweep` | None | ASC, `IOS_RECEIPT_TOKEN` | `IOS_DELIVERY_ENABLED` | Caller's runner |
| `audit` | None | ASC, `IOS_RECEIPT_TOKEN` | None | Caller's runner |
| `app-store-preflight` | None | ASC | None | Caller's runner |
| `verify-signing` | None | Signing | None | Caller's Mac |
| `promote` | `reason` for dispatch; optional `rollout=phased` or `all` | ASC, `IOS_RECEIPT_TOKEN` | `IOS_APPSTORE_ENABLED` | Caller's runner |
| `pause`, `resume`, `release-to-all` | `reason` | ASC | `IOS_APPSTORE_ENABLED` | GitHub-hosted Ubuntu, independent queue |

A switch must be exactly `true` or `false`. A disabled operation does not require its
Apple, signing or receipt secrets; disabled TestFlight pushes and schedules still check
the caller's queue and verify the app. Disabled manual deployment requests fail with an
explanation. Read-only preflight and signing diagnostics do not depend on either switch.
The composites handle these gates inside the job. Disabled scheduled App Store releases
finish successfully with a notice; manual release and rollout requests fail at the gate
before credential validation or deployment operations.

## Where state comes from

| Question | Answer |
| --- | --- |
| Is commit `S` delivered? | A `deploy/testflight/<version>/<count>.<n>` tag on `S`. |
| Next build number for `S` | `<count>` is `S`'s commit count. The attempt is one past every `<count>.<n>` in receipt tags, Apple builds, Apple uploads (failed and abandoned ones too) and retained artifacts, which are probed by name (`ios-build-<count>.<n>`) after the highest number the others show. |
| Marketing version for a new build | The month's `YY.MM.RR`, with `RR` one past the highest version of that month Apple has submitted or shipped. |
| Upload outcome uncertain? | The latest upload is `PROCESSING`, or `COMPLETE` with no build visible yet. An upload left in `AWAITING_UPLOAD` or `UPLOADING` after its job ended never completes, so it is not uncertain. |
| Interrupted delivery | The latest build number for `S` has a retained artifact or an Apple upload or build, but no receipt. A `retry` or rerun resumes distribution when Apple has a valid build, uploads the same retained bytes under the same number when Apple lists none, and otherwise takes a new number. |
| Upload cooldown | A failed upload with Apple error `90382`: no upload for a day after it. |
| App Store release state | App Store versions, review submissions and the `submit/appstore`/`deploy/appstore` tags. |
| Incidents and holds | Open issues labelled `ios-delivery` (found by an owner marker), and issues labelled `testflight-hold`. |

When a sweep repairs a receipt it must find the build's commit. TestFlight notes end with
`Commit <sha>`; for builds from before that, the sweep uses the first-parent commit on the delivery branch
with the build's count, and checks the count matches.

The concurrency group is the only lock GitHub enforces, and every job that changes Apple or
receipts is in it. Each Apple change also reads Apple right before it: a delivery decides
again after verification before taking a number, an upload checks Apple has not seen its
number, and distribution checks readiness. Two jobs the queue lost track of could still both
try one number, and Apple would refuse the second.

Two risks are accepted:
- **A number taken but never retained.** A runner that dies after archiving but before
  retaining leaves no trace, so the next attempt reuses that number. Nothing with it exists
  in Apple, Actions or tags, so the reuse is invisible.
- **An upload Apple has not listed yet.** Apple's lists can lag. A retained build with no
  listed upload is uploaded again under the same number, which Apple refuses (error 90189)
  if it already has it; that refusal counts as proof Apple has the build. A new number is
  taken only after Actions has deleted the bytes, 90 days later.

## Beta delivery

A push to the delivery branch delivers that exact commit: verify → archive → retain → upload →
distribute to the internal group → receipt tag. Intermediate commits are never built.
If the branch has moved on by the time a queued push run starts, the run finishes green as
"superseded by `<tip>`" and does no delivery work.

Dispatch **TestFlight delivery** on the delivery branch for anything else:

| Operation | What it does |
| --- | --- |
| `deliver` (default) | Delivers `sha` (default: the dispatch SHA) if it has never used a build number. |
| `retry` | Needs `sha`, `expected_attempt` and `reason`. `expected_attempt` is the latest build number for that commit (for example `404.1`), or `none`. |

A refused `deliver` prints the exact retry command, for example:

```sh
gh workflow run testflight.yml -f operation=retry -f sha=<sha> -f expected_attempt=404.1 -f reason="<why>"
```

To deliberately rebuild a commit that was already delivered, add `-f new_attempt=true`
to the retry command. The latest attempt must have a delivery receipt. This creates the
next build number after full verification. The expected number makes a duplicate dispatch
stale as soon as the new package is retained. Leave this option off for recovery: a
repeated recovery request must not accidentally become a request for another build.

A retry asks Apple first:
- A retry needs a nonblank reason and the current `expected_attempt`, even when a delivery
  receipt already exists; stale requests are refused before any work.
- With `new_attempt=false` (the default), a delivered latest attempt is a no-op. An older
  receipt does not hide an unfinished newer attempt from recovery or sweeps.
- If Apple is still processing the last upload, the retry is refused. It never uploads again
  or takes a new number while an outcome is uncertain.
- If Apple has the build but testers do not, distribution resumes without uploading.
- If a build was retained but Apple lists no upload for it, the retry uploads the same
  retained bytes under the same version and number, even if new builds have moved to a later
  version since. Apple refuses a number it already has in that version, so an upload Apple
  has not listed yet can never become a second build. If Apple refuses because that version
  has shipped, the upload counts as failed and the next retry takes a new number.
- If the last upload failed, was invalid or was abandoned, or the retained bytes have
  expired, the retry builds again with the next number.

An Actions **Re-run** continues its own run: it uploads the build it already retained, or
repeats the retry it was, and never rebuilds a number it can still upload. Once a later run
has moved on, a rerun is refused with the retry command.

Numbers are never reused: the next attempt is one past every number seen in receipts, Apple
builds, Apple uploads and retained artifacts, whatever version each was built for. An upload
stops if Apple already has its number under another version. A failed verification takes no number, so a
later `deliver` runs it again. After Apple's upload-limit error (90382) no upload starts for
a day.

## Sweeps

A sweep runs at the end of every delivery run and on the caller's maintenance schedule
(hourly in the quickstart) in **Delivery maintenance**, which also runs one on demand with
`sweep`. Release decisions never wait for a sweep: App Store release reads Apple, receipts
and holds itself. A sweep does four things:
- writes missing receipts for builds testers can install, however old,
- reports the branch tip and App Store state,
- updates incidents,
- never changes Apple.

Its cost does not grow with history: 11 calls today, and the same with 500 builds, because
builds with receipts cost nothing. To check that every existing receipt is on the right
commit, dispatch **Delivery maintenance** with `audit`.

Every **TestFlight delivery** run is a delivery attempt; sweeps never appear there. Push
runs are titled with the commit message, dispatches with the operation and SHA. To find a
problem, start from its `ios-delivery` incident or filter the runs to failures.

Incidents are issues labelled `ios-delivery`, found by an owner marker in the body. A sweep
opens one per problem, updates its status block (people's notes are kept), and closes it
when the problem is gone, as superseded when the branch moved past that commit. Closed incidents
are never read or reopened: a problem that comes back gets a new issue. To see what keeps
coming back, count recent incidents by title:

```sh
gh issue list --label ios-delivery --state all --limit 500 --search "created:>=$(date -v-90d +%F)" --json title --jq '.[].title' | sort | uniq -c | sort -rn
```

## Red and green

- **Green:** the run did what it should. Pending operator actions stay green; they appear
  as `::warning::` annotations, in the step summary and in the incident issue.
- **Red:** an error in this run: a failed delivery step, a refused `deliver`/`retry`, or an
  infrastructure or repair error.

## Evidence

Each run uploads `ios-delivery-<run>-<attempt>` (verification reports and `build/delivery/`,
including `run-summary.json`) for 90 days. It is uploaded before the final sweep, because a
cancelled job loses its token within minutes. Each build's IPA, dSYMs and manifest are
retained as `ios-build-<number>` and read back with checksum validation before any upload.
TestFlight "What to Test" notes end with `Commit <sha>`, so testers and sweeps can tell
which commit a build came from.

Actions deletes those after 90 days, so before a build is submitted to the App Store its
dSYMs and manifest are attached to a Release on its `deploy/testflight/…` receipt tag, where
they stay. The IPA stays with Apple. A build whose retained package has expired is not
submitted.

## Receipts

An annotated tag `deploy/testflight/<version>/<build>` on a commit means that commit's
build is available in TestFlight. It is written only after Apple reports the build
`IN_BETA_TESTING` for the internal group. If writing it fails, the delivery still counts
as successful and the next sweep repairs it. A repaired receipt holds the Apple facts
only; an existing tag counts as the same receipt when its commit, version, number and Apple
build ID match. `submit/appstore/…` is written after Apple accepts a submission, and
`deploy/appstore/…` after Apple reports the release. Receipts are never moved or deleted.
Any other tag under `deploy/testflight/` was made by hand: every delivery and sweep stops
with an error naming it until it is deleted (`git push origin :refs/tags/<tag>`).

Receipts are written with `IOS_RECEIPT_TOKEN`, a secret holding a fine-grained
token with Contents and Workflows write access. GitHub refuses tags on commits that change
`.github/workflows/` unless the token has Workflows permission, which `GITHUB_TOKEN`
cannot have. The token's expiry is shown to its owner under **Settings → Developer
settings → Personal access tokens → Fine-grained tokens**, and GitHub emails the owner
before it expires. When it lapses, receipt writes fail with `HTTP 401`. Deliveries still
reach TestFlight, but sweeps go red with a repair error and a `record-repair` incident
until the secret is replaced (`gh secret set IOS_RECEIPT_TOKEN`); the next sweep then
writes the missing tags.

## App Store release

**App Store release** decides from Apple and receipt tags only. The caller's schedule
sets the release cadence; the examples use Wednesday at 07:00 New York. Each scheduled
run submits the newest TestFlight build that passes every eligibility rule:

1. has a `deploy/testflight` receipt,
2. is not expired or invalid in Apple,
3. is newer (higher commit count) than the last App Store release,
4. has a marketing version not yet on the App Store.

If no build qualifies, the run skips quietly. A scheduled run delayed by the queue
promotes when it runs, even if it starts on a later day. No release starts while an earlier one is unresolved (waiting for review, in review, pending release
or an unknown state). An editable App Store version with a build attached means an
earlier submission failed or its outcome is uncertain: scheduled runs never resubmit it.

Dispatch it on the delivery branch to act now:

```sh
gh workflow run app-store-release.yml -f operation=promote -f reason="<why now>" -f rollout=phased
gh workflow run app-store-rollout.yml -f operation=pause -f reason="<why>"
gh workflow run app-store-rollout.yml -f operation=resume -f reason="<why>"
gh workflow run app-store-rollout.yml -f operation=release-to-all -f reason="<why>"
```

- `promote` works on any day with the same eligibility rules and needs a
  `reason`. A release earlier the same day or week does not block it. If the previous
  version is still waiting for review, it stops and asks you to remove that submission from
  review in App Store Connect first. It is also the only way to retry a failed submission.
  After App Review rejects one, Apple keeps its review submission open (Unresolved Issues):
  cancel it in App Store Connect first, or `promote` stops on it.
  A submission that was created but never sent (a cut-off run leaves it `READY_FOR_REVIEW`)
  also stops every release, because Apple allows one open submission; the sweep names it.
  Submit or cancel it in App Store Connect, then dispatch `promote`.
  Critical fixes can get an expedited review from Apple; request it yourself in App Store
  Connect.
- Every release requests Apple's 7-day phased rollout (1%, 2%, 5%, 10%, 20%, 50%, 100% of
  people with automatic updates). Use `rollout=all` for a fix that must reach everyone now.
- `pause`, `resume` and `release-to-all` each need a `reason` and make at most one change
  to the current rollout; repeating one already in effect changes nothing. They run in their
  own job on a GitHub-hosted runner with its own queue, so a pause starts within a minute or
  two even while a beta delivery holds the run lock or the self-hosted runner. They read only
  App Store Connect. **Pausing does not stop new downloads:** anyone who installs or updates manually
  still gets the newest version. Apple allows 30 pause days in total.
- A release reuses only a draft the pipeline left behind (its version belongs to a receipted
  TestFlight build), and copies its metadata from the last released version. The first App
  Store release is made by hand; until one exists, releases skip or stop. A rejection or
  stuck review submission before then is fixed and resubmitted in App Store Connect, and
  the sweep's incident says so instead of pointing at `promote`.
  Any other editable App Store version is someone's work: releases stop until it is deleted
  or shipped by hand.
- A build keeps its own marketing version. Once Apple has a version submitted, new beta
  builds move to the next one.

Sweeps report App Store state in the run summary (review state, rollout day, percentage,
paused or not, pause days used) and repair App Store receipts, but never change Apple.
`IOS_APPSTORE_ENABLED` gates every App Store change. Dispatch **Delivery maintenance** with
`app-store-preflight` for a read-only report of what the next release would do.

### Holding a bad TestFlight build

When testers find a showstopper, open a **Hold a TestFlight build** issue (the
`testflight-hold` issue form, from `examples/ISSUE_TEMPLATE/`) with the build, for example `26.09.02/405.1` or, as TestFlight
shows it, `26.09.02 (405.1)`, and the reason.
While it is open, no App Store release includes that build or any later build. An older build
that is still newer than the last release may ship instead.

- Close it with the fixing commit (`Fixes #<n>`) to release builds that contain the fix;
  builds between the held one and the fix stay blocked.
- Close it by hand if it was a false alarm. A close by a bot without a fix, or one GitHub
  shows no closer for, never lifts a hold.
- Holds do not expire with TestFlight builds. Old holds still protect fresh descendants
  and retries of commits that lack the fix.
- A hold whose build cannot be found blocks every App Store release until you fix or close it.
  If GitHub cannot be read while holds are checked, the run fails instead of guessing.
- If the held build is already waiting for review or rolling out, the sweep asks you to
  remove it from review or dispatch `pause`. Nothing changes Apple automatically.
- Expiring the build in App Store Connect also keeps it out, but only that build, and it
  cannot be undone.

Exclude holds from stale-issue automation. A bot closing an issue without a fixing commit
does not lift the hold; App Store preflight links these closed holds and explains how to
resolve them. For `actions/stale`, add this setting to your pinned action step:

```yaml
- uses: actions/stale@<sha>
  with:
    exempt-issue-labels: testflight-hold
```

See [DESIGN-holds.md](DESIGN-holds.md) for the general hold design.

### Enabling App Store release

1. Let any version already in review in App Store Connect finish, or remove it from review.
   The enabled maintenance sweep creates missing `testflight-hold` and `ios-delivery` labels.
2. Dispatch **Delivery maintenance** with `app-store-preflight`. Check the build it would
   select, the first rule each of the three newest builds fails, whether its dSYMs can be
   kept, and the metadata source validation (the last released version).
3. Check that `IOS_RECEIPT_TOKEN` will not expire soon.
4. `gh variable set IOS_APPSTORE_ENABLED --body true` and `gh workflow enable app-store-release.yml`.
5. First controlled release:
   `gh workflow run app-store-release.yml -f operation=promote -f reason="First pipeline release" -f rollout=phased`.
   Expect `submitted <version>/<build> (phased)`, the version waiting for review with a
   phased release in App Store Connect, and a `submit/appstore/…` tag.
6. After Apple releases it, the sweep reports `phased rollout active, day 1 (1%)` and writes
   `deploy/appstore/…`. Dispatch `pause`, confirm the sweep reports it paused, then
   dispatch `resume`.
7. To turn it off again: `gh variable set IOS_APPSTORE_ENABLED --body false` and
   `gh workflow disable app-store-release.yml`.

## Runner, signing and credentials

Jobs run on the `runs-on` labels, except rollout controls, which run on `ubuntu-latest`
with only the App Store Connect key. Signing secrets reach only the archive step and the
signing diagnostic; App Store Connect secrets reach only Apple-facing steps; verification
subprocesses get an allowlisted environment; checkout never persists credentials; every
action is pinned to a full SHA. `./gradlew check` tests these properties, and each composite validates the actual caller
job before any delivery phase runs.

Signing sessions for the same macOS user serialize through
`~/.mobile-delivery/signing.lock`, from reading the original keychain/profile state through
archive, export and restoration. Keep this lock file in place between runs. All runner
instances sharing that user must use this locking version; older engines and unrelated
signing tools do not participate. Use separate macOS users while migrating older runners.
The operating system releases the lock if a process exits, but forced termination can still
leave temporary signing state requiring operator cleanup.

Passwords are sent to `security -i` through its standard input, never process arguments
or command logs. Passwords and signing paths must be single-line and NUL-free, and each
quoted command must fit macOS's 4,096-byte interactive input buffer; unsupported input
is rejected before execution. Temporary keychain passwords use random UUIDs.

To diagnose signing, dispatch **Delivery maintenance** with `verify-signing`. It validates
the profile and the P12 in a temporary keychain, prints only fingerprints and status codes,
and never builds, uploads or calls GitHub or Apple.

`mobile-delivery bootstrap --asc-config <file> --p12 <file> --p12-password-file <file>
--profile <file>`, run from the app's checkout, sets the
environment secrets from local files. It runs only when invoked deliberately. Build the
command with `./gradlew installDist`; it is `build/install/mobile-delivery/bin/mobile-delivery`.
Keep app build values as individual secrets and bind their declared names
on the TestFlight step; no composite secret is required.

## Developing the tool

```sh
./gradlew check        # tests, Detekt, ktfmt, and caller/workflow safety checks
./gradlew format       # apply ktfmt
bash internal/actionlint.sh # checksum-verified pinned actionlint, including quickstart callers
```

actionlint 1.7.12 does not yet know `concurrency.queue` or `job.workflow_sha` and
`job.workflow_repository`. The [known rough edges](docs/rough-edges.md) link the
workflow-context and queue acceptance records. Keep these ignores scoped to the unsupported
fields; other workflow errors must still fail lint. Each app keeps its live adoption
evidence in its own repository.

`check` runs in `check.yml` on every push and pull request. Verification itself
(`mobile-delivery verify`) needs the app's pinned Xcode, so it runs only on the app's runner.

Each app's own source checks, native UI harness and pre-push review stay in that app;
reference them from `verification.commands`.
