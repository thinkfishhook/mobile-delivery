# Ticket 19: v1.0.1 publication

## Owner authorization

Received in the Mission conversation on 2026-11-03; recorded before any push or tag:

> I authorize publishing the prepared docs-only v1.0.1 release, including its trunk push and tag. I assume checks will pass.

Authorization covers publication; checks must actually pass before tagging.
The previous ticket 18 window authorization is not used for this release.

## Candidate scope

Prepared docs-only milestone `30413380fae0fb905cbfbf2d48629a554712d60b` adds
the portfolio acceptance guide, links it from CONTRIBUTING, records initial
evidence and adds the 1.0.1 changelog section. Its parent
`f6968e95ff58f08ae877fa24b48a0855c4ded1ea` is the final ticket 18 evidence-only
commit, previously local. Public trunk before publication is
`b31a7c5296ac2876ffe5be3db2b8f498ba2cff79`. All pending changes are documentation;
no engine, composite, workflow or app changes are included.

Dated `[1.0.1]` as 2026-11-03. Local checks and exact-candidate hosted Check,
Engine self-test and Composite self-test are required before creating the
annotated version tag. The release workflow owns publication and moving `v1`.

## Acceptance boundary

Publishing v1.0.1 does not complete ticket 19. Public issue #1 remains open for
scheduled sweeps, all-app Dependabot success and grouped upgrade/CI proof, live
failure and recovery evidence, full workflow execution coverage, comparable queue
measurements, and seven complete days of billing evidence. No app PR is merged,
no event is manufactured and no archive setting is changed as part of this
publication authorization. The private rollback archive remains available.

## Local validation before publication

At approximately 22:37 UTC: `./gradlew --no-daemon check installDist` PASS on
Zulu JDK 21.0.11; documentation-only recheck reused 389 existing passing test
results (zero failures/errors/skips). Formatting, Detekt and workflow policy
checks PASS. Pinned actionlint PASS with downloaded archive checksum verified.
Release helper tests 5 PASS; drain helper tests 9 PASS. Stable v1.0.1 release
notes parsed successfully from the dated changelog. Whitespace checks PASS.

## Publication and verification

- Dated candidate and authorization commit:
  `fdb1e7f9c7171d5bb8c9c50452d1eb7649b66950`, fast-forwarded public trunk from
  `b31a7c5296ac2876ffe5be3db2b8f498ba2cff79` under the owner's go-ahead.
- All required hosted workflows passed on that exact candidate before tagging:
  [Check](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41732438800),
  [Engine self-test](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41732438888),
  [Composite self-test](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41732438854).
- Annotated `v1.0.1` created at that SHA and pushed after all checks passed.
  [Release workflow](https://github.com/thinkfishhook/mobile-delivery/actions/runs/41732857478)
  completed SUCCESS. [Stable release](https://github.com/thinkfishhook/mobile-delivery/releases/tag/v1.0.1)
  published at 2026-11-03T22:42:14Z, not draft or prerelease, with exactly
  `mobile-delivery-v1.0.1.zip` and `mobile-delivery-v1.0.1.zip.sha256`.
- Downloaded both assets, checked ZIP CRC and all eight distribution files
  (including launcher and engine jar), and compared downloaded content with
  the checksum file. SHA-256:
  `1e873550188486abe661e82c1d8157be04e761fced629840284d3df09aead66c`.
  Downloaded CLI archive is byte-identical to the verified v1.0.0 CLI archive,
  consistent with the docs-only scope.
- GitHub Git refs/tag APIs confirm annotated `v1.0.1` peels to the exact
  candidate, and lightweight `v1` has advanced to the same SHA. Immutable
  `v1.0.0` still peels to `61b8c7c330c9b361dbf99be81d0e080f602d1bc5`.
- At 22:43:51 UTC, read-only open-PR queries across all three apps found
  **zero** mobile-delivery Dependabot upgrade PRs. The grouped-upgrade criterion
  remains pending normal Dependabot activity. No updater job was dispatched,
  no app branch changed, and no app PR was created or merged by this release.
- Public tracking issue [#1](https://github.com/thinkfishhook/mobile-delivery/issues/1)
  records publication separately from the still-pending grouped PR and other
  live acceptance items. It stays open; the private archive stays available.

Raw exact-candidate runs, release API data, downloaded assets, tag/checksum
verification and PR observations are retained in ignored `build/ticket-19/release/`.
This final publication evidence is committed locally for resumption, without
moving the published release or adding a further public-trunk push. Ticket 19
is not submitted as complete while the remaining live criteria are outstanding.
