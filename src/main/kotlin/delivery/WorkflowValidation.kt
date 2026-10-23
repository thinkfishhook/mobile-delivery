package delivery

import kotlinx.serialization.json.*

private val appleSecrets = setOf("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_PRIVATE_KEY")
private val signingSecrets =
    setOf("IOS_DISTRIBUTION_P12_BASE64", "IOS_DISTRIBUTION_P12_PASSWORD", "IOS_PROFILE_BASE64")
private val toolModes =
    setOf(
        "startRun",
        "buildBeta",
        "uploadBeta",
        "finishRun",
        "sweep",
        "audit",
        "appStoreRelease",
        "appStorePreflight",
        "rolloutControl",
    )

internal fun workflowProblems(
    mode: String,
    env: Map<String, String>,
    buildEnvironment: List<String> = emptyList(),
): List<String> {
    val problems = mutableListOf<String>()
    val inputs =
        try {
            workflowInputs(env) ?: WorkflowInputs()
        } catch (_: Exception) {
            problems += "caller with: inputs must have the declared types"
            WorkflowInputs()
        }
    val operation =
        when (env["DELIVERY_VALIDATION_MODE"]) {
            "delivery" -> "startRun"
            "release" -> "appStoreRelease"
            "rollout" -> "rolloutControl"
            "maintenance" ->
                when (inputs.operation) {
                    null,
                    "sweep" -> "sweep"
                    "audit" -> "audit"
                    "app-store-preflight" -> "appStorePreflight"
                    "verify-signing" -> "verifySigning"
                    else -> {
                        problems +=
                            "caller with: operation must be sweep, audit, app-store-preflight or verify-signing"
                        "maintenance"
                    }
                }
            else -> mode
        }.let { if (mode == "validate") it else mode }
    if (mode == "validate" && operation == "validate")
        problems += "DELIVERY_VALIDATION_MODE is missing or invalid in composite setup"
    val switches =
        when (operation) {
            "startRun",
            "buildBeta",
            "uploadBeta",
            "sweep" -> listOf("IOS_DELIVERY_ENABLED")
            "appStoreRelease",
            "rolloutControl" -> listOf("IOS_APPSTORE_ENABLED")
            else -> emptyList()
        }
    switches.forEach { key ->
        if (env[key] !in setOf("true", "false"))
            problems +=
                "repository or environment variable $key must be exactly \"true\" or \"false\""
    }
    if ((operation in toolModes || mode == "validate") && inputs.branch.isNullOrBlank())
        problems += "caller with: branch is missing"
    if (mode == "validate") {
        problems += inputProblems(operation, inputs, env)
        val presence = runCatching {
            json.parseToJsonElement(env["DELIVERY_SECRET_PRESENCE"] ?: "{}").jsonObject
        }
            .getOrDefault(JsonObject(emptyMap()))
        val enabled = switches.none { env[it] == "false" }
        val required =
            (if (operation == "startRun") buildEnvironment.toSet() else emptySet()) +
                if (!enabled) emptySet()
                else
                    when (operation) {
                        "startRun" -> appleSecrets + signingSecrets + "IOS_RECEIPT_TOKEN"
                        "sweep",
                        "audit",
                        "appStoreRelease" -> appleSecrets + "IOS_RECEIPT_TOKEN"
                        "appStorePreflight",
                        "rolloutControl" -> appleSecrets
                        "verifySigning" -> signingSecrets
                        else -> emptySet()
                    }
        required.forEach { secret ->
            val present =
                if (secret in buildEnvironment) !env[secret].isNullOrBlank()
                else (presence[secret] as? JsonPrimitive)?.booleanOrNull == true
            if (!present) problems += "ios-delivery environment secret $secret is missing"
        }
    }
    return problems
}

private fun inputProblems(
    operation: String,
    inputs: WorkflowInputs,
    env: Map<String, String>,
): List<String> {
    val problems = mutableListOf<String>()
    fun requireInput(name: String, value: String?) {
        if (value.isNullOrBlank()) problems += "caller with: $name is missing"
    }
    if (operation == "startRun") {
        if (inputs.operation !in setOf(null, "deliver", "retry"))
            problems += "caller with: operation must be deliver or retry"
        inputs.sha?.let {
            if (!Regex("[a-f0-9]{40}").matches(it))
                problems += "caller with: sha must be a full commit SHA"
        }
        if (inputs.newAttempt && inputs.operation != "retry")
            problems += "caller with: new_attempt requires operation=retry"
        if (inputs.operation == "retry") {
            requireInput("sha", inputs.sha)
            requireInput("expected_attempt", inputs.expectedAttempt)
            requireInput("reason", inputs.reason)
            inputs.expectedAttempt?.let {
                if (it != "none" && runCatching { BuildNumber(it) }.isFailure)
                    problems += "caller with: expected_attempt must be a build number or none"
            }
        }
    }
    if (operation in setOf("appStoreRelease", "rolloutControl")) {
        val allowed =
            if (operation == "rolloutControl") setOf("pause", "resume", "release-to-all")
            else setOf(null, "promote")
        if (inputs.operation !in allowed)
            problems += "caller with: operation is invalid for $operation"
        if (env["GITHUB_EVENT_NAME"] != "schedule") requireInput("reason", inputs.reason)
        if (inputs.rollout !in setOf(null, "phased", "all"))
            problems += "caller with: rollout must be phased or all"
    }
    return problems
}

internal fun requireWorkflow(mode: String, env: Map<String, String>) {
    val problems = workflowProblems(mode, env)
    require(problems.isEmpty()) { problems.joinToString("\n", "Configuration problems:\n") }
}
