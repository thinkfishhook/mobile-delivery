package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class TestTotals(val tests: Int, val failures: Int, val errors: Int, val skipped: Int)

fun reportTotals(directory: Path): TestTotals {
    val reports =
        if (directory.exists()) directory.listDirectoryEntries("TEST-*.xml") else emptyList()
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

@Serializable
private data class XcresultSummary(
    val totalTestCount: Int,
    val failedTests: Int,
    val skippedTests: Int,
)

private val xcresultJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

// Totals from an Xcode result bundle (Xcode 16+ `xcresulttool get test-results summary`).
fun xcresultTotals(bundle: Path, summary: String): TestTotals {
    val result = xcresultJson.decodeFromString<XcresultSummary>(summary)
    return gate(
        bundle,
        TestTotals(result.totalTestCount, result.failedTests, 0, result.skippedTests),
    )
}

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

class Verification(
    private val config: DeliveryConfig,
    private val commands: Commands,
    private val env: Map<String, String>,
) : BuildOperations {
    override fun checkEnvironment() {
        require(
            commands.run(listOf("uname", "-m"), env = buildEnvironment(env)).trim() == "arm64"
        ) {
            "iOS verification requires an arm64 Mac"
        }
        val actualXcode =
            commands
                .run(listOf("xcodebuild", "-version"), env = buildEnvironment(env))
                .lineSequence()
                .first()
        require(actualXcode == "Xcode ${config.xcode}") {
            "Expected Xcode ${config.xcode}; found $actualXcode"
        }
    }

    private fun totals(source: Path, report: String): TestTotals {
        val path = source.resolve(report)
        if (!report.endsWith(".xcresult")) return reportTotals(path)
        if (!path.exists()) {
            throw VerificationFailure(
                "application-tests",
                FailureKind.INFRASTRUCTURE,
                "Missing test reports: $path",
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
                    path.toString(),
                    "--compact",
                ),
                env = buildEnvironment(env),
            )
        return xcresultTotals(path, summary)
    }

    override fun verify(source: Path, output: Path) {
        output.createDirectories()
        checkEnvironment()
        val log = output.resolve("verification.log")
        log.writeText("")
        val reports = config.verification.testReports
        // Reports from an earlier run must not stand in for this one.
        reports.forEach { source.resolve(it).toFile().deleteRecursively() }
        try {
            config.verification.commands.forEach { command ->
                println("Running ${command.joinToString(" ")}")
                log.appendText("Running ${command.joinToString(" ")}\n")
                try {
                    commands.run(
                        command,
                        cwd = source,
                        env = appEnvironment(env, config.buildEnvironment),
                        log = log,
                    )
                } catch (error: CommandError) {
                    val text = log.readText()
                    val validation =
                        Regex(
                                "(?m)^e: |[0-9]+ tests completed, [1-9]|Analysis failed with|" +
                                    "There were failing tests|\\*\\* TEST FAILED \\*\\*|" +
                                    "error: .*\\.swift"
                            )
                            .containsMatchIn(text)
                    throw VerificationFailure(
                        "application-checks",
                        if (validation) FailureKind.VALIDATION else FailureKind.INFRASTRUCTURE,
                        error.message.orEmpty(),
                    )
                }
            }
            val totals = reports.associateWith { totals(source, it) }
            output.resolve("tests.json").writeText(json.encodeToString(totals) + "\n")
            println(json.encodeToString(totals))
        } catch (error: Exception) {
            log.appendText("Verification failed: ${error.message}\n")
            throw error
        } finally {
            (reports + config.verification.evidence).forEach {
                val folder = source.resolve(it)
                if (folder.exists())
                    folder.toFile().copyRecursively(output.resolve(it).toFile(), overwrite = true)
            }
        }
    }

    override fun archive(source: Path, output: Path, build: Build) =
        Signing(config, commands, env).archive(source, output, build)
}

@Serializable
enum class FailureKind {
    VALIDATION,
    INFRASTRUCTURE,
}

class VerificationFailure(val phase: String, val kind: FailureKind, message: String) :
    CommandError(message)

interface BuildOperations {
    fun checkEnvironment()

    fun verify(source: Path, output: Path)

    fun archive(source: Path, output: Path, build: Build): Path
}
