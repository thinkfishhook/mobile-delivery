package delivery

import kotlin.test.*
import org.junit.Test

class ToolsTest {
    @Test
    fun `bootstrap rejects a retired app environment file before reading files or writing secrets`() {
        val commands = RecordingCommands()
        val flags =
            listOf("--asc-config", "--p12", "--p12-password-file", "--profile", "--app-build-env")
        val failure =
            assertFailsWith<IllegalArgumentException> {
                bootstrap(flags.flatMap { listOf(it, "nonexistent") }, config, commands, emptyMap())
            }
        assertContains(failure.message.orEmpty(), "Supply --asc-config")
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `unsupported ASC runner names available platforms and a usable iOS runner`() {
        val commands = RecordingCommands()
        commands.responses += CommandResult(0, "Darwin\n", "")
        commands.responses += CommandResult(0, "x86_64\n", "")
        val failure =
            assertFailsWith<IllegalArgumentException> {
                installAsc(Toolchain.bundled, commands, emptyMap())
            }
        assertContains(failure.message.orEmpty(), "Darwin/x86_64")
        assertContains(failure.message.orEmpty(), "macOS/arm64")
        assertContains(failure.message.orEmpty(), "Linux/x86_64")
        assertContains(failure.message.orEmpty(), "runs-on: macos-26")
        assertContains(failure.message.orEmpty(), "arm64 self-hosted Mac")
        assertEquals(
            listOf(listOf("uname", "-s"), listOf("uname", "-m")),
            commands.calls.map { it.args },
        )
    }

    @Test
    fun `supported ASC platforms come from the configured assets`() {
        val commands = RecordingCommands()
        commands.responses += CommandResult(0, "Unknown", "")
        commands.responses += CommandResult(0, "arch", "")
        val toolchain =
            Toolchain.bundled.copy(
                ascAssets = mapOf("Linux/aarch64" to Toolchain.bundled.ascAssets.values.first())
            )
        val failure =
            assertFailsWith<IllegalArgumentException> {
                installAsc(toolchain, commands, emptyMap())
            }
        assertContains(failure.message.orEmpty(), "Supported platforms: Linux/aarch64.")
        assertFalse("Linux/x86_64" in failure.message.orEmpty())
    }
}
