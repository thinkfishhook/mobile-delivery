package delivery

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

open class CommandError(
    message: String,
    val exit: Int? = null,
    val log: Path? = null,
) : RuntimeException(message)

internal fun Throwable.displayMessage(): String? = message?.let { text ->
    text + ((this as? CommandError)?.log?.let { "; see ${it.fileName}" } ?: "")
}

class ArtifactError(message: String) : CommandError(message)

data class CommandResult(val exit: Int, val stdout: String, val stderr: String)

interface Commands {
    fun execute(
        args: List<String>,
        cwd: Path? = null,
        env: Map<String, String> = System.getenv(),
        input: String? = null,
        log: Path? = null,
        timeoutSeconds: Long = 21600,
    ): CommandResult
}

class ProcessCommands : Commands {
    override fun execute(
        args: List<String>,
        cwd: Path?,
        env: Map<String, String>,
        input: String?,
        log: Path?,
        timeoutSeconds: Long,
    ): CommandResult {
        val temp = createTempDirectory("delivery-command-")
        try {
            val stdout = log ?: temp.resolve("stdout")
            val stderr = temp.resolve("stderr")
            val builder = ProcessBuilder(args).directory(cwd?.toFile())
            builder.environment().apply {
                clear()
                putAll(env)
            }
            if (log != null) builder.redirectErrorStream(true)
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(stdout.toFile()))
            builder.redirectError(stderr.toFile())
            val process = builder.start()
            process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                throw CommandError("${args.first()} timed out")
            }
            return CommandResult(
                process.exitValue(),
                if (log == null) stdout.readText() else "",
                stderr.readText(),
            )
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}

fun Commands.run(
    args: List<String>,
    cwd: Path? = null,
    env: Map<String, String> = System.getenv(),
    input: String? = null,
    log: Path? = null,
    timeoutSeconds: Long = 21600,
): String {
    val result = execute(args, cwd, env, input, log, timeoutSeconds)
    if (result.exit != 0)
        throw CommandError(
            "${args.first()} failed (exit ${result.exit})",
            exit = result.exit,
            log = log,
        )
    return result.stdout
}

fun buildEnvironment(env: Map<String, String> = System.getenv()): Map<String, String> {
    val allowed =
        setOf(
            "PATH",
            "HOME",
            "USER",
            "LOGNAME",
            "SHELL",
            "TMPDIR",
            "TMP",
            "TEMP",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "TZ",
            "TERM",
            "JAVA_HOME",
            "ANDROID_HOME",
            "ANDROID_SDK_ROOT",
            "DEVELOPER_DIR",
            "GRADLE_USER_HOME",
            "KONAN_DATA_DIR",
            "XDG_CACHE_HOME",
            "SDKMAN_DIR",
            "SDKMAN_CANDIDATES_DIR",
            "CI",
            "GITHUB_ACTIONS",
            "GITHUB_WORKSPACE",
        )
    return env.filterKeys { it in allowed }
}

fun toolEnvironment(env: Map<String, String>, vararg names: String): Map<String, String> =
    buildEnvironment(env) + env.filterKeys { it in names }

private fun reservedBuildName(name: String): Boolean =
    Regex("(ASC|IOS|GH|GITHUB|ACTIONS|RUNNER)_.*|DELIVERY_.*").matches(name) ||
        name in buildEnvironment(mapOf(name to ""))

fun requireBuildEnvironmentNames(names: List<String>) {
    require(names.distinct() == names) { "build_environment must not repeat names" }
    names.forEach { name ->
        require(Regex("[A-Z_][A-Z0-9_]*").matches(name)) { "Invalid build_environment name: $name" }
        require(!reservedBuildName(name)) { "build_environment cannot set $name" }
    }
}

fun appEnvironment(
    env: Map<String, String>,
    names: List<String> = emptyList(),
): Map<String, String> {
    requireBuildEnvironmentNames(names)
    val values = names.associateWith { name ->
        requireNotNull(env[name]?.takeIf { it.isNotBlank() }) {
            "Missing app build environment value: $name"
        }
    }
    return buildEnvironment(env) + values
}

fun workflowUrl(env: Map<String, String>) =
    "https://github.com/${env["GITHUB_REPOSITORY"] ?: "local"}/actions/runs/${env["GITHUB_RUN_ID"] ?: "local"}"

fun Path.appendLineCreating(value: String) {
    writeText(
        value + "\n",
        Charsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND,
    )
}

// Process termination is not a recoverable service error. Test kill signals and JVM Errors escape.
inline fun <T> attemptEffect(action: () -> T): Result<T> =
    try {
        Result.success(action())
    } catch (error: Exception) {
        Result.failure(error)
    }
