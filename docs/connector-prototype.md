# Connector transport prototype

The owner selected Connector-only hosted review, with a managed model and bot-created
review-only PRs after direct integration pushes. The manually requested Connector already
reviewed a real shared-tool PR, published a P2 finding, then completed a corrected-head
review. This prototype checks the next boundary: an Actions-authored PR and request.

`Connector review prototype` is opt-in `workflow_dispatch` in the shared tool repository.
Supply an exact source commit and its authoritative first parent. The driver reads those
identities from GitHub, creates immutable disposable `codex/review/.../base` and `head`
refs, opens a review-only PR between them, and comments `@codex review` as the workflow
bot. The PR never targets main/trunk. Existing refs/PRs/triggers are reused; moved refs,
closed human decisions and ambiguous identities fail without force updates or reopening.
No repository source from the requested commits executes in the workflow. It receives
only its scoped GitHub token, no OpenAI key or app/signing credentials. Model identity is
reported as Connector-managed/not provided; do not substitute the local hook model.

A successful workflow means only that the request was created. Inspect the trusted
Connector's completion and exact reviewed head before claiming inference success or a
clean review. No response, timeout, malformed state or mismatched head is incomplete.
This first prototype accepts one exact parent/commit pair; it does not yet claim full
multi-commit/initial/rewrite coverage or issue-publication acceptance.

The next integration must cover every outgoing commit, including intermediate reversions,
with explicit bounds/unsupported states, then normalize anchored P0–P3 native findings
through the existing deduplicating publisher. Preserve human triage and closed decisions;
provider/publication failures remain visible and never hold CI or deployment. Direct
main/trunk development remains the app workflow; review PRs are an internal transport.

## Native evidence boundary

The Actions trial created review-only PR #26, but its bot-authored mention received an
account-link prompt rather than native inference. Human-authored requests have completed
and produced useful P2 findings. Repository automatic-review authentication remains under
investigation; request creation is not acceptance of this mode.

`review/connector-evidence.mjs` reads the observed native v1 summary and finding markers.
It requires the known Connector bot identity, exact immutable PR head/base, completed
exact-head metadata, matching standard review receipts for findings, and complete bounded
comment inventories. It retains P0–P3 priorities, native text and LEFT/RIGHT source identity;
unknown formats, source movement, absent findings or contradictory state fail incomplete.
It checks source/state again after collecting comments. The format is observed behavior,
not a guaranteed public API. This reader alone does not publish issues or establish full
landed-range coverage, automatic authentication or consumer acceptance.

## Landed-range planning

`review/connector-plan.mjs` plans fast-forward integration pushes before any GitHub write.
It includes the combined landed diff and every added commit's first-parent diff, including
merged side-branch commits and changes reverted later in the push. Identical views are
requested once; tree-identical views are recorded explicitly as unchanged. A single-commit
push therefore needs only one PR. The full existing immutable Git context bounds still
apply, and more than 32 changed views is explicitly incomplete before request creation.
Initial, deleted, rewritten, shallow or unavailable histories fail visibly; no subset is
quietly accepted. The request prototype still accepts only one parent/commit pair until
this planner and an aggregate-range transport are integrated and validated.

## Native issue publication adapter

`review/connector-publish.mjs` preserves native finding text/priority and reads each exact
LEFT/RIGHT committed line before any issue write. It passes those anchors through the
existing deduplicating publisher with provider `connector`, model `connector-managed`,
and links to both the native PR comment and publication run. It does not invent separate
impact, correction or risk classifications from the native prose. Existing owned issue
blocks, urgent P0 handling, human edits and intentionally closed decisions are preserved.
Unknown priorities, mismatched source/model, unavailable anchors or evidence exceeding the
shared schema bound fail incomplete before publication. This adapter is fixture-tested;
no live automatic publication or consumer acceptance is inferred until the workflow is
integrated and a trusted bot-PR review completes.
