# mobile-delivery

Shared GitHub Actions for verifying iOS apps, delivering signed builds to TestFlight,
and managing App Store releases. Each app owns its runners, credentials and workflows.

## Quick start

1. Review and copy the appropriate `.github` overlay into the app repository.
   Use [quickstart](examples/quickstart/.github) for marked templates, or start with
   the complete [Xcode](examples/xcode/.github) or [Kotlin Multiplatform](examples/kmp/.github)
   examples. The [legacy overlay](examples/legacy/.github) is for existing all-in-one callers.
   Merge existing workflows and Dependabot rules you still need.
2. Replace the app identifiers, baseline commit, branch and runner choices, Xcode
   and simulator names, test commands and report paths. Replace each entire marked
   JSON string, including its `# CHANGE ME` suffix. For the marked KMP template,
   use `delivery.kmp.example.json` as `delivery.json`. Retain the app's existing
   verification, build environment and binary checks. The examples are the reference
   for caller inputs, permissions and job wiring.
3. In the app repository, create the `ios-delivery` environment and set both repository
   switches to `false`. Bind any existing values declared in `build_environment`
   on the steps that run the app's verification or build.

   ```sh
   gh api --method PUT 'repos/{owner}/{repo}/environments/ios-delivery'
   gh variable set IOS_DELIVERY_ENABLED --body false
   gh variable set IOS_APPSTORE_ENABLED --body false
   ```

4. Download the [release CLI](https://github.com/thinkfishhook/mobile-delivery/releases)
   compatible with the example's action pins. Verify the zip against its SHA-256
   checksum, unzip it, and use JDK 21 to run
   `/path/to/mobile-delivery/bin/mobile-delivery callerChecks` from the app root.
   Keep every shared action at the same full release SHA and review grouped upgrades
   against the [changelog](CHANGELOG.md). Follow the [PR workflow](docs/pr-workflow.md)
   to verify the change with delivery disabled before enabling uploads.

Receipt tags pushed by the tool can trigger Actions. Review every existing push
workflow. Use branch filters, or exclude both `deploy/**` and `submit/**` with
`tags-ignore` for workflows that run on pushes to all branches. Keep verification
on normal PRs and integration pushes to avoid duplicate Dependabot runs.

The build runner needs Git, GitHub CLI, Python 3, and the configured Xcode version
and simulators. For self-hosted runners, grant the app access to its runner group.
On shared Macs, avoid changing machine-wide Xcode selection from jobs. Keep
`~/.mobile-delivery/signing.lock` in place and use separate macOS users for tools
that do not participate in that lock.

## Enable TestFlight

In App Store Connect, configure an internal testing group and an API key. Export
an Apple distribution certificate as a password-protected P12 and download the
App Store provisioning profile. From the app checkout, put the credentials in
`ios-delivery`. Keep credential values out of committed files and command arguments.

```sh
gh secret set ASC_KEY_ID --env ios-delivery
gh secret set ASC_ISSUER_ID --env ios-delivery
gh secret set ASC_PRIVATE_KEY --env ios-delivery < AuthKey.p8
base64 < distribution.p12 | tr -d '\n' | gh secret set IOS_DISTRIBUTION_P12_BASE64 --env ios-delivery
gh secret set IOS_DISTRIBUTION_P12_PASSWORD --env ios-delivery
base64 < App.mobileprovision | tr -d '\n' | gh secret set IOS_PROFILE_BASE64 --env ios-delivery
gh secret set IOS_RECEIPT_TOKEN --env ios-delivery
```

Scope the receipt token to the app repository with Contents and Workflows read/write
permissions, and record its expiry. GitHub requires Workflows permission when a receipt
points to a commit that changes workflow files.

Dispatch `verify-signing` in the maintenance workflow and wait for it to pass. Then
set `IOS_DELIVERY_ENABLED=true` for the next normal integration push. Confirm the
automatic deployment used the package from that CI run and the configured internal
group can install the build. A `deploy/testflight/…` receipt identifies the delivered
commit. Keep the App Store switch off until public release is ready.

Apps that use commit-count build numbers can set `"build_numbering": "commit_count"` in
`.github/delivery.json`. CI reserves `<source commit count>.<attempt>` for the verified
commit. The same ledger allocates manual builds from the three-component sequence and
retains earlier package identities for reruns. Update all shared action pins to v1.2.0
or newer before enabling this setting.

For an interrupted or uncertain upload, rerun the original deployment to reconcile
Apple's state and reuse the existing build. A fresh manual dispatch starts a new build
from the selected branch. If a receipt write failed after delivery, replace an expired
receipt token and rerun deployment or let maintenance repair the tag. Preserve
tool-generated receipt tags and preparation refs. Legacy callers can use the retry
command printed in their run summary.

## Enable the App Store

Ship the app's first release manually in App Store Connect so automation has approved
metadata to reuse. Resolve unfinished drafts or review submissions and dispatch
`app-store-preflight` in the maintenance workflow. Review its blockers, the caller's
release schedule, the receipt token's validity and the candidate's retained symbols.
Then set `IOS_APPSTORE_ENABLED=true` to allow scheduled submission.

Use `promote` in the App Store release workflow for a deliberate submission or retry.
If Apple still has an open review submission, resolve it in App Store Connect before
retrying. The rollout workflow can pause, resume or release a phased rollout to
everyone. A pause affects automatic updates. Users can still download the current
version manually. Check the `submit/appstore/…` receipt after submission and the
`deploy/appstore/…` receipt after release and maintenance.

To block a bad build from public release, file a **Hold a TestFlight build** issue
using the [hold form](examples/ISSUE_TEMPLATE/testflight-hold.yml). Create the
`testflight-hold` label if maintenance has yet to do so, and exempt it from stale
automation. Supply the exact version and build number. Close the hold with the
fixing commit (`Fixes #<number>`) to allow builds containing the fix, or close it
manually for a false alarm. If the build is already in review or rolling out,
remove it from review in App Store Connect or dispatch a rollout pause.

## More

- [Build a signed IPA locally with an existing keychain identity](docs/local-archive.md)
- [Upgrade from all-in-one delivery](docs/upgrade-v1.1.md)
- [Install the local pre-push review hook](docs/local-risk-review.md)
- [Configure optional API-based landed-push review](docs/hosted-review.md)
- [Contribute or publish a release](CONTRIBUTING.md)
- [Report a vulnerability](SECURITY.md)
