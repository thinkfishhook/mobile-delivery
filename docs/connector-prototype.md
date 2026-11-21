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
