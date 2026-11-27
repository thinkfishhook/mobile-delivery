# Local pre-push risk review

Use Node 22+ and an authenticated provider CLI. From the app checkout, install
from a pinned tool checkout or the released CLI bundle's `share/review/cli.mjs`.
Keep that location available while the hook is installed.

```sh
node /absolute/path/to/review/cli.mjs install --provider=codex --model=CURRENT_MODEL
```

Use an explicit supported model. Codex can use its existing CLI login. For Claude,
use `--provider=claude` and provide `ANTHROPIC_API_KEY`. If replacing an older
paid-review stage, preserve the existing deterministic and Beads hook stages.
Run the installer again to update the trusted path, or use the `uninstall`
subcommand to restore the previous setup.

For a branch whose remote base is unavailable, restore the necessary Git history.
If the hook needs a known remote base before the first push, create the topic ref
on GitHub at the current integration commit. A human who has assessed a blocked or
incomplete result can accept the exact key printed by the hook and retry the push:

```sh
node /absolute/path/to/review/cli.mjs accept EXACT_REVIEW_KEY "Reason this precise risk is accepted"
```

For review context from a pinned dependency, fetch its known commit and add the full
object ID in the caller's local Git configuration:

```sh
git fetch --no-tags https://github.com/OWNER/DEPENDENCY FULL_COMMIT
git config --local --add mobile-delivery.reviewContextCommit FULL_COMMIT
```
