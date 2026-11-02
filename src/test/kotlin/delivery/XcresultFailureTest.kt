package delivery

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class XcresultFailureTest {
    @Test
    fun `unit UI and bare assertion failures retain names and available locations`() {
        assertEquals(
            listOf(
                TestFailure(
                    "SampleTests/ValueTests/testValue()",
                    "XCTAssertEqual failed: (1) is not equal to (2)",
                    "Tests/ValueTests.swift",
                    12,
                ),
                TestFailure("SampleTests/ValueTests/testFlag()", "XCTAssertTrue failed"),
                TestFailure("SampleUITests/ScreenTests/testOpen()", "Failed to find button"),
            ),
            XcresultReport(Path.of("tests.xcresult"))
                .failures(resource("xcresult/test-failures.json")),
        )
    }

    @Test
    fun `failed commands inspect every existing bundle without assertion text in log`() =
        temp { source ->
            val commands =
                RecordingCommands().apply {
                    respond = { call ->
                        when (call.args.first()) {
                            "uname" -> CommandResult(0, "arm64", "")
                            "xcodebuild" -> CommandResult(0, "Xcode ${config.xcode}", "")
                            "sample-tests" -> {
                                source.resolve("first.xcresult").createDirectories()
                                source.resolve("second.xcresult").createDirectories()
                                CommandResult(65, "", "")
                            }
                            "xcrun" -> CommandResult(0, resource("xcresult/test-failures.json"), "")
                            else -> error("Unexpected command")
                        }
                    }
                }
            val output = capture {
                val failure =
                    assertFailsWith<VerificationFailure> {
                        Verification(
                                config.copy(
                                    verification =
                                        VerificationConfig(
                                            commands = listOf(listOf("sample-tests")),
                                            testReports =
                                                listOf(
                                                    "first.xcresult",
                                                    "missing.xcresult",
                                                    "second.xcresult",
                                                ),
                                        )
                                ),
                                commands,
                                emptyMap(),
                            )
                            .verify(source, source.resolve("evidence"))
                    }
                assertEquals(FailureKind.VALIDATION, failure.kind)
            }
            assertEquals(
                2,
                commands.calls.count {
                    it.args.take(5) ==
                        listOf("xcrun", "xcresulttool", "get", "test-results", "tests")
                },
            )
            assertContains(
                output,
                "::error title=SampleTests/ValueTests/testValue(),file=Tests/ValueTests.swift,line=12::XCTAssertEqual failed",
            )
            assertContains(
                output,
                "::error title=SampleUITests/ScreenTests/testOpen()::Failed to find button",
            )
            assertEquals(6, output.lineSequence().count { it.startsWith("::error") })
            println(output)
        }

    @Test
    fun `annotation limit reserves room for command failure and reports remaining count`() {
        val output = capture {
            annotateTestFailures((1..14).map { TestFailure("Target/Class/test$it()", "failed") })
        }
        val lines = output.trim().lines()
        assertEquals(9, lines.size)
        assertContains(lines.last(), "6 more failing tests")
        assertContains(lines.first(), "title=Target/Class/test1()")
    }

    @Test
    fun `annotation cap boundary emits every test without an overflow notice`() {
        for (count in listOf(0, 1, 9, 10)) {
            val output = capture {
                annotateTestFailures(
                    (1..count).map { TestFailure("Target/Class/test$it()", "failed") }
                )
            }
            val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(minOf(count, 9), lines.size)
            if (count == 10) assertContains(lines.last(), "2 more failing tests")
            else assertFalse(output.contains("more failing tests"))
        }
    }

    @Test
    fun `unreadable result does not hide later bundles or replace the original error`() =
        temp { source ->
            val commands =
                RecordingCommands().apply {
                    respond = { call ->
                        when (call.args.first()) {
                            "uname" -> CommandResult(0, "arm64", "")
                            "xcodebuild" -> CommandResult(0, "Xcode ${config.xcode}", "")
                            "sample-tests" -> {
                                source.resolve("broken.xcresult").createDirectories()
                                source.resolve("valid.xcresult").createDirectories()
                                CommandResult(65, "", "")
                            }
                            "xcrun" ->
                                CommandResult(
                                    0,
                                    if (call.args[6].endsWith("broken.xcresult")) "invalid JSON"
                                    else resource("xcresult/test-failures.json"),
                                    "",
                                )
                            else -> error("Unexpected command")
                        }
                    }
                }
            val output = source.resolve("evidence")
            val annotations = capture {
                val failure =
                    assertFailsWith<VerificationFailure> {
                        Verification(
                                config.copy(
                                    verification =
                                        VerificationConfig(
                                            commands = listOf(listOf("sample-tests")),
                                            testReports =
                                                listOf("broken.xcresult", "valid.xcresult"),
                                        )
                                ),
                                commands,
                                emptyMap(),
                            )
                            .verify(source, output)
                    }
                assertEquals(FailureKind.VALIDATION, failure.kind)
                assertEquals("sample-tests failed (exit 65); see verification.log", failure.message)
            }
            assertContains(annotations, "title=SampleUITests/ScreenTests/testOpen()")
            assertContains(
                output.resolve("verification.log").readText(),
                "Could not read test failures from",
            )
            assertTrue(output.resolve("valid.xcresult").exists())
        }

    @Test
    fun `annotation escapes data and property delimiters independently`() {
        assertEquals(
            "::error title=a%2Cb%3Ac%25%0A,file=a%2Cb.swift,line=7::bad: value,%25%0D%0A\n",
            capture {
                annotate(Annotation("error", "bad: value,%\r\n", "a,b:c%\n", "a,b.swift", 7))
            },
        )
        assertEquals(
            "::notice::old: message,unchanged\n",
            capture { annotate(Annotation("notice", "old: message,unchanged")) },
        )
    }

    private fun capture(action: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(bytes))
            action()
        } finally {
            System.setOut(original)
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
