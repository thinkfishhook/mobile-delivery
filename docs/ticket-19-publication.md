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
