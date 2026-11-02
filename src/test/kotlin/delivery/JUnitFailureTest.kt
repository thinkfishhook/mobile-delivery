package delivery

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import org.junit.Test

class JUnitFailureTest {
    @Test
    fun `failure and error reports retain names messages and only matching class locations`() =
        temp { source ->
            populate(source)
            val report: TestReport = JUnitReport(source)
            assertEquals(
                listOf(
                    TestFailure("sample.LoadTest.load", "Unable to load fixture"),
                    TestFailure(
                        "sample.ValueTest.value",
                        "expected 1 but was 2",
                        "ValueTest.kt",
                        42,
                    ),
                ),
                report.failures(RecordingCommands(), emptyMap()),
            )
        }

    @Test
    fun `mixed reports share annotations after nonzero commands and gate failures`() {
        for (exit in listOf(0, 1)) temp { source ->
            val commands =
                RecordingCommands().apply {
                    respond = { call ->
                        when (call.args.first()) {
                            "uname" -> CommandResult(0, "arm64", "")
                            "xcodebuild" -> CommandResult(0, "Xcode ${config.xcode}", "")
                            "sample-tests" -> {
                                populate(source.resolve("junit").createDirectories())
                                source.resolve("tests.xcresult").createDirectories()
                                CommandResult(exit, "", "")
                            }
                            "xcrun" -> CommandResult(0, resource("xcresult/test-failures.json"), "")
                            else -> error("Unexpected command: ${call.args}")
                        }
                    }
                }
            val bytes = ByteArrayOutputStream()
            val original = System.out
            try {
                System.setOut(PrintStream(bytes))
                assertFailsWith<VerificationFailure> {
                    val settings =
                        config.copy(
                            verification =
                                VerificationConfig(
                                    commands = listOf(listOf("sample-tests")),
                                    testReports = listOf("junit", "tests.xcresult"),
                                )
                        )
                    source
                        .resolve(".github")
                        .createDirectories()
                        .resolve("delivery.json")
                        .writeText(json.encodeToString(settings))
                    runTask(
                        arrayOf("verify"),
                        source,
                        mapOf(
                            "GITHUB_STEP_SUMMARY" to
                                source.resolve("summary.md").createFile().toString()
                        ),
                        commands,
                    )
                }
            } finally {
                System.setOut(original)
            }
            val output = bytes.toString(Charsets.UTF_8)
            assertContains(
                output,
                "::error title=sample.ValueTest.value,file=ValueTest.kt,line=42::expected 1 but was 2",
            )
            assertContains(output, "::error title=sample.LoadTest.load::Unable to load fixture")
            assertContains(output, "::error title=SampleUITests/ScreenTests/testOpen()")
            assertEquals(5, output.lineSequence().count { it.startsWith("::error") })
            val summary = source.resolve("build/delivery/run-summary.json").readText()
            val decoded = json.decodeFromString<RunSummary>(summary)
            assertEquals(5, decoded.testFailures.size)
            assertEquals(
                TestFailure("sample.ValueTest.value", "expected 1 but was 2", "ValueTest.kt", 42),
                decoded.testFailures[1],
            )
            assertContains(summary, "sample.ValueTest.value")
            assertContains(summary, "SampleUITests/ScreenTests/testOpen()")
            assertContains(source.resolve("summary.md").readText(), "sample.LoadTest.load")
        }
    }

    private fun populate(directory: Path) {
        for (name in listOf("TEST-values.xml", "TEST-errors.xml")) directory
            .resolve(name)
            .writeText(resource("junit/$name"))
        directory.resolve("ignored.xml").writeText("invalid xml")
    }
}
