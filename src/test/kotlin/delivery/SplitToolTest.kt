package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class SplitToolTest {
    @Test
    fun `manual branch selection is frozen without branch-head CI Apple or reviewer reads`() =
        temp { root ->
            val commands =
                RecordingCommands().apply {
                    respond = { error("Selection must not call external tools") }
                }
            val output = root.resolve("output").also { it.writeText("") }
            val env =
                mapOf(
                    "GITHUB_ACTIONS" to "true",
                    "GITHUB_EVENT_NAME" to "workflow_dispatch",
                    "GITHUB_REPOSITORY" to "owner/repo",
                    "GITHUB_SHA" to "b".repeat(40),
                    "GITHUB_REF" to "refs/heads/experiment",
                    "GITHUB_WORKFLOW_REF" to
                        "owner/repo/.github/workflows/deploy.yml@refs/heads/experiment",
                    "GITHUB_RUN_ID" to "456",
                    "GITHUB_RUN_ATTEMPT" to "1",
                    "DELIVERY_VALIDATION_MODE" to "deploy",
                    "DELIVERY_INPUTS" to """{"branch":"trunk"}""",
                    "IOS_DELIVERY_ENABLED" to "true",
                    "GITHUB_OUTPUT" to output.toString(),
                )
            SplitTool(root, config, commands, env).run("selectSplit")
            val text = output.readText()
            assertTrue("b".repeat(40) in text)
            assertTrue(Regex("manual<<[^\\n]+\\ntrue").containsMatchIn(text))
            assertTrue(Regex("selected<<[^\\n]+\\ntrue").containsMatchIn(text))
            output.writeText("")
            SplitTool(root, config, commands, env + ("IOS_DELIVERY_ENABLED" to "false"))
                .run("selectSplit")
            assertTrue(Regex("selected<<[^\\n]+\\nfalse").containsMatchIn(output.readText()))
            assertTrue(commands.calls.isEmpty())
        }
}
