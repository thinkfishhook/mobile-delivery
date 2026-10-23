package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        runTask(
            args,
            Path.of(System.getProperty("user.dir")).toAbsolutePath(),
            System.getenv(),
            ProcessCommands(),
        )
    } catch (error: Exception) {
        System.err.println("Delivery stopped: ${error.message}")
        exitProcess(1)
    }
}

internal fun runTask(
    args: Array<String>,
    root: Path,
    env: Map<String, String>,
    commands: Commands,
) {
    val mode = args.firstOrNull() ?: error("Specify a delivery task")
    when (mode) {
        "configureComposite" -> {
            configureComposite(env)
            return
        }
        "compositeSummary" -> {
            compositeSummary(root, env)
            return
        }
        "jobPolicy" -> {
            JobPolicy(env).validate(root)
            return
        }
        "gate" -> {
            gate(env)
            return
        }
        // Checks this repository's composites and CI workflows; it needs no app configuration.
        "workflowChecks" -> {
            WorkflowChecks().validate(root)
            commands.run(listOf("git", "diff", "--check"), cwd = root)
            println("Composites and CI workflows keep the delivery safety properties.")
            return
        }
        "installAsc" -> {
            installAsc(Toolchain.bundled, commands, env)
            return
        }
    }
    val config = DeliveryConfig.load(root, env, mode)
    when (mode) {
        "validate" ->
            println("Delivery configuration, inputs, variables and secret presence validated.")
        "setupAndroid" -> setupAndroid(root, config, commands, env)
        "selectXcode" -> selectXcode(config, commands, env)
        "verifySigning" ->
            Signing(config, commands, env).withIdentity(diagnostic = true) {
                println("Signing identity verified; no archive or upload performed.")
            }
        "verify" ->
            Verification(config, commands, env).verify(root, root.resolve("build/ci-verification"))
        "bootstrap" -> bootstrap(args.drop(1), config, commands, env)
        else -> DeliveryTool(root, config, commands, env).run(mode)
    }
}

@kotlinx.serialization.Serializable
data class WorkflowInputs(
    val branch: String? = null,
    val operation: String? = null,
    val sha: String? = null,
    @kotlinx.serialization.SerialName("expected_attempt") val expectedAttempt: String? = null,
    val reason: String? = null,
    val rollout: String? = null,
    @kotlinx.serialization.SerialName("new_attempt") val newAttempt: Boolean = false,
)

@kotlinx.serialization.Serializable
data class WorkflowEvent(val schedule: String? = null, val inputs: WorkflowInputs? = null)

private val inputJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

// Composite setup forwards typed dispatch values through DELIVERY_INPUTS.
// Blank fields from triggers that do not supply them are treated as absent.
fun workflowInputs(env: Map<String, String>): WorkflowInputs? =
    env["DELIVERY_INPUTS"]?.let { text ->
        val raw = inputJson.decodeFromString<WorkflowInputs>(text)
        fun String?.value() = this?.takeIf { it.isNotBlank() }
        WorkflowInputs(
            raw.branch.value(),
            raw.operation.value(),
            raw.sha.value(),
            raw.expectedAttempt.value(),
            raw.reason.value(),
            raw.rollout.value(),
            raw.newAttempt,
        )
    }

internal fun requireActionsWriter(env: Map<String, String>, branch: String) {
    require(env["GITHUB_ACTIONS"] == "true" && env["GITHUB_REF"] == "refs/heads/$branch") {
        "Delivery tasks run only in queued $branch Actions jobs"
    }
}
