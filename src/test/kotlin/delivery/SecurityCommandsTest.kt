package delivery

import kotlin.test.*
import org.junit.Test

class SecurityCommandsTest {
    @Test
    fun `password operations use quoted stdin with no secret in argv environment or logs`() {
        val commands = RecordingCommands()
        val password = "p12 'quoted' \"value\" \\ $ é"
        commands.security(
            listOf("import", "/tmp/private identity.p12", "-P", password),
            mapOf("PATH" to "/usr/bin", "IOS_DISTRIBUTION_P12_PASSWORD" to password),
        )
        val call = commands.calls.single()
        assertEquals(listOf("security", "-i"), call.args)
        assertEquals(mapOf("PATH" to "/usr/bin"), call.env)
        assertNull(call.log)
        assertEquals(
            "\"import\" \"/tmp/private identity.p12\" \"-P\" \"p12 'quoted' \\\"value\\\" \\\\ $ é\"\n",
            call.input,
        )
    }

    @Test
    fun `input cannot inject another command or exceed the interpreter byte limit`() {
        for (password in listOf("one\ntwo", "one\rtwo", "one\u0000two", "é".repeat(2048))) {
            val commands = RecordingCommands()
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    commands.security(listOf("unlock-keychain", "-p", password), emptyMap())
                }
            assertFalse(failure.message.orEmpty().contains(password))
            assertTrue(commands.calls.isEmpty())
        }
    }

    @Test
    fun `a failed password operation propagates without exposing its stdin`() {
        val commands = RecordingCommands()
        commands.responses += CommandResult(1, "", "rejected")
        val failure =
            assertFailsWith<CommandError> {
                commands.security(listOf("unlock-keychain", "-p", "private-value"), emptyMap())
            }
        assertEquals("security failed (exit 1)", failure.message)
        assertEquals(1, commands.calls.size)
    }
}
