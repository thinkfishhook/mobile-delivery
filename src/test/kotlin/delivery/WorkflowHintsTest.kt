package delivery

import kotlin.test.*
import org.junit.Test

class WorkflowHintsTest {
    private val env =
        mapOf(
            "GITHUB_REPOSITORY" to "owner/repo",
            "DELIVERY_WORKFLOW_REPOSITORY" to "tools/delivery",
        )

    @Test
    fun `current caller hints need no API and cross workflow lookup stops at the first matching page`() {
        val commands = RecordingCommands()
        val github =
            GitHub(
                "owner/repo",
                commands,
                env + ("GITHUB_WORKFLOW_REF" to "owner/repo/.github/workflows/my-release.yml@main"),
            )
        val current = github.workflowHints("app-store-release")
        assertEquals(
            "gh workflow run my-release.yml -f operation=pause",
            current.command("app-store-release", "-f operation=pause"),
        )
        assertTrue(commands.calls.isEmpty())
        val row = """{"path":".github/workflows/ship.yml","head_sha":"${"a".repeat(40)}"}"""
        commands.response("""{"workflow_runs":[${List(100) { row }.joinToString()}]}""")
        commands.response(workflowContents("tools/delivery/testflight@v1"))
        val hints = github.workflowHints()
        repeat(2) { assertContains(hints.retry("abc", "12.1"), "gh workflow run ship.yml") }
        assertEquals(2, commands.calls.size)
    }

    @Test
    fun `retry uses the current caller filename`() {
        val hints =
            WorkflowHints(
                env +
                    ("GITHUB_WORKFLOW_REF" to
                        "owner/repo/.github/workflows/beta-custom.yaml@refs/heads/main")
            )
        assertContains(
            hints.retry("abc", "12.1"),
            "gh workflow run beta-custom.yaml -f operation=retry",
        )
    }

    @Test
    fun `cross workflow hints find the composite in the exact caller revision`() {
        val commands = RecordingCommands()
        val sha = "a".repeat(40)
        commands.response(
            """{"workflow_runs":[{"path":".github/workflows/unrelated.yml","head_sha":"$sha"},{"path":".github/workflows/ship-custom.yml@main","head_sha":"$sha"}]}"""
        )
        commands.response(workflowContents("other/tool@v1"))
        commands.response(workflowContents("tools/delivery/testflight@v1"))
        assertEquals(
            "gh workflow run ship-custom.yml -f operation=deliver",
            GitHub("owner/repo", commands, env)
                .workflowHints()
                .command("testflight", "-f operation=deliver"),
        )
    }

    @Test
    fun `missing or unreadable metadata links to Actions without guessing`() {
        assertEquals(
            "https://github.com/owner/repo/actions",
            WorkflowHints(env).retry("abc", "12.1"),
        )
        val commands = RecordingCommands()
        commands.response("""{"workflow_runs":[]}""")
        assertEquals(
            "https://github.com/owner/repo/actions",
            GitHub("owner/repo", commands, env)
                .workflowHints()
                .command("app-store-release", "-f operation=pause"),
        )
        commands.respond = { throw CommandError("unavailable") }
        assertEquals(
            "https://github.com/owner/repo/actions",
            GitHub("owner/repo", commands, env).workflowHints().retry("abc", "12.1"),
        )
    }
}
