package delivery

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test

class VerificationTailTest {
    @Test
    fun `failed command shows exactly the last 200 lines and preserves complete log`() {
        val lines = (1..250).map { "compiler output $it" }
        checkTail(lines, lines.takeLast(200))
    }

    @Test
    fun `short output is whole and workflow command lines cannot inject annotations`() {
        val lines =
            listOf(
                "::error::fake",
                "::endgroup::",
                "::group::fake",
                "prefix ::warning::fake\r::error::also fake",
                "compile failed",
            )
        checkTail(lines, listOf("Running sample-tests --debug") + lines)
    }

    @Test
    fun `command errors carry the log path separately and preserve displayed wording`() =
        temp { root ->
            val log = root.resolve("verification.log")
            val commands =
                RecordingCommands().apply {
                    respond = { CommandResult(65, "", "") }
                }
            val error =
                assertFailsWith<CommandError> {
                    commands.run(listOf("sample-tests"), log = log)
                }
            assertEquals("sample-tests failed (exit 65)", error.message)
            assertEquals(log, error.log)
            assertEquals(65, error.exit)
            assertEquals(
                "sample-tests failed (exit 65); see verification.log",
                error.displayMessage(),
            )
            val noLog = assertFailsWith<CommandError> { commands.run(listOf("sample-tests")) }
            assertEquals("sample-tests failed (exit 65)", noLog.displayMessage())
        }

    @Test
    fun `tail length can be limited for a verification instance`() = temp { root ->
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    when (call.args.first()) {
                        "uname" -> CommandResult(0, "arm64", "")
                        "xcodebuild" -> CommandResult(0, "Xcode ${config.xcode}", "")
                        else -> {
                            call.log!!.appendText("one\ntwo\nthree\n")
                            CommandResult(1, "", "")
                        }
                    }
                }
            }
        val error =
            assertFailsWith<VerificationFailure> {
                Verification(config, commands, emptyMap(), tailLines = 2)
                    .verify(root, root.resolve("output"))
            }
        assertEquals("two\nthree", error.failingCommand?.tail)
    }

    private fun checkTail(lines: List<String>, expected: List<String>) = temp { root ->
        val settings =
            config.copy(
                verification =
                    VerificationConfig(
                        commands = listOf(listOf("sample-tests", "--debug")),
                        testReports = listOf("junit"),
                    )
            )
        root
            .resolve(".github")
            .createDirectories()
            .resolve("delivery.json")
            .writeText(json.encodeToString(settings))
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    when (call.args.first()) {
                        "uname" -> CommandResult(0, "arm64", "")
                        "xcodebuild" -> CommandResult(0, "Xcode ${config.xcode}", "")
                        "sample-tests" -> {
                            call.log!!.appendText(lines.joinToString("\n", postfix = "\n"))
                            CommandResult(65, "", "")
                        }
                        else -> error("Unexpected command: ${call.args}")
                    }
                }
            }
        val bytes = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(bytes))
            val failure =
                assertFailsWith<VerificationFailure> {
                    runTask(arrayOf("verify"), root, emptyMap(), commands)
                }
            assertEquals("sample-tests failed (exit 65); see verification.log", failure.message)
        } finally {
            System.setOut(original)
        }
        val output = bytes.toString(Charsets.UTF_8)
        val group = "::group::Output tail: sample-tests --debug\n"
        assertContains(output, group)
        val expectedLines = expected.flatMap { it.lineSequence().toList() }
        val tail = output.substringAfter(group).substringBefore("\n::endgroup::\n") + "\n"
        assertEquals(
            expectedLines.joinToString("\n", postfix = "\n") {
                it.replace("::", ": :")
            },
            tail,
        )
        assertEquals(1, output.lineSequence().count { it == "::endgroup::" })
        assertEquals(
            listOf(group.trimEnd(), "::endgroup::"),
            output.lineSequence().filter { it.contains("::") }.toList(),
        )
        val summary =
            json
                .parseToJsonElement(root.resolve("build/delivery/run-summary.json").readText())
                .jsonObject
        val consoleSummary = output.substringAfter("\n::endgroup::\n").trim()
        assertEquals(summary, json.parseToJsonElement(consoleSummary).jsonObject)
        val failure = summary.getValue("failingCommand").jsonObject
        assertEquals("sample-tests --debug", failure.getValue("command").jsonPrimitive.content)
        assertEquals(65, failure.getValue("exit").jsonPrimitive.int)
        assertEquals(
            expectedLines.joinToString("\n"),
            failure.getValue("tail").jsonPrimitive.content,
        )
        val log = root.resolve("build/ci-verification/verification.log").readText()
        assertTrue(
            log.startsWith(
                "Running sample-tests --debug\n" + lines.joinToString("\n", postfix = "\n")
            )
        )
    }
}
