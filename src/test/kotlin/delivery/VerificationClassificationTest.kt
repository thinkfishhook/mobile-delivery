package delivery

import java.io.IOException
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class VerificationClassificationTest {
    private data class Case(
        val name: String,
        val log: String,
        val kind: FailureKind,
        val report: String? = null,
        val exception: Exception? = null,
    )

    // Neutral tool-output snippets; test report bodies are synthetic JUnit XML.
    private val failing =
        """<testsuite tests="1" failures="1"><testcase classname="ExampleTest" name="saves"><failure message="expected true"/></testcase></testsuite>"""
    private val passing =
        """<testsuite tests="1"><testcase classname="ExampleTest" name="saves"/></testsuite>"""

    @Test
    fun `characterize existing failure classification`() {
        listOf(
                Case(
                    "Xcode test",
                    "Test Case '-[ExampleTests saves]' failed (0.1 seconds).\n** TEST FAILED **",
                    FailureKind.VALIDATION,
                    failing,
                ),
                Case(
                    "Gradle test",
                    "1 tests completed, 1 failed\n> There were failing tests. See the report at: file:///fixture/tests/index.html",
                    FailureKind.VALIDATION,
                    failing,
                ),
                Case(
                    "Swift compile",
                    "/fixture/App.swift:12:4: error: cannot find 'value' in scope",
                    FailureKind.VALIDATION,
                ),
                Case(
                    "Kotlin compile",
                    "e: file:///fixture/App.kt:12:4 Unresolved reference 'value'.",
                    FailureKind.VALIDATION,
                ),
                Case(
                    "detekt analysis",
                    "> Analysis failed with 3 weighted issues.",
                    FailureKind.VALIDATION,
                ),
                Case(
                    "simulator crash",
                    "Failed to establish communication with the test runner. Simulator device failed to launch.",
                    FailureKind.INFRASTRUCTURE,
                ),
                Case(
                    "timeout",
                    "xcodebuild timed out",
                    FailureKind.INFRASTRUCTURE,
                    exception = CommandError("xcodebuild timed out"),
                ),
            )
            .forEach(::checkCase)
    }

    @Test
    fun `report evidence wins over banners and ordinary app output`() {
        listOf(
                Case(
                    "app printed Xcode banner",
                    "2026-11-02 ExampleApp[123:456] ** TEST FAILED **",
                    FailureKind.INFRASTRUCTURE,
                    passing,
                ),
                Case(
                    "Gradle banner without reports",
                    "There were failing tests",
                    FailureKind.INFRASTRUCTURE,
                ),
                Case(
                    "summary echoed by app",
                    "ExampleApp: 10 tests completed, 1 failed",
                    FailureKind.INFRASTRUCTURE,
                    passing,
                ),
                Case("quiet failing report", "Task failed", FailureKind.VALIDATION, failing),
                Case(
                    "Swift compile",
                    "/fixture/App.swift:12:4: error: cannot find 'value' in scope",
                    FailureKind.VALIDATION,
                ),
                Case(
                    "Swift warning",
                    "/fixture/App.swift:12:4: warning: value was never used",
                    FailureKind.INFRASTRUCTURE,
                    passing,
                ),
                Case(
                    "app quoting Swift error",
                    "ExampleApp: error: expected fixture.swift",
                    FailureKind.INFRASTRUCTURE,
                    passing,
                ),
                Case(
                    "missing executable",
                    "",
                    FailureKind.INFRASTRUCTURE,
                    exception =
                        IOException(
                            "Cannot run program fixture-check: error=2, No such file or directory"
                        ),
                ),
            )
            .forEach(::checkCase)
    }

    @Test
    fun `pure classification table excludes quoted compiler and analysis text`() {
        val cases =
            listOf(
                Case(
                    "quoted Kotlin diagnostic",
                    "ExampleApp: e: file:///fixture/App.kt:12:4 Unresolved reference",
                    FailureKind.INFRASTRUCTURE,
                ),
                Case(
                    "quoted analysis diagnostic",
                    "ExampleApp: Analysis failed with 3 weighted issues.",
                    FailureKind.INFRASTRUCTURE,
                ),
                Case(
                    "analysis prose",
                    "ExampleApp: Analysis failed with a network error",
                    FailureKind.INFRASTRUCTURE,
                ),
                Case(
                    "passing report and app banner",
                    "** TEST FAILED **",
                    FailureKind.INFRASTRUCTURE,
                ),
                Case(
                    "Kotlin compiler",
                    "e: file:///fixture/App.kt:12:4 Unresolved reference",
                    FailureKind.VALIDATION,
                ),
                Case(
                    "Swift compiler",
                    "/fixture/App.swift:12:4: error: cannot find 'value' in scope",
                    FailureKind.VALIDATION,
                ),
                Case(
                    "detekt findings",
                    "   > Analysis failed with 3 weighted issues.",
                    FailureKind.VALIDATION,
                ),
                Case("no output", "", FailureKind.INFRASTRUCTURE),
            )
        cases.forEach {
            assertEquals(it.kind, classifyVerificationFailure(it.log, emptyList()), it.name)
        }
        assertEquals(
            FailureKind.VALIDATION,
            classifyVerificationFailure(
                "test runner disconnected",
                listOf(TestFailure("Example.saves", "expected true")),
            ),
        )
    }

    private fun checkCase(case: Case) = temp { root ->
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    when (call.args.first()) {
                        "uname" -> CommandResult(0, "arm64", "")
                        "xcodebuild" -> CommandResult(0, "Xcode ${config.xcode}", "")
                        else -> {
                            call.log!!.appendText(case.log + "\n")
                            case.report?.let {
                                root
                                    .resolve("junit")
                                    .createDirectories()
                                    .resolve("TEST-example.xml")
                                    .writeText(it)
                            }
                            case.exception?.let { throw it }
                            CommandResult(1, "", "")
                        }
                    }
                }
            }
        val settings =
            config.copy(
                verification =
                    VerificationConfig(
                        commands = listOf(listOf("fixture-check")),
                        testReports = listOf("junit"),
                    )
            )
        val error =
            assertFailsWith<VerificationFailure>(case.name) {
                Verification(settings, commands, emptyMap()).verify(root, root.resolve("evidence"))
            }
        assertEquals(case.kind, error.kind, case.name)
    }
}
