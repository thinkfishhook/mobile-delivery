package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class HostedReviewChecksTest {
    @Test
    fun `hosted review remains in its own workflow with minimum credentials and serialized publication`() {
        val valid =
            """
            name: AI Code Review
            on: { push: { branches: [trunk] } }
            jobs:
              review:
                runs-on: ubuntu-latest
                permissions: { contents: read, issues: write }
                concurrency: { group: mobile-delivery-ai-review, queue: max, cancel-in-progress: false }
                steps:
                  - uses: owner/tool/ai-review@${"a".repeat(40)}
                    with:
                      branch: trunk
                      provider: codex
                      model: configured-model
                      provider-api-key: ${'$'}{{ secrets.OPENAI_API_KEY }}
            """
                .trimIndent()
        validateHostedReviewWorkflow(workflowDocument(valid), "review")
        for (changed in
            listOf(
                valid.replace("name: AI Code Review", "name: Continuous Integration"),
                valid.replace("review:\n", "review:\n    needs: ci\n"),
                valid.replace("ubuntu-latest", "self-hosted"),
                valid.replace("queue: max", "queue: single"),
                valid.replace("contents: read", "contents: write"),
                valid.replace("OPENAI_API_KEY", "IOS_RECEIPT_TOKEN"),
                valid.replace("steps:\n", "env: { ASC_PRIVATE_KEY: secret }\n    steps:\n"),
            )) assertFails { validateHostedReviewWorkflow(workflowDocument(changed), "review") }
    }

    @Test
    fun `hosted composite scopes provider keys and always exposes operation failure`() =
        temp { root ->
            val repository = Path.of(System.getProperty("delivery.root"))
            val target = root.resolve("ai-review/action.yml")
            target.parent.createDirectories()
            repository.resolve("ai-review/action.yml").copyTo(target)
            HostedReviewChecks().validate(root)
            val original = target.readText()
            for (changed in
                listOf(
                    original.replace("safety-strategy: drop-sudo", "safety-strategy: unsafe"),
                    original.replace("sandbox: read-only", "sandbox: workspace-write"),
                    original.replace("if: always()", "if: success()"),
                    original.replace(
                        "run: npm install",
                        "env: { OPENAI_API_KEY: secret }\n      run: npm install",
                    ),
                )) {
                target.writeText(changed)
                assertFails { HostedReviewChecks().validate(root) }
            }
        }
}
