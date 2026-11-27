# Optional API-based landed-push review

The [PR workflow](pr-workflow.md) uses native Codex App review. Apps that opt into
API-based landed-push review can run it in a separate workflow on a fresh GitHub-hosted
Ubuntu runner. Keep it independent of CI and deployment.

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

Use the same stable full SHA as the app's other shared actions and choose a supported
API model. For Claude, set `provider: claude` and pass `secrets.ANTHROPIC_API_KEY`.
Use a repository secret bound directly to the action input. Keep the shared review
queue so concurrent runs can safely update finding issues. If provider or publication
access fails, restore access and rerun the exact review job from Actions.
