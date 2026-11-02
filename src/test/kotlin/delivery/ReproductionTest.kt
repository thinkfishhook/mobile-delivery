package delivery

import kotlin.test.*
import org.junit.Test

class ReproductionTest {
    @Test
    fun `Xcode replaces broad filters deduplicates and ignores another command's failures`() {
        val command = listOf("xcrun", "xcodebuild", "test", "-only-testing:UITests")
        val failures =
            listOf(
                TestFailure(
                    "UITests/Login/testOpen()",
                    "failed",
                    command = command.joinToString(" "),
                ),
                TestFailure("UITests/Login/testOpen()", "duplicate"),
                TestFailure("UITests/Other/testOther()", "unrelated", command = "other command"),
                TestFailure("app.SomeTest.method", "JVM failure"),
            )
        assertEquals(
            listOf("xcrun", "xcodebuild", "test", "-only-testing:UITests/Login/testOpen"),
            TestReport.reproduce(command, failures),
        )
    }

    @Test
    fun `Gradle replaces old selectors while preserving task flags and method names`() {
        val command =
            listOf("./gradlew", ":app:jvmTest", "--tests", "app.*", "--info", "--tests=old.Test")
        val failures =
            listOf(
                TestFailure("app.Test.saves a value[1]", "failed"),
                TestFailure("UITests/Other/testOther()", "unrelated"),
            )
        assertEquals(
            listOf("./gradlew", ":app:jvmTest", "--info", "--tests", "app.Test.saves a value[1]"),
            TestReport.reproduce(command, failures),
        )
    }

    @Test
    fun `unrelated tools non testing Xcode and empty failure lists keep original commands`() {
        for (command in
            listOf(
                listOf("bash", "check.sh"),
                listOf("xcodebuild", "archive"),
                listOf("./gradlew", "check"),
            )) {
            assertEquals(command, TestReport.reproduce(command, emptyList()))
        }
        assertEquals(
            listOf("xcodebuild", "archive"),
            TestReport.reproduce(
                listOf("xcodebuild", "archive"),
                listOf(TestFailure("Tests/Suite/testFoo()", "failed")),
            ),
        )
    }
}
