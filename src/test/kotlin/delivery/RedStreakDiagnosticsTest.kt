package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class RedStreakDiagnosticsTest {
    private val commit =
        RedStreakCommit("a".repeat(40), "Red", "https://github.com/owner/repo/actions/runs/10", 2)

    private fun details(command: List<String>) =
        RedStreakDetails(
            FailingCommand(command.joinToString(" "), 65, "Assertion failed\nExpected saved value"),
            listOf(command),
            "trunk",
            commit.runUrl + "/attempts/2",
            "ios-delivery-10-2",
            "https://github.com/owner/repo/actions/runs/10/artifacts/99",
            listOf("delivery/${commit.sha}/verification/build/UITests.xcresult"),
        )

    @Test
    fun `finishRun uses actual summary config artifact and archive paths`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.verificationFailure =
            VerificationFailure(
                "application-checks",
                FailureKind.VALIDATION,
                "Tests failed",
                listOf(TestFailure("app.SettingsTest.saves", "failed")),
            )
        h.run("startRun", "push").getOrThrow()
        assertTrue(h.run("buildBeta", "push").isFailure)
        val summary =
            json.decodeFromString<RunSummary>(
                root.resolve("build/delivery/run-summary.json").readText()
            )
        val original = h.commands.respond!!
        h.commands.respond = { call ->
            if (call.args.getOrNull(2)?.contains("actions/runs/10/artifacts") == true)
                CommandResult(0, """{"artifacts":[{"id":99,"name":"ios-delivery-10-2"}]}""", "")
            else original(call)
        }
        h.run("finishRun", "push", attempt = 2, extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true"))
            .getOrThrow()
        val body = h.issues.single { ":red-streak -->" in it.string("body") }.string("body")
        assertContains(body, summary.failingCommand!!.command)
        assertContains(body, "Exit code: 1")
        assertContains(body, summary.failingCommand.tail)
        assertContains(body, "--tests app.SettingsTest.saves")
        assertContains(
            body,
            "[ios-delivery-10-2](https://github.com/owner/repo/actions/runs/10/artifacts/99)",
        )
        assertContains(
            body,
            "delivery/${h.tip}/verification/${config.verification.testReports.first()}",
        )
    }

    @Test
    fun `output containing markdown fences remains inside a code block`() {
        val detail =
            details(listOf("bash", "check.sh"))
                .copy(failingCommand = FailingCommand("bash check.sh", 1, "```\ntext\n```"))
        val body = renderRedStreak(listOf(commit), emptyList(), details = detail)
        assertContains(body, "````text\n```\ntext\n```\n````")
    }

    @Test
    fun `xcode renderer shows command exit collapsed tail and two narrowed UI tests`() {
        val command =
            listOf(
                "xcodebuild",
                "test",
                "-scheme",
                "My App",
                "-destination",
                "platform=iOS Simulator,name=iPhone 17",
            )
        val failures =
            listOf(
                TestFailure("AppUITests/LoginTests/testLogin()", "failed"),
                TestFailure("AppUITests/SettingsTests/testSave()", "failed"),
            )
        val body = renderRedStreak(listOf(commit), failures, details = details(command))
        assertContains(body, "## Failing command")
        assertContains(body, command.joinToString(" "))
        assertContains(body, "Exit code: 65")
        assertContains(body, "<details>")
        assertContains(body, "Assertion failed\nExpected saved value")
        assertContains(body, "## Reproduce")
        assertContains(
            body,
            "xcodebuild test -scheme 'My App' -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:AppUITests/LoginTests/testLogin -only-testing:AppUITests/SettingsTests/testSave",
        )
    }

    @Test
    fun `gradle renderer adds one tests filter per JVM failure`() {
        val command = listOf("./gradlew", "composeApp:jvmTest", "--stacktrace")
        val failures =
            listOf(
                TestFailure("app.SettingsTest.saves", "failed"),
                TestFailure("app.LaunchTest.opens", "failed"),
            )
        val body = renderRedStreak(listOf(commit), failures, details = details(command))
        assertContains(
            body,
            "./gradlew composeApp:jvmTest --stacktrace --tests app.SettingsTest.saves --tests app.LaunchTest.opens",
        )
    }

    @Test
    fun `non test script is reproduced unchanged without narrowing`() {
        val command = listOf("bash", "internal/check.sh", "--strict")
        val body = renderRedStreak(listOf(commit), emptyList(), details = details(command))
        assertContains(body, "## Reproduce\n\n```sh\nbash internal/check.sh --strict\n```")
        assertFalse("--tests" in body)
        assertFalse("-only-testing" in body)
    }

    @Test
    fun `evidence links exact run attempt artifact and result bundle paths plus completion condition`() {
        val body =
            renderRedStreak(
                listOf(commit),
                emptyList(),
                details = details(listOf("./gradlew", "check")),
            )
        assertContains(body, "## Evidence")
        assertContains(body, "[Run](https://github.com/owner/repo/actions/runs/10/attempts/2)")
        assertContains(
            body,
            "[ios-delivery-10-2](https://github.com/owner/repo/actions/runs/10/artifacts/99)",
        )
        assertContains(body, "delivery/${commit.sha}/verification/build/UITests.xcresult")
        assertContains(body, "## Done when")
        assertContains(
            body,
            "a commit on `trunk` delivers to TestFlight, which closes this issue automatically.",
        )
    }
}
