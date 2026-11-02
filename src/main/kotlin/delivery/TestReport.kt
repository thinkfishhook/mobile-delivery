package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.Serializable

@Serializable
data class TestTotals(val tests: Int, val failures: Int, val errors: Int, val skipped: Int)

sealed class TestReport(val path: Path) {
    abstract fun totals(commands: Commands, env: Map<String, String>): TestTotals

    abstract fun failures(commands: Commands, env: Map<String, String>): List<TestFailure>

    companion object {
        internal fun reproduce(command: List<String>, failures: List<TestFailure>): List<String> {
            val relevant = failures.filter {
                it.command == null || it.command == command.joinToString(" ")
            }
            return JUnitReport.narrow(XcresultReport.narrow(command, relevant), relevant)
        }

        fun resolve(source: Path, report: String): TestReport {
            val path = source.resolve(report)
            return if (report.endsWith(".xcresult")) XcresultReport(path) else JUnitReport(path)
        }
    }
}

class JUnitReport(private val directory: Path) : TestReport(directory) {
    companion object {
        internal fun narrow(command: List<String>, failures: List<TestFailure>): List<String> {
            if (command.firstOrNull()?.substringAfterLast('/') !in setOf("gradle", "gradlew"))
                return command
            val tests =
                failures
                    .filter { it.report?.endsWith(".xcresult") != true && '/' !in it.test }
                    .map { it.test }
                    .distinct()
            if (tests.isEmpty()) return command
            var skip = false
            val unfiltered = command.filter {
                if (skip) {
                    skip = false
                    false
                } else if (it == "--tests") {
                    skip = true
                    false
                } else !it.startsWith("--tests=")
            }
            return unfiltered + tests.flatMap { listOf("--tests", it) }
        }
    }

    override fun totals(commands: Commands, env: Map<String, String>): TestTotals = totals()

    override fun failures(commands: Commands, env: Map<String, String>): List<TestFailure> =
        failures()

    fun failures(): List<TestFailure> =
        reports().flatMap { report ->
            val cases =
                xmlFactory()
                    .newDocumentBuilder()
                    .parse(report.toFile())
                    .getElementsByTagName("testcase")
            (0 until cases.length).mapNotNull { index ->
                val test = cases.item(index) as org.w3c.dom.Element
                val problem =
                    (0 until test.childNodes.length)
                        .map { test.childNodes.item(it) }
                        .filterIsInstance<org.w3c.dom.Element>()
                        .firstOrNull { it.tagName == "failure" || it.tagName == "error" }
                        ?: return@mapNotNull null
                val className = test.getAttribute("classname")
                val body = problem.textContent.trim()
                val frame =
                    Regex(
                            """(?m)^\s*at ${Regex.escape(className)}(?:\$[^.]+)?\.[^(]+\(([^():]+):(\d+)\)"""
                        )
                        .find(body)
                TestFailure(
                    "$className.${test.getAttribute("name")}",
                    problem.getAttribute("message").ifBlank { body.lineSequence().first() },
                    frame?.groupValues?.get(1),
                    frame?.groupValues?.get(2)?.toIntOrNull(),
                )
            }
        }

    private fun reports(): List<Path> =
        if (directory.exists()) directory.listDirectoryEntries("TEST-*.xml").sorted()
        else emptyList()

    fun totals(): TestTotals {
        val reports = reports()
        require(reports.isNotEmpty()) { "Missing test reports: $directory" }
        val attributes = listOf("tests", "failures", "errors", "skipped")
        val counts = IntArray(4)
        reports.forEach { report ->
            val suite = xmlFactory().newDocumentBuilder().parse(report.toFile()).documentElement
            attributes.forEachIndexed { index, name ->
                counts[index] += suite.getAttribute(name).ifEmpty { "0" }.toInt()
            }
        }
        return gate(directory, TestTotals(counts[0], counts[1], counts[2], counts[3]))
    }
}

class XcresultReport(private val bundle: Path) : TestReport(bundle) {
    companion object {
        internal fun narrow(command: List<String>, failures: List<TestFailure>): List<String> {
            val tool =
                if (command.firstOrNull()?.substringAfterLast('/') == "xcrun") command.getOrNull(1)
                else command.firstOrNull()
            if (tool?.substringAfterLast('/') != "xcodebuild" || "test" !in command) return command
            val tests =
                failures
                    .map { it.test.removeSuffix("()") }
                    .filter { it.split('/').size == 3 }
                    .distinct()
            if (tests.isEmpty()) return command
            return command.filterNot { it.startsWith("-only-testing:") } +
                tests.map { "-only-testing:$it" }
        }
    }

    override fun totals(commands: Commands, env: Map<String, String>): TestTotals {
        if (!bundle.exists()) {
            throw VerificationFailure(
                "application-tests",
                FailureKind.INFRASTRUCTURE,
                "Missing test reports: $bundle",
            )
        }
        val summary =
            commands.run(
                listOf(
                    "xcrun",
                    "xcresulttool",
                    "get",
                    "test-results",
                    "summary",
                    "--path",
                    bundle.toString(),
                    "--compact",
                ),
                env = buildEnvironment(env),
            )
        return totals(summary)
    }

    override fun failures(commands: Commands, env: Map<String, String>): List<TestFailure> =
        failures(
            commands.run(
                listOf(
                    "xcrun",
                    "xcresulttool",
                    "get",
                    "test-results",
                    "tests",
                    "--path",
                    bundle.toString(),
                    "--compact",
                ),
                env = buildEnvironment(env),
            )
        )

    fun failures(tests: String): List<TestFailure> {
        val failures = mutableListOf<TestFailure>()
        fun visit(node: XcresultNode, target: String, suite: String) {
            val currentTarget =
                if (node.nodeType in setOf("Unit test bundle", "UI test bundle")) node.name
                else target
            val currentSuite = if (node.nodeType == "Test Suite") node.name else suite
            if (node.nodeType == "Test Case" && node.result == "Failed") {
                val messages =
                    node.descendants().filter { it.nodeType == "Failure Message" }.toList()
                val location =
                    messages.firstNotNullOfOrNull { it.sourceLocation } ?: node.sourceLocation
                val identifier = node.nodeIdentifier ?: "$currentSuite/${node.name}"
                val test =
                    if (identifier.startsWith("$currentTarget/")) identifier
                    else "$currentTarget/$identifier"
                failures +=
                    TestFailure(
                        test,
                        messages
                            .map { it.name }
                            .distinct()
                            .joinToString("\n")
                            .ifBlank { "Test failed" },
                        location?.filePath,
                        location?.lineNumber,
                    )
            } else {
                node.children.forEach { visit(it, currentTarget, currentSuite) }
            }
        }
        xcresultJson.decodeFromString<XcresultTests>(tests).testNodes.forEach { visit(it, "", "") }
        return failures
    }

    // Xcode 16+ `xcresulttool get test-results summary` JSON.
    fun totals(summary: String): TestTotals {
        val result = xcresultJson.decodeFromString<XcresultSummary>(summary)
        return gate(
            bundle,
            TestTotals(result.totalTestCount, result.failedTests, 0, result.skippedTests),
        )
    }
}

@Serializable
private data class XcresultSummary(
    val totalTestCount: Int,
    val failedTests: Int,
    val skippedTests: Int,
)

private val xcresultJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun gate(report: Path, totals: TestTotals): TestTotals {
    val counts = listOf(totals.tests, totals.failures, totals.errors, totals.skipped)
    if (totals.tests <= 0 || counts.drop(1).any { it != 0 }) {
        throw VerificationFailure(
            "application-tests",
            if (totals.tests <= 0) FailureKind.INFRASTRUCTURE else FailureKind.VALIDATION,
            "Test gate failed: $report: $counts",
        )
    }
    return totals
}

@Serializable
data class TestFailure(
    val test: String,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val report: String? = null,
    val command: String? = null,
)

@Serializable private data class XcresultTests(val testNodes: List<XcresultNode>)

@Serializable
private data class XcresultNode(
    val nodeType: String,
    val name: String,
    val nodeIdentifier: String? = null,
    val result: String? = null,
    val sourceLocation: XcresultLocation? = null,
    val children: List<XcresultNode> = emptyList(),
) {
    fun descendants(): Sequence<XcresultNode> =
        children.asSequence().flatMap { sequenceOf(it) + it.descendants() }
}

@Serializable private data class XcresultLocation(val filePath: String, val lineNumber: Int)
