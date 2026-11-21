# Asynchronous landed-push review

Use a separate workflow named **AI Code Review**, filtered to the existing integration
branch. It never participates in Continuous Integration's aggregate conclusion and has
no `needs` relationship with CI or TestFlight. Its own failed result is visible without
changing either workflow's outcome. For an app using `trunk` and Codex API authentication:

```yaml
name: AI Code Review
on:
  push: { branches: [trunk] }
jobs:
  review:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    permissions: { contents: read, issues: write }
    concurrency: { group: mobile-delivery-ai-review, queue: max, cancel-in-progress: false }
    steps:
      - uses: thinkfishhook/mobile-delivery/ai-review@FULL_RELEASE_SHA
        with:
          branch: trunk
          provider: codex
          model: ${{ vars.AI_REVIEW_MODEL }}
          provider-api-key: ${{ secrets.OPENAI_API_KEY }}
```

Use the compatible stable full SHA shared by the other actions. Configure an explicit
supported model and repository API secret. Organization GitHub App installation provides
repository integration; it does not substitute for this workflow's provider authentication.
To select Claude, change `provider` and use `secrets.ANTHROPIC_API_KEY` with its API model.
Only the selected provider receives a key. Do not put app/signing/Apple/provider credentials
in global or job environments. Hosted policy requires a fresh GitHub Ubuntu job; the
official Codex action drops sudo and must not change a reused app build runner's account.
Application CI and delivery keep their own Mac runners.

The action freezes the push event's exact before/after SHA and branch, checks repository
identity, and reads those committed objects. It includes all intermediate committed patches,
initial history and rewrite removals. Missing bases are fetched by exact identity; missing
objects, shallow/oversized history, insufficient binary/submodule context and malformed or
incomplete reviews remain visible operation failures. It never reviews latest trunk by
execution time or silently truncates a push. Reruns use the same source range.

The shared [schema/risk rubric](local-risk-review.md) and immutable read-only context service
apply to both providers. Codex runs through the pinned official action's API proxy with a
fresh trusted home/config, read-only permissions, disabled shell/image/browser/plugin/hook
and subagent tools, and only the trusted context server. Claude uses the pinned bare,
restricted noninteractive interface with no built-in tools and explicit context access.
Repository content cannot authorize commands, writes or exfiltration. No generated fixes
run. Provider credentials are absent from source setup and issue publication. GitHub issue
access is scoped to source retrieval and the publisher. Temporary provider results are
deleted after publication; no raw transcript/log artifact is retained.

Actionable P1–P3 findings create/update issues with priority, exact source file/commit/line,
failure scenario, impact, correction, provider/model and review-run/attempt link. Under the
owner's P0 decision, a P0 creates a clearly marked **URGENT** issue. Its severity is preserved.
No finding, including P0, creates a delivery hold or approval requirement.

Fingerprints use the repository path and normalized actual committed source line, rather
than model wording, priority, line number or provider. This deduplicates stable anchors
across providers and line movement. Multiple observations of one anchor share its issue;
the status block retains bounded evidence and the most urgent recorded priority. The
serialized AI queue prevents competing publishers from creating duplicates, and a lost
create response is read back by owned marker before retry. Preserve this shared queue;
do not use per-run groups or cancel queued review publications.

Human prose, comments, assignments, titles and triage labels survive. Deliberately closed
issues stay closed; matching findings are listed as suppressed decisions for inspection.
A later empty review never automatically closes an issue or proves a fix. AI markers and
labels are separate from CI/deployment incidents and owner repair alerts.

Provider/auth/timeout/schema/publication errors produce an explicit failed/incomplete AI
summary. An outage never becomes a fabricated code defect or clean review. Partial issue
publication states how much completed and can be rerun safely. Repair access/service and
rerun that exact review job. A whole-job timeout is also visible in Actions even if its
final summary cannot run. CI, package preparation and automatic/manual deployment continue
under their own deterministic rules.

Fixture validation covers urgent P0, P1–P3, source selection, shared anchors/provider changes,
human resolution, redaction and partial publication. A real phase-entry fixture prepares
the isolated provider configuration and exposes a provider failure without changing source.
All tests use fake inference and fake issue APIs. Configuration/fixture results must remain
distinct from actual consumer live review and delivery evidence.
