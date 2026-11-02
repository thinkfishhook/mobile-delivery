package delivery

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class VerificationSuccessTest {
    @Test
    fun `later command cannot remove an earlier report and bypass the final gate`() =
        temp { source ->
            val report = source.resolve("unit")
            val commands =
                RecordingCommands().apply {
                    respond = { call ->
                        val stdout =
                            when (call.args.first()) {
                                "uname" -> "arm64"
                                "xcodebuild" -> "Xcode ${config.xcode}"
                                "unit-tests" -> {
                                    report
                                        .createDirectories()
                                        .resolve("TEST-results.xml")
                                        .writeText("<testsuite tests=\"2\"/>")
                                    ""
                                }
                                else -> {
                                    report.toFile().deleteRecursively()
                                    ""
                                }
                            }
                        CommandResult(0, stdout, "")
                    }
                }
            val failure = assertFails {
                Verification(
                        config.copy(
                            verification =
                                VerificationConfig(
                                    commands = listOf(listOf("unit-tests"), listOf("cleanup")),
                                    testReports = listOf("unit"),
                                )
                        ),
                        commands,
                        emptyMap(),
                    )
                    .verify(source, source.resolve("evidence"))
            }
            assertContains(failure.message!!, "Missing test reports:")
        }

    @Test
    fun `process commands expose seam timing without including report parsing`() {
        val ticks = ArrayDeque(listOf(10_000_000L, 1_244_000_000L))
        val result = ProcessCommands {
            ticks.removeFirst()
        }
            .execute(listOf("/bin/sh", "-c", "printf probe"))
        assertEquals("probe", result.stdout)
        assertEquals(1234, result.elapsedMillis)
        assertTrue(ticks.isEmpty())
    }

    @Test
    fun `passing commands log one ordered line with elapsed duration and their own reports`() =
        temp { source ->
            var nanos = 0L
            val commands =
                RecordingCommands().apply {
                    respond = { call ->
                        when (call.args.first()) {
                            "uname" -> CommandResult(0, "arm64", "")
                            "xcodebuild" -> CommandResult(0, "Xcode ${config.xcode}", "")
                            else -> {
                                val name = call.args.first()
                                nanos +=
                                    when (name) {
                                        "unit-tests" -> 724_000_000_000L
                                        "lint" -> 999_000_000L
                                        else -> 3_661_000_000_000L
                                    }
                                call.log!!.appendText(
                                    "passing test-by-test output must stay in the log\n"
                                )
                                if (name != "lint") {
                                    val report =
                                        source.resolve(
                                            if (name == "unit-tests") "unit" else "integration"
                                        )
                                    report
                                        .createDirectories()
                                        .resolve("TEST-results.xml")
                                        .writeText(
                                            """<testsuite tests="${if (name == "unit-tests") 1197 else 12}"/>"""
                                        )
                                }
                                CommandResult(0, "unwanted stdout", "unwanted stderr")
                            }
                        }
                    }
                }
            val output = ByteArrayOutputStream()
            val original = System.out
            try {
                System.setOut(PrintStream(output))
                Verification(
                        config.copy(
                            verification =
                                VerificationConfig(
                                    commands =
                                        listOf(
                                            listOf("unit-tests", "--plan", "unit"),
                                            listOf("lint"),
                                            listOf("integration-tests"),
                                        ),
                                    testReports = listOf("integration", "unit"),
                                )
                        ),
                        TimedCommands(commands) { nanos },
                        emptyMap(),
                    )
                    .verify(source, source.resolve("evidence"))
            } finally {
                System.setOut(original)
            }
            assertEquals(
                listOf(
                    "✓ unit-tests --plan unit — 12m04s — unit: 1197 passed",
                    "✓ lint — 0s",
                    "✓ integration-tests — 1h01m01s — integration: 12 passed",
                ),
                output.toString().trim().lines(),
            )
            assertContains(
                source.resolve("evidence/verification.log").readText(),
                "test-by-test output",
            )
            val totals =
                json.decodeFromString<Map<String, TestTotals>>(
                    source.resolve("evidence/tests.json").readText()
                )
            assertEquals(1209, totals.values.sumOf { it.tests })
        }
}
