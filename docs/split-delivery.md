# CI preparation and standalone TestFlight

The additive `ci` and `testflight-deploy` actions share the existing verification,
signing, package validation, upload and Apple reconciliation implementations. Existing
`verify` and all-in-one `testflight` callers remain supported during migration.

Name the push workflow **Continuous Integration**. Filter `push.branches` to the app's
existing integration branch (`main` or `trunk`); receipt tags must not trigger it. Pass
that branch and the CI workflow filename to both actions. Use the existing `ios-delivery`
job concurrency group with `queue: max` and `cancel-in-progress: false` across preparation,
deployment and maintenance. An obsolete CI run cancels itself before verification. Do
not use generic concurrency cancellation that could interrupt an upload.

`ci` freezes the push SHA, runs every configured deterministic command/report gate,
then signs/exports exactly that source. Verification receives no signing, GitHub or Apple
credentials. A local verification receipt binds the SHA, run, attempt and configuration;
it is invalidated before every verification attempt. Failed checks cannot prepare a package.
Preparation receives signing credentials plus the Apple metadata credential needed to
select a marketing version and inventory occupied build identities. It never uploads.
The sealed IPA, symbols and manifest are retained for 14 days. Action outputs identify
source, artifact name/ID, package digest, version/build and actual producing run/attempt.

Name the second workflow **TestFlight Deploy**. Configure `workflow_run` for completed
Continuous Integration runs and `workflow_dispatch` with no artifact-selection inputs.
At the job boundary, run for manual dispatch or successful upstream CI. Do not add a
main-only `github.ref` guard, `needs: ci`, or a review prerequisite. Grant this action
`contents: write`, `actions: read`, `issues: write`; CI additionally needs `actions: write`
for supersession cancellation. Keep credentials in named action inputs, outside global
or job environments. Keep AI review in its own workflow.

Automatic deployment checks the authoritative upstream workflow ID/path, repository,
branch, event, conclusion, SHA, run and attempt. It resolves that run's sealed reservation,
checks the package bytes and app/signing identity, and uploads without verification or
archive. It never searches for the latest success. Recheck the integration head after queue
admission and immediately before a new upload; a newer pending, failing or cancelled
commit prevents an older successful package from starting an upload. Once Apple has
accepted the build, finish processing/distribution recovery even if the branch advances
or the artifact expires. Uncertain uploads require reconciliation; do not allocate a
replacement identity just because a response was lost.

Manual dispatch uses GitHub's branch selector and freezes the dispatch SHA. It signs,
exports, retains and uploads that commit without CI checks or any review requirement.
Integration-branch movement does not invalidate this explicit choice. The existing pause
switch applies to both paths. A manual-origin receipt remains ineligible for automatic
App Store selection. App Store release switches and schedules remain separate.

Reruns reuse the first reservation and any sealed package. If an earlier attempt reserved
a number but stopped before sealing, the successful attempt records its own `/attempts/N`
URL in the manifest. Reservation provenance still identifies the original allocation.
Previously sealed packages with run-only URLs remain readable. A missing, expired or
corrupt sealed package fails explicitly; automatic deployment never rebuilds it. A new
human dispatch creates a fresh operation/number. Receipt-write failures can be repaired
by rerunning deployment against Apple's existing build, with no duplicate archive/upload.

The action summaries distinguish CI/manual origin, selected SHA/ref, qualifying run and
attempt, actual package-producing URL, reserved identity, digest, and confirmed internal
TestFlight availability. Fixture results establish the contract; consumer migration still
requires a real automatic CI-to-same-package delivery. Live manual evidence stays pending
until naturally exercised. Failure reporting and local/hosted review are separate entry
points and must not become dependencies of upload.

Schema-1 packages and legacy receipts remain readable. After schema-2 reservations begin,
roll back only to a release with compatible reservation/receipt readers. Preserve the
ledger and receipt tags; never reset numbers, move release tags, or rewrite retained bytes.
