package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class XcresultTest {
    @Test
    fun `real Xcode summaries distinguish passing failing and empty runs`() {
        assertEquals(TestTotals(2, 0, 0, 0), totals("passing"))
        assertEquals(TestTotals(1, 0, 0, 0), totals("passing-second"))
        assertEquals(
            FailureKind.VALIDATION,
            assertFailsWith<VerificationFailure> { totals("failing") }.kind,
        )
        assertEquals(
            FailureKind.INFRASTRUCTURE,
            assertFailsWith<VerificationFailure> { totals("zero") }.kind,
        )
    }

    @Test
    fun `verification keeps both real bundle counts without duplication`() = temp { source ->
        val output = source.resolve("evidence")
        val commands = commands(source, "passing-second")
        verifier(commands).verify(source, output)

        val reports =
            json.decodeFromString<Map<String, TestTotals>>(output.resolve("tests.json").readText())
        assertEquals(
            mapOf(
                "first.xcresult" to TestTotals(2, 0, 0, 0),
                "second.xcresult" to TestTotals(1, 0, 0, 0),
            ),
            reports,
        )
        assertEquals(3, reports.values.sumOf { it.tests })
        assertEquals(
            listOf("first.xcresult", "second.xcresult").map { source.resolve(it).toString() },
            commands.calls.filter { it.args.first() == "xcrun" }.map { it.args[6] },
        )
    }

    @Test
    fun `a passing bundle cannot mask a failing or empty second bundle`() {
        mapOf("failing" to FailureKind.VALIDATION, "zero" to FailureKind.INFRASTRUCTURE).forEach {
            (fixture, kind) ->
            temp { source ->
                val output = source.resolve("evidence")
                val failure =
                    assertFailsWith<VerificationFailure> {
                        verifier(commands(source, fixture)).verify(source, output)
                    }
                assertEquals(kind, failure.kind)
                assertFalse(output.resolve("tests.json").exists())
                assertTrue(output.resolve("first.xcresult").isDirectory())
                assertTrue(output.resolve("second.xcresult").isDirectory())
            }
        }
    }

    private fun totals(fixture: String) =
        XcresultReport(Path.of("$fixture.xcresult")).totals(resource("xcresult/$fixture.json"))

    private fun verifier(commands: Commands) =
        Verification(
            config.copy(
                xcode = "27.1",
                verification =
                    VerificationConfig(
                        commands = listOf(listOf("sample-tests")),
                        testReports = listOf("first.xcresult", "second.xcresult"),
                    ),
            ),
            commands,
            emptyMap(),
        )

    private fun commands(source: Path, second: String) =
        RecordingCommands().apply {
            respond = { call ->
                val result =
                    when (call.args.first()) {
                        "uname" -> "arm64"
                        "xcodebuild" -> "Xcode 27.1\nBuild version 27A9275"
                        "sample-tests" -> {
                            source.resolve("first.xcresult").createDirectories()
                            source.resolve("second.xcresult").createDirectories()
                            ""
                        }
                        "xcrun" -> {
                            val fixture =
                                when (Path.of(call.args[6]).fileName.toString()) {
                                    "first.xcresult" -> "passing"
                                    "second.xcresult" -> second
                                    else -> error("Unexpected result bundle: ${call.args}")
                                }
                            resource("xcresult/$fixture.json")
                        }
                        else -> error("Unexpected command: ${call.args}")
                    }
                CommandResult(0, result, "")
            }
        }
}
