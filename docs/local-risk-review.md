# Local pre-push risk review

Use a pinned copy of this repository, Node 22+, Git and one authenticated provider CLI.
The tested interfaces are Codex CLI 0.166.0 and Claude Code 2.1.325. Install/authenticate
from the provider's official distribution. Codex uses its existing CLI login or a selected
API key. Claude uses bare API mode with `ANTHROPIC_API_KEY`. Configure a currently supported
model explicitly; the shared tool never hardcodes a model or runs both providers by default.
See [Codex noninteractive usage](https://learn.chatgpt.com/docs/non-interactive-mode) and
[Claude CLI reference](https://code.claude.com/docs/en/cli-reference).

From the app checkout:

```sh
node /absolute/pinned/mobile-delivery/review/cli.mjs install --provider=codex --model=CURRENT_MODEL
# or use --provider=claude with its configured API model
```

The installer saves provider/model locally and composes the current pre-push hook through
a tool-owned Git hooks directory. Existing Beads/deterministic hooks receive the same
arguments and complete stdin first; a failure there still blocks. Other existing hooks
are forwarded. Install again to update the trusted script path without adding another
review invocation. Reinstall/uninstall refuse to overwrite human changes to wrappers or
a newer hooks-path configuration. Restore with:

```sh
node /absolute/pinned/mobile-delivery/review/cli.mjs uninstall
```

Keep the pinned tool checkout available while installed. If replacing an older AI hook,
remove its paid-review stage while preserving its deterministic/Beads behavior before
composing this hook. One shared provider session covers every ref in each push; a cache
avoids paying again for an identical reviewed range. This tool introduces no PR requirement
or blanket restriction on feature branches.

Git supplies the exact local ref/object and remote ref/object for every update. Review
uses those committed objects, never dirty files or an assumed HEAD. Fast-forward pushes
include all added commits and the combined diff. Initial branches include full reachable
history. Deletions explicitly remove a ref. Rewrites include added and removed commits.
Individual committed patches remain included when the final combined tree reverts them.
Shallow clones must be unshallowed or explicitly accepted as incomplete.
Annotated tags resolve to their committed source; non-commit objects require explicit
acceptance of an incomplete review. Missing remote bases are fetched by exact object ID
without hooks or tag/branch guessing. If unavailable, review remains incomplete. Large
ranges, unavailable binary/submodule context and timeouts never silently omit work or
produce a clean result. Split the push, restore context/provider access, or accept its risk.

Both adapters use the same strict JSON schema and risk rubric. Clean, advisory, blocked
and incomplete are explicit outcomes. Priority P0–P3 describes urgency. The local blocking
decision separately addresses unresolved destructive data/schema changes, irreversible
external effects, incompatible storage/API/protocol changes, credentials/access/signing,
production routing, and broad shared behavior with uncertain containment or recovery.
A blocking finding must identify affected surface, reversibility, mitigation and reason.
Contained reversible changes and routine findings remain advisory, regardless of a blanket
priority threshold. Findings must anchor to an exact committed source location.

The reviewer runs in an isolated temporary directory. Its only source tools list/read
immutable Git blobs from the allowed commits. They cannot read the working tree, home
credentials, symlinks or arbitrary commits, execute repository code, fetch URLs or write
fixes. Codex runs read-only with shell/hooks/apps/plugins/browser/subagent tools disabled;
Claude uses bare restricted mode with no built-in tools and only the explicit context MCP
server. Source/prompt-injection text and repository instructions remain untrusted data.
GitHub, Apple, signing and unrelated provider credentials are removed from the reviewer
environment. Only the selected provider's authentication is retained. Temporary results
and provider output disappear after invocation; no transcript archive is kept.

If blocked or incomplete, the hook prints its exact review key. After assessing that
specific risk, the human can record a meaningful reason and retry the same push:

```sh
node /absolute/pinned/mobile-delivery/review/cli.mjs accept EXACT_REVIEW_KEY "Reason this precise risk is accepted"
```

The key binds every outgoing ref/object, remote identity, provider, model, CLI version
and the schema/prompt/implementation digest. Changed content, ref/range, provider/model,
version or rubric invalidates acceptance and cache. There is no global skip switch.
Only minimal local metadata is retained in the Git common directory: decision, finding
titles/locations/risk reasons, exact refs, key, timestamps and acceptance reason. Do not
put credentials in acceptance reasons. Provider/auth/timeout/malformed-output failures
remain incomplete and actionable. Cached incomplete results are retried unless accepted.

`./gradlew check` runs fake-provider, actual Git-range, MCP framing and hook-composition
fixtures. Tests make no inference calls and do not use live provider/GitHub/Apple credentials.
Hosted review reuses the contract but publishes asynchronous issues; its findings and
operation failures must never gate CI or automatic/manual deployment.
