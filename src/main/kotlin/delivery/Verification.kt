package delivery

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class Verification(
    private val config: DeliveryConfig,
    private val commands: Commands,
    private val env: Map<String, String>,
    private val tailLines: Int = 200,
) : BuildOperations {
    init {
        require(tailLines > 0) { "Output tail line count must be positive" }
    }

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
        val totals = linkedMapOf<String, TestTotals>()
        val reportCommands = mutableMapOf<String, String>()
        try {
            config.verification.commands.forEach { command ->
                log.appendText("Running ${command.joinToString(" ")}\n")
                val before = reports.associate { (name, report) ->
                    name to report.path.reportSnapshot()
                }
                val result =
                    try {
                        commands.runResult(
                            command,
                            cwd = source,
                            env = appEnvironment(env, config.buildEnvironment),
                            log = log,
                        )
                    } catch (error: CommandError) {
                        val tail = log.useLines { lines ->
                            val last = ArrayDeque<String>()
                            lines.forEach { line ->
                                if (last.size == tailLines) last.removeFirst()
                                last.addLast(line)
                            }
                            last.joinToString("\n")
                        }
                        val failingCommand =
                            FailingCommand(command.joinToString(" "), error.exit, tail)
                        println(
                            "::group::Output tail: ${failingCommand.command.replace("\r", " ").replace("\n", " ")}"
                        )
                        // The runner recognises workflow commands even after ordinary text.
                        tail.lineSequence().forEach { line ->
                            println(line.replace("::", ": :"))
                        }
                        println("::endgroup::")
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
                            error.displayMessage().orEmpty(),
                            failingCommand = failingCommand,
                        )
                    } finally {
                        reports
                            .filter { (name, report) ->
                                report.path.exists() &&
                                    report.path.reportSnapshot() != before.getValue(name)
                            }
                            .forEach { (name, _) ->
                                reportCommands[name] = command.joinToString(" ")
                            }
                    }
                reports.filter { !it.second.path.exists() }.forEach { totals.remove(it.first) }
                val produced =
                    reports
                        .filter { (name, report) ->
                            report.path.exists() &&
                                report.path.reportSnapshot() != before.getValue(name)
                        }
                        .associate { (name, report) -> name to report.totals(commands, env) }
                totals.putAll(produced)
                val reportSummary =
                    produced.entries.joinToString("") { (name, count) ->
                        " — $name: ${count.tests} passed"
                    }
                println(
                    "✓ ${command.joinToString(" ")} — ${commandDuration(result.elapsedMillis)}$reportSummary"
                )
            }
            // Preserve the final gate: every configured report must have been produced.
            reports.forEach { (name, report) ->
                totals.getOrPut(name) { report.totals(commands, env) }
            }
            output.resolve("tests.json").writeText(json.encodeToString(totals) + "\n")
        } catch (error: Exception) {
            val failures =
                reports
                    .filter { it.second.path.exists() }
                    .flatMap { (name, report) ->
                        attemptEffect {
                            report.failures(commands, env).map {
                                it.copy(report = name, command = reportCommands[name])
                            }
                        }
                            .getOrElse {
                                log.appendText(
                                    "Could not read test failures from ${report.path}: ${it.displayMessage()}\n"
                                )
                                emptyList()
                            }
                    }
            annotateTestFailures(failures)
            log.appendText("Verification failed: ${error.displayMessage()}\n")
            if (error is VerificationFailure)
                throw VerificationFailure(
                    error.phase,
                    if (failures.isNotEmpty()) FailureKind.VALIDATION else error.kind,
                    error.displayMessage().orEmpty(),
                    failures,
                    error.failingCommand,
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
    val failingCommand: FailingCommand? = null,
) : CommandError(message)

interface BuildOperations {
    fun checkEnvironment()

    fun verify(source: Path, output: Path)

    fun archive(source: Path, output: Path, build: Build): Path
}

@Serializable data class FailingCommand(val command: String, val exit: Int?, val tail: String)

private fun Path.reportSnapshot(): Map<Path, Pair<java.nio.file.attribute.FileTime, Long>> {
    if (!exists()) return emptyMap()
    return Files.walk(this).use { paths ->
        paths.toList().associateWith {
            it.getLastModifiedTime() to if (it.isRegularFile()) it.fileSize() else 0L
        }
    }
}

internal fun commandDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    return when {
        hours > 0 ->
            "${hours}h${minutes.toString().padStart(2, '0')}m${(seconds % 60).toString().padStart(2, '0')}s"
        minutes > 0 -> "${minutes}m${(seconds % 60).toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}
