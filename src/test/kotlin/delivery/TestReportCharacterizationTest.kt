package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class TestReportCharacterizationTest {
    @Test
    fun `JUnit directories aggregate matching suites with absent counts defaulting to zero`() =
        temp { source ->
            val output = source.resolve("evidence")
            verify(source, output, "junit") { directory ->
                directory
                    .resolve("TEST-first.xml")
                    .writeText("""<testsuite tests="2" failures="0" errors="0" skipped="0"/>""")
                directory.resolve("TEST-second.xml").writeText("""<testsuite tests="3"/>""")
                directory.resolve("ignored.xml").writeText("not a report")
            }
            assertEquals(mapOf("junit" to TestTotals(5, 0, 0, 0)), totals(output))
        }

    @Test
    fun `Xcode summary JSON produces totals while ignoring extra fields`() = temp { source ->
        val output = source.resolve("evidence")
        verify(source, output, "tests.xcresult", resource("xcresult/passing.json")) {}
        assertEquals(mapOf("tests.xcresult" to TestTotals(2, 0, 0, 0)), totals(output))
    }

    @Test
    fun `missing reports retain their exact errors`() {
        for (report in listOf("junit", "tests.xcresult")) temp { source ->
            val failure = assertFails {
                verify(source, source.resolve("evidence"), report, createReport = false) {}
            }
            if (report == "junit") {
                assertIs<IllegalArgumentException>(failure)
            } else {
                assertIs<VerificationFailure>(failure)
                assertEquals("application-tests", failure.phase)
                assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
            }
            assertEquals("Missing test reports: ${source.resolve(report)}", failure.message)
        }
    }

    @Test
    fun `zero tests are infrastructure failures even with other nonzero counts`() {
        assertGate(TestTotals(0, 0, 0, 0), FailureKind.INFRASTRUCTURE)
        assertGate(TestTotals(0, 1, 0, 1), FailureKind.INFRASTRUCTURE)
    }

    @Test
    fun `failures errors and skips are validation failures with exact counts`() {
        assertGate(TestTotals(3, 1, 0, 0), FailureKind.VALIDATION)
        assertGate(TestTotals(3, 0, 0, 1), FailureKind.VALIDATION)
        assertGate(TestTotals(3, 0, 1, 0), FailureKind.VALIDATION, reports = listOf("junit"))
    }

    private fun assertGate(
        counts: TestTotals,
        kind: FailureKind,
        reports: List<String> = listOf("junit", "tests.xcresult"),
    ) {
        for (report in reports) temp { source ->
            val output = source.resolve("evidence")
            val failure =
                assertFailsWith<VerificationFailure> {
                    verify(
                        source,
                        output,
                        report,
                        """{"totalTestCount":${counts.tests},"failedTests":${counts.failures},"skippedTests":${counts.skipped}}""",
                    ) { directory ->
                        directory
                            .resolve("TEST-results.xml")
                            .writeText(
                                """<testsuite tests="${counts.tests}" failures="${counts.failures}" errors="${counts.errors}" skipped="${counts.skipped}"/>"""
                            )
                    }
                }
            assertEquals("application-tests", failure.phase)
            assertEquals(kind, failure.kind)
            assertEquals(
                "Test gate failed: ${source.resolve(report)}: [${counts.tests}, ${counts.failures}, ${counts.errors}, ${counts.skipped}]",
                failure.message,
            )
            assertFalse(output.resolve("tests.json").exists())
            assertContains(
                output.resolve("verification.log").readText(),
                "Verification failed: ${failure.message}\n",
            )
        }
    }

    private fun totals(output: Path) =
        json.decodeFromString<Map<String, TestTotals>>(output.resolve("tests.json").readText())

    private fun verify(
        source: Path,
        output: Path,
        report: String,
        summary: String = "{}",
        createReport: Boolean = true,
        populate: (Path) -> Unit,
    ) {
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    val text =
                        when (call.args.first()) {
                            "uname" -> "arm64"
                            "xcodebuild" -> "Xcode ${config.xcode}\nBuild version fixture"
                            "sample-tests" -> {
                                if (createReport)
                                    populate(source.resolve(report).createDirectories())
                                ""
                            }
                            "xcrun" -> summary
                            else -> error("Unexpected command: ${call.args}")
                        }
                    CommandResult(0, text, "")
                }
            }
        Verification(
                config.copy(
                    verification =
                        VerificationConfig(
                            commands = listOf(listOf("sample-tests")),
                            testReports = listOf(report),
                        )
                ),
                commands,
                emptyMap(),
            )
            .verify(source, output)
    }
}
