package delivery

import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class LabelsTest {
    @Test
    fun `missing needs-owner label explains that agents skip it`() {
        val commands = RecordingCommands()
        commands.respond = { call ->
            if (call.args[2] == "repos/owner/repo/labels/needs-owner")
                CommandResult(1, "", "HTTP 404")
            else CommandResult(0, "{}", "")
        }
        GitHub("owner/repo", commands, emptyMap()).ensureDeliveryLabels()
        val payload =
            json.parseToJsonElement(commands.calls.single { it.input != null }.input!!).jsonObject
        assertEquals("needs-owner", payload.string("name"))
        assertContains(payload.string("description"), "agents skip")
    }

    @Test
    fun `maintenance creates missing labels but preserves existing labels`() {
        val commands = RecordingCommands()
        commands.respond = { call ->
            when (call.args[2]) {
                "repos/owner/repo/labels/needs-owner" -> CommandResult(0, "{}", "")
                "repos/owner/repo/labels/$HOLD_LABEL" -> CommandResult(0, "{}", "")
                "repos/owner/repo/labels/$INCIDENT_LABEL" -> CommandResult(1, "", "HTTP 404")
                "repos/owner/repo/labels" -> CommandResult(0, "{}", "")
                else -> error("Unexpected label request: ${call.args}")
            }
        }
        GitHub("owner/repo", commands, emptyMap()).ensureDeliveryLabels()
        val writes = commands.calls.filter { it.input != null }
        assertEquals(1, writes.size)
        assertEquals(
            INCIDENT_LABEL,
            json.parseToJsonElement(writes.single().input!!).jsonObject.string("name"),
        )
        assertContains(writes.single().args, "POST")
    }

    @Test
    fun `a concurrent label creator is accepted but other validation failures propagate`() {
        for (code in listOf("already_exists", "invalid")) {
            val commands = RecordingCommands()
            commands.respond = { call ->
                if (call.input == null) CommandResult(1, "", "HTTP 404")
                else
                    CommandResult(
                        1,
                        """HTTP/2 422

                    {"message":"Validation Failed","errors":[{"resource":"Label","field":"name","code":"$code"}]}
                """
                            .trimIndent(),
                        "HTTP 422",
                    )
            }
            val github = GitHub("owner/repo", commands, emptyMap())
            if (code == "already_exists") {
                github.ensureDeliveryLabels()
                assertEquals(3, commands.calls.count { it.input != null })
            } else assertFailsWith<GitHubFailure> { github.ensureDeliveryLabels() }
        }
    }

    @Test
    fun `an unreadable label fails before any label write`() {
        val commands = RecordingCommands()
        commands.respond = { CommandResult(1, "", "HTTP 403") }
        assertFailsWith<GitHubFailure> {
            GitHub("owner/repo", commands, emptyMap()).ensureDeliveryLabels()
        }
        assertEquals(1, commands.calls.size)
        assertNull(commands.calls.single().input)
    }
}
