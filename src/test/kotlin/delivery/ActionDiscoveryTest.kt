package delivery

import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ActionDiscoveryTest {
    @Test
    fun `composite callers resolve every role with version tags and exact SHAs`() {
        val roles =
            mapOf(
                "testflight" to "testflight-delivery.yml",
                "maintenance" to "maintenance.yml",
                "app-store-release" to "app-store-release.yml",
                "app-store-rollout" to "app-store-release.yml",
            )
        for ((action, role) in roles) {
            for (pin in listOf("v1", "v1.0.0", "a".repeat(40))) {
                val text = "jobs:\n  ship:\n    steps:\n      - uses: tools/delivery/$action@$pin\n"
                assertEquals(setOf(role), actionWorkflowRoles(text, "tools/delivery"))
                assertEquals(emptySet(), actionWorkflowRoles(text, "other/tool"))
            }
        }
        for (action in
            listOf(
                "tools/delivery/testflight@",
                "tools/delivery/other@v1",
                "tools/delivery-other/testflight@v1",
            )) {
            assertEquals(
                emptySet(),
                actionWorkflowRoles(
                    "jobs:\n  ship:\n    steps:\n      - uses: $action\n",
                    "tools/delivery",
                ),
            )
        }
    }

    @Test
    fun `action callers are found at the run revision and reused for operator hints`() {
        val commands = RecordingCommands()
        val sha = "b".repeat(40)
        val rows =
            """{"workflow_runs":[{"id":9,"head_sha":"$sha","status":"queued","path":".github/workflows/custom-delivery.yaml","referenced_workflows":[]}]}"""
        val text =
            """
            jobs:
              ship:
                steps:
                  - uses: tools/delivery/testflight@v1
            """
                .trimIndent()
        val content = Base64.getEncoder().encodeToString(text.toByteArray())
        commands.respond = { call ->
            val path = call.args[2]
            CommandResult(
                0,
                when {
                    path.contains("actions/runs?") -> rows
                    path ==
                        "repos/owner/repo/contents/.github/workflows/custom-delivery.yaml?ref=$sha" ->
                        """{"content":"$content","encoding":"base64"}"""
                    else -> error("Unexpected lookup: $path")
                },
                "",
            )
        }
        val github =
            GitHub(
                "owner/repo",
                commands,
                mapOf("DELIVERY_WORKFLOW_REPOSITORY" to "tools/delivery", "GITHUB_RUN_ID" to "1"),
            )
        assertTrue(github.queuedPush(sha))
        assertContains(
            github.workflowHints().retry(sha, "1.1"),
            "gh workflow run custom-delivery.yaml",
        )
        assertEquals(1, commands.calls.count { it.args[2].contains("contents/") })
    }

    @Test
    fun `retired reusable metadata cannot classify an unrelated caller as delivery`() {
        val sha = "a".repeat(40)
        val commands = RecordingCommands().apply { response(workflowContents("other/tool@v1")) }
        val run =
            json
                .parseToJsonElement(
                    """{"head_sha":"$sha","path":".github/workflows/ship.yml","referenced_workflows":[{"path":"tools/delivery/.github/workflows/testflight-delivery.yml@pin"}]}"""
                )
                .jsonObject
        val github =
            GitHub(
                "owner/repo",
                commands,
                mapOf("DELIVERY_WORKFLOW_REPOSITORY" to "tools/delivery"),
            )
        assertFalse(github.runUsesWorkflow(run, "testflight-delivery.yml"))
        assertContains(
            commands.calls.single().args[2],
            "contents/.github/workflows/ship.yml?ref=$sha",
        )
    }

    @Test
    fun `retired phase callers do not identify composite workflow roles`() {
        for (command in listOf("startRun", "sweep", "appStoreRelease", "rolloutControl")) {
            val text =
                "jobs:\n  ship:\n    steps:\n      - uses: tools/delivery@${"a".repeat(40)}\n        with: {command: $command}\n"
            assertEquals(emptySet(), actionWorkflowRoles(text, "tools/delivery"))
        }
    }
}
