package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

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

    override fun verify(source: Path, output: Path) {
        output.createDirectories()
        checkEnvironment()
        val log = output.resolve("verification.log")
        log.writeText("")
        val reports = config.verification.testReports.map { it to TestReport.resolve(source, it) }
        // Reports from an earlier run must not stand in for this one.
        reports.forEach { (_, report) -> report.path.toFile().deleteRecursively() }
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
            val totals = reports.associate { (name, report) ->
                name to report.totals(commands, env)
            }
            output.resolve("tests.json").writeText(json.encodeToString(totals) + "\n")
            println(json.encodeToString(totals))
        } catch (error: Exception) {
            val failures =
                reports
                    .map { it.second }
                    .filter { it.path.exists() }
                    .flatMap { report ->
                        attemptEffect { report.failures(commands, env) }
                            .getOrElse {
                                log.appendText(
                                    "Could not read test failures from ${report.path}: ${it.message}\n"
                                )
                                emptyList()
                            }
                    }
            annotateTestFailures(failures)
            log.appendText("Verification failed: ${error.message}\n")
            if (error is VerificationFailure)
                throw VerificationFailure(
                    error.phase,
                    if (failures.isNotEmpty()) FailureKind.VALIDATION else error.kind,
                    error.message.orEmpty(),
                    failures,
                )
            throw error
        } finally {
            (reports.map { it.first } + config.verification.evidence).forEach {
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

class VerificationFailure(
    val phase: String,
    val kind: FailureKind,
    message: String,
    val testFailures: List<TestFailure> = emptyList(),
) : CommandError(message)

interface BuildOperations {
    fun checkEnvironment()

    fun verify(source: Path, output: Path)

    fun archive(source: Path, output: Path, build: Build): Path
}
