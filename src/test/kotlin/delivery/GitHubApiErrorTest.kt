package delivery

import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class GitHubApiErrorTest {
    @Test
    fun `API failures retain exact message text and redact tokens`() {
        val cases =
            listOf(
                CommandResult(
                    1,
                    "HTTP/2.0 401 Unauthorized\r\nContent-Type: application/json\r\n\r\n" +
                        """{"message":"Bad credentials\n job-token receipt-token"}""",
                    "HTTP 401",
                ) to "GitHub API failed: git/refs (HTTP 401: Bad credentials *** ***)",
                CommandResult(
                    1,
                    "HTTP/2.0 404 Not Found\n\n" + """{"message":"Not Found"}""",
                    "HTTP 404",
                ) to "GitHub API failed: git/refs (HTTP 404: Not Found)",
                CommandResult(7, "", "gh failed before an HTTP response") to
                    "GitHub API failed: git/refs (exit 7)",
            )
        for ((response, expected) in cases) {
            val commands = RecordingCommands().apply { respond = { response } }
            val waits = mutableListOf<Long>()
            val github =
                GitHub(
                    "owner/repo",
                    commands,
                    mapOf("GH_TOKEN" to "job-token", "IOS_RECEIPT_TOKEN" to "receipt-token"),
                    waits::add,
                )
            val failure =
                assertFailsWith<CommandError> {
                    github.api("git/refs?private=query", receiptToken = true)
                }
            assertEquals(expected, failure.message)
            assertEquals(1, commands.calls.size)
            assertTrue(waits.isEmpty())
        }
    }

    @Test
    fun `read retries retain exponential backoff and four attempt limit`() {
        val commands =
            RecordingCommands().apply {
                respond = { CommandResult(1, "HTTP/2 503\n\n{}", "HTTP 503") }
            }
        val waits = mutableListOf<Long>()
        val github = GitHub("owner/repo", commands, emptyMap(), waits::add)
        val error = assertFailsWith<CommandError> { github.api("issues") }
        assertEquals("GitHub API failed: issues (HTTP 503)", error.message)
        assertEquals(4, commands.calls.size)
        assertEquals(listOf(1000L, 2000L, 4000L), waits)
    }

    @Test
    fun `transient reads recover but writes never replay and optional 404 stays absent`() {
        val commands =
            RecordingCommands().apply {
                responses.add(CommandResult(1, "HTTP/2 429\nRetry-After: 2\n\n{}", "HTTP 429"))
                response("{}")
            }
        val waits = mutableListOf<Long>()
        val github = GitHub("owner/repo", commands, emptyMap(), waits::add)
        assertNotNull(github.api("issues"))
        assertEquals(listOf(2000L), waits)
        assertEquals(2, commands.calls.size)
        commands.respond = { CommandResult(1, "HTTP/2 503\n\n{}", "HTTP 503") }
        assertFailsWith<CommandError> { github.api("issues", buildJsonObject {}) }
        assertEquals(3, commands.calls.size)
        assertEquals(listOf(2000L), waits)
        commands.respond = { CommandResult(1, "", "gh: Not Found (HTTP 404)") }
        assertNull(github.api("issues/1", missing = true))
    }
}
