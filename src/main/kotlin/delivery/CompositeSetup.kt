package delivery

import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal fun writeEnvironment(values: Map<String, String>, output: Path) {
    values.forEach { (key, value) ->
        val delimiter = UUID.randomUUID().toString()
        output.appendText("$key<<$delimiter\n$value\n$delimiter\n")
    }
}

internal fun configureComposite(env: Map<String, String>, root: Path = Path.of(".")) {
    val name = Path.of(env.getValue("ACTION_PATH")).fileName.toString()
    if (name in setOf("ci", "testflight-deploy")) {
        val branch =
            requireNotNull(env["DELIVERY_ACTION_BRANCH"]?.takeIf { it.isNotBlank() }) {
                "Set the integration branch"
            }
        val mode = if (name == "ci") "ci" else "deploy"
        JobPolicy(env + ("DELIVERY_VALIDATION_MODE" to mode)).validate(root)
        writeEnvironment(
            mapOf(
                "DELIVERY_VALIDATION_MODE" to mode,
                "DELIVERY_COMPOSITE" to "true",
                "DELIVERY_INPUTS" to json.encodeToString(WorkflowInputs(branch = branch)),
                "DELIVERY_CI_WORKFLOW" to (env["DELIVERY_CI_WORKFLOW"] ?: "ci.yml"),
                "IOS_DELIVERY_ENABLED" to env.getValue("IOS_DELIVERY_ENABLED"),
            ),
            Path.of(env.getValue("GITHUB_ENV")),
        )
        return
    }
    if (name in setOf("verify", "select-xcode")) {
        JobPolicy(env + ("DELIVERY_VALIDATION_MODE" to name)).validate(root)
        writeEnvironment(
            mapOf("DELIVERY_VALIDATION_MODE" to name, "DELIVERY_COMPOSITE" to "true"),
            Path.of(env.getValue("GITHUB_ENV")),
        )
        if (name == "verify") {
            val config = DeliveryConfig.load(root, env, "verify")
            Path.of(env.getValue("GITHUB_OUTPUT"))
                .appendText("android=${config.androidPackages.isNotEmpty()}\n")
        }
        return
    }
    val dispatch =
        try {
            workflowInputs(mapOf("DELIVERY_INPUTS" to env.getValue("DELIVERY_ACTION_INPUTS")))
                ?: WorkflowInputs()
        } catch (_: Exception) {
            error(
                "dispatch must be a JSON object with the documented operation input types; pass toJSON(inputs)"
            )
        }
    val mode =
        requireNotNull(
            mapOf(
                "testflight" to "delivery",
                "maintenance" to "maintenance",
                "app-store-release" to "release",
                "app-store-rollout" to "rollout",
            )[name]
        ) {
            "Unsupported composite directory $name"
        }
    val branch =
        requireNotNull(env["DELIVERY_ACTION_BRANCH"]?.takeIf { it.isNotBlank() }) {
            "Set the composite branch input to the app's delivery branch"
        }
    val repository = env.getValue("DELIVERY_ACTION_REPOSITORY")
    val operation =
        if (mode == "maintenance") {
            if (env["GITHUB_EVENT_NAME"] == "schedule") "sweep" else dispatch.operation ?: "sweep"
        } else dispatch.operation
    val values =
        env.filterKeys {
            it in
                setOf(
                    "DELIVERY_ACTION_REPOSITORY",
                    "DELIVERY_ACTION_SHA",
                    "DELIVERY_ACTION_BRANCH",
                    "IOS_DELIVERY_ENABLED",
                    "IOS_APPSTORE_ENABLED",
                )
        } +
            mapOf(
                "DELIVERY_VALIDATION_MODE" to mode,
                "DELIVERY_COMPOSITE" to "true",
                "DELIVERY_OPERATION" to operation.orEmpty(),
                "DELIVERY_WORKFLOW_REPOSITORY" to repository,
                "DELIVERY_INPUTS" to
                    json.encodeToString(dispatch.copy(branch = branch, operation = operation)),
            )
    writeEnvironment(values, Path.of(env.getValue("GITHUB_ENV")))
}

internal fun compositeSummary(root: Path, env: Map<String, String>) {
    val result =
        root
            .resolve("build/delivery/run-summary.json")
            .takeIf { it.exists() }
            ?.let { json.parseToJsonElement(it.readText()).jsonObject }
    fun cell(value: String) = value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")
    val operation =
        workflowInputs(env)?.operation
            ?: when (env["DELIVERY_VALIDATION_MODE"]) {
                "ci" -> "CI verification and preparation"
                "deploy" ->
                    if (env["GITHUB_EVENT_NAME"] == "workflow_dispatch") "manual branch deployment"
                    else "automatic CI package deployment"
                else -> "automatic"
            }
    val fallback =
        if (operation == "verify-signing" && env["DELIVERY_PHASE_OUTCOME"] == "success")
            "Signing identity verified"
        else "No delivery operation ran"
    val values =
        linkedMapOf(
            "Operation" to operation,
            "Result" to
                (if (env["DELIVERY_PHASE_OUTCOME"] == "failure") "failed"
                else
                    result?.get("runHealth")?.jsonPrimitive?.content
                        ?: env["DELIVERY_PHASE_OUTCOME"].orEmpty().ifBlank { "skipped" }),
            "Delivery" to (result?.get("deliveries")?.jsonPrimitive?.content ?: fallback),
        )
    val text =
        "\n| Mobile delivery | Result |\n| --- | --- |\n" +
            values.entries.joinToString("\n") { "| ${it.key} | ${cell(it.value)} |" } +
            "\n"
    Path.of(env.getValue("GITHUB_STEP_SUMMARY")).appendText(text)
    env["GITHUB_OUTPUT"]?.let { output ->
        writeEnvironment(
            mapOf(
                "operation" to values.getValue("Operation"),
                "health" to values.getValue("Result"),
                "outcome" to values.getValue("Delivery"),
            ),
            Path.of(output),
        )
    }
}
