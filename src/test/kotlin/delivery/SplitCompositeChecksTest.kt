package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class SplitCompositeChecksTest {
    @Test
    fun `split actions enforce phase order and automatic credential isolation`() = temp { root ->
        val repository = Path.of(System.getProperty("delivery.root"))
        for (action in listOf("ci", "testflight-deploy")) {
            val target = root.resolve("$action/action.yml")
            target.parent.createDirectories()
            repository.resolve("$action/action.yml").copyTo(target)
        }
        SplitCompositeChecks().validate(root)
        val ci = root.resolve("ci/action.yml")
        val original = ci.readText()
        ci.writeText(
            original.replace(
                "run: mobile-delivery verifySource",
                "run: mobile-delivery deployPackage",
            )
        )
        assertFails { SplitCompositeChecks().validate(root) }
        ci.writeText(original)
        val deploy = root.resolve("testflight-deploy/action.yml")
        deploy.writeText(
            deploy
                .readText()
                .replace(
                    "if: steps.select.outputs.manual == 'true' && steps.select.outputs.selected == 'true'",
                    "if: steps.select.outputs.selected == 'true'",
                )
        )
        assertFails { SplitCompositeChecks().validate(root) }
    }

    @Test
    fun `deployment caller rejects manual artifact inputs main-only guards and review dependency`() {
        val valid =
            """
            name: TestFlight Deploy
            on:
              workflow_run: { workflows: [Continuous Integration], types: [completed] }
              workflow_dispatch:
            jobs:
              deploy:
                steps:
                  - uses: owner/tool/testflight-deploy@${"a".repeat(40)}
            """
                .trimIndent()
        validateSplitWorkflow(
            workflowDocument(valid),
            yamlMapping(yamlMapping(workflowDocument(valid)["jobs"])["deploy"]),
            "deploy",
        )
        for (changed in
            listOf(
                valid.replace(
                    "workflow_dispatch:",
                    "workflow_dispatch:\n    inputs: { artifact_id: { type: string } }",
                ),
                valid.replace("deploy:", "deploy:\n    if: github.ref == 'refs/heads/main'"),
                valid.replace("deploy:", "deploy:\n    needs: ai-review"),
            )) {
            val document = workflowDocument(changed)
            assertFails {
                validateSplitWorkflow(
                    document,
                    yamlMapping(yamlMapping(document["jobs"])["deploy"]),
                    "deploy",
                )
            }
        }
    }
}
