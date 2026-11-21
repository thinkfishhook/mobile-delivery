# Signed package identity

This is the shared foundation for [issue #6](https://github.com/thinkfishhook/mobile-delivery/issues/6).
The split CI preparation and standalone deployment entry points are separate work in #7/#8.
Current all-in-one actions continue generating schema-1 packages and legacy numbers; this
change does not dispatch a build, publish a release or migrate a consumer.

## Reserve, prepare, seal, retain, recover

`PreparationRequest` records the caller repository, bundle identifier, full selected source
SHA, branch ref, `CI_VERIFIED` or `MANUAL` origin, producing workflow path/run/attempt and
an explicit rebuild generation. Callers construct it from trusted event/verification facts,
not arbitrary artifact-manifest claims. `CI_VERIFIED` means the configured integration source
passed the required deterministic checks; a manual build cannot claim this origin.

The reservation key hashes repository, app, workflow, run ID and rebuild generation.
Attempts are excluded so reruns resume the original preparation. Source/ref/origin changes
under that key are rejected. Reruns preserve the originally allocated marketing version,
number and producing attempt, including across month/version changes. An intentional rebuild
increments the generation and receives a fresh number; it is distinct from upload recovery.

1. `PreparationReservations.reserve` atomically reserves an identity before signing/export.
2. `Preparation.build()` supplies that identity to the existing `Signing.archive` path.
   Verification and preparation must consume the same frozen source snapshot in #7.
3. `Signing.archive` validates the exported IPA and emits a schema-2 manifest when a
   preparation is supplied. Symbols are taken from that archive and hashed alongside the IPA,
   or the manifest records an explicit absence reason. Archives remain build intermediates.
4. `PreparationReservations.seal` records the exact manifest **before** artifact publication.
   Concurrent builders can seal identical results; different bytes/symbols under one identity
   are rejected. The first seal is immutable.
5. Retain `localPackage`'s IPA, optional symbols and manifest under `ios-build-<number>` in
   the producing run. The split workflow's bounded default is
   `PREPARED_PACKAGE_RETENTION_DAYS` (14 days). It must apply that retention when wired in #7.
6. `PreparedPackages.download` starts from a trusted sealed ledger record. It requires one
   unexpired artifact from the expected producing run, exact sealed provenance and hashes,
   then independently checks the actual IPA bundle/version/build, signature, entitlements
   and configured release binary policy using the existing signing validator.

The source binding is the external reservation's full SHA plus its allocated number and
sealed byte hashes, checked against the actual IPA's number. This is not an independent
compiler/source-reproducibility attestation: the preparation caller must actually build the
reserved source. A self-consistent manifest for another source or app is insufficient.

Before sealing, interrupted preparation may finish using the existing reservation. After
sealing, recover the identical retained package rather than rebuilding. If publication was
interrupted and the exact local bytes remain, publication can resume; otherwise a missing or
expired sealed artifact requires an explicit new generation. Failed preparations and expired
artifacts never release their numbers. No manual artifact-reference input is introduced.

## Repository state and numbering

`GitHubPreparationStore` keeps a versioned `reservations.json` on the data-only
`refs/heads/mobile-delivery-state` branch. This reuses the repository, with no additional
service or mutable deployment receipt. Each update creates a commit parented by the observed
ledger revision and advances the branch **without force**. Concurrent sibling updates lose
the fast-forward race and retry from the winner. Initialization races use create-ref
exclusivity. Lost write responses are read back before any retry; authentication/other failures
are not silently treated as successful reservations.

Keep this branch durable: do not delete, rewrite or treat it as an app integration branch.
It holds minimal identity/manifest metadata, not IPA bytes, archives, source copies or secrets.
The writer needs repository `contents: write` with the job token. It does not use receipt,
signing or Apple credentials. The allocation floor separately reads Apple build/upload/store
metadata, receipt names and the complete artifact inventory (expired entries included), so
those reads need their existing minimal credentials. Signing/upload secrets do not belong in
the ledger or reservation subprocess environment.

New numbers have three components `major.minor.patch`, with major 1–9999, minor 0–99 and
patch 1–99. The nonzero third component distinguishes them from all legacy `count.attempt`
numbers. Allocation is monotonic above both durable reservations and every occupied number;
it does not inspect branch commit counts. Apple-equivalent old forms and leading zeros are
normalized for the allocation floor. For example, a highest historical `234.1` yields
`234.1.1`, followed by `234.1.2` even when two branches have equal commit counts. After
`234.99.99`, the next number is `235.0.1`. Unrecognized occupied numbers or exhaustion stop
allocation rather than guessing. Git state serializes reservations; existing per-app queues
and signing locks remain responsible for build/upload execution.

The three-component format follows Apple's
[CFBundleVersion rules](https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleversion)
and stays within the conservative digit bounds in its
[Core Foundation key reference](https://developer.apple.com/library/archive/documentation/General/Reference/InfoPlistKeyReference/Articles/CoreFoundationKeys.html).
Git reference updates use the documented [GitHub Git references API](https://docs.github.com/en/rest/git/refs).

## Compatibility and public-release eligibility

- Schema 1 is still decoded/generated for existing callers, including historical receipt
  JSON with no preparation fields. Existing tag names/targets and package bytes are not rewritten.
- This is backward compatibility in the new engine. Previously released engines do not
  understand schema 2 or reserved numbers; after adopting them, rollback needs a compatible
  reader/recovery version. A prior legacy-only pin is not a schema-2 recovery mechanism.
- Schema 2 requires exact preparation provenance, canonical artifact name, positive sizes,
  SHA-256 digests and distinct safe filenames. Symbols or a nonblank absence reason are required.
- Receipts for reserved numbers carry the same preparation, preserving manual/verified origin
  through TestFlight and subsequent App Store receipts. Receipt collision checks also compare
  modern provenance. Legacy repaired receipts may still omit the old optional evidence fields.
- Legacy tracing continues to use commit counts only for two-component numbers. Reserved
  numbers are never inferred as commit counts. Sweep repair uses a sealed reservation for a
  reserved build, preserves its origin, and ignores an unrecognized foreign three-component
  build. Audits validate reserved receipt provenance rather than guessing a source commit.
- App Store selection excludes modern manual or unqualified receipts. Modern CI-qualified
  receipts are ordered by the full number; legacy release ordering retains its count-based
  behavior. This does not enable public-release switches. The old all-in-one upload entry
  point rejects schema-2 packages; #8 supplies their standalone deployment path.

## Verification

Tests use neutral ZIP/plist fixtures, an in-memory Apple/artifact service and a fake GitHub Git
API. They cover concurrent reservations/reruns, equal-count branches, explicit rebuilds,
interrupted preparation, lost responses, immutable sealing, expired/ambiguous/tampered
packages, wrong source/app/build, legacy decoding and origin-safe receipt recovery/selection.
No paid API, live Apple signing/upload or live reservation branch mutation is needed.

Run `./gradlew check installDist`, `bash internal/actionlint.sh` and the existing Python
release/drain helper tests. Fixture success does not establish a real signed consumer archive
or TestFlight delivery; those observations belong in the consumer migration issues.
