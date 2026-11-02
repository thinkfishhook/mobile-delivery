package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.json.*

internal class CompositeChecks {
    private val operationPhases =
        mapOf(
            "testflight" to
                listOf(
                    "selectXcode",
                    "startRun",
                    "setupAndroid",
                    "verify",
                    "buildBeta",
                    "uploadBeta",
                    "finishRun",
                ),
            "maintenance" to listOf("sweep", "audit", "appStorePreflight", "verifySigning"),
            "app-store-release" to listOf("appStoreRelease"),
            "app-store-rollout" to listOf("rolloutControl"),
        )
    private val supportedActions = operationPhases.keys.toList()
    private val engineIds = listOf("engine-key", "engine-cache", "engine-gradle", "engine-install")
    private val applePhases =
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
    private val receiptPhases =
        setOf("uploadBeta", "finishRun", "sweep", "audit", "appStoreRelease")
    private val signingPhases = setOf("buildBeta", "verifySigning")
    private val bindings =
        mapOf(
            "asc-key-id" to "ASC_KEY_ID",
            "asc-issuer-id" to "ASC_ISSUER_ID",
            "asc-private-key" to "ASC_PRIVATE_KEY",
            "receipt-token" to "IOS_RECEIPT_TOKEN",
            "github-token" to "GH_TOKEN",
            "distribution-p12" to "IOS_DISTRIBUTION_P12_BASE64",
            "distribution-p12-password" to "IOS_DISTRIBUTION_P12_PASSWORD",
            "provisioning-profile" to "IOS_PROFILE_BASE64",
        )

    private fun phase(step: Map<*, *>) =
        Regex("mobile-delivery ([A-Za-z]+)")
            .matchEntire(step["run"].toString().trim())
            ?.groupValues
            ?.get(1)

    fun validate(root: Path, actions: List<String> = supportedActions) {
        var engine: List<Map<*, *>>? = null
        for (action in actions) {
            val path = root.resolve("$action/action.yml")
            val file = "$action/action.yml"
            require(path.isRegularFile()) { "$file: required composite action is missing" }
            val document = workflowDocument(path.readText())
            val runs = yamlMapping(document["runs"])
            require(runs["using"] == "composite") { "$file: expected runs.using: composite" }
            val steps = workflowSteps(runs)
            val installed = engineIds.map { id ->
                requireNotNull(steps.singleOrNull { it["id"] == id }) {
                    "$file: expected one $id engine step"
                }
            }
            require(engine == null || engine == installed) {
                "$file: engine cache and installation steps must match every composite"
            }
            engine = installed
            require(installed.map(steps::indexOf).zipWithNext().all { (a, b) -> a < b }) {
                "$file: engine cache must precede installation"
            }
            val required =
                listOf("gate", "jobPolicy", "validate", "installAsc", "compositeSummary") +
                    operationPhases.getValue(action)
            required.forEach { command ->
                require(steps.count { phase(it) == command } == 1) {
                    "$file: expected one $command phase"
                }
            }
            val phases = steps.mapNotNull(::phase).filter { it != "selectXcode" }
            val selections = steps.filter { phase(it) == "selectXcode" }
            require(
                selections.isEmpty() ||
                    (action == "testflight" &&
                        selections.size == 1 &&
                        steps.indexOf(selections.single()) > steps.indexOf(installed.last()) &&
                        steps.indexOf(selections.single()) <
                            steps.indexOfFirst { phase(it) == "gate" })
            ) {
                "$file: Xcode selection belongs between engine setup and the TestFlight gate"
            }
            require(phases.take(3) == listOf("gate", "jobPolicy", "validate")) {
                "$file: gate, jobPolicy and validate must precede delivery phases"
            }
            require(steps.indexOf(installed.last()) < steps.indexOfFirst { phase(it) == "gate" }) {
                "$file: install the engine before gate"
            }
            steps.forEach { step -> validateStep(file, step) }
            if (action == "testflight") validateDelivery(file, steps)
            val upload = steps.indexOfFirst { phase(it) == "uploadBeta" }
            if (upload >= 0) {
                val prepare = steps.indexOfFirst { phase(it) == "buildBeta" }
                val retained = steps.indexOfFirst {
                    artifact(it) &&
                        (it["with"] as? Map<*, *>)?.get("name") ==
                            "\${{ steps.prepare.outputs.artifact_name }}"
                }
                require(prepare >= 0 && retained > prepare && retained < upload) {
                    "$file: retain the prepared package before uploadBeta"
                }
                require(yamlMapping(steps[retained]["with"])["if-no-files-found"] == "error") {
                    "$file: prepared package retention needs if-no-files-found: error"
                }
            }
            val finish = steps.indexOfFirst { phase(it) == "finishRun" }
            if (action == "maintenance") {
                require(
                    steps.any {
                        artifact(it) &&
                            it["if"] == "always() && env.DELIVERY_OPERATION != 'verify-signing'"
                    }
                ) {
                    "$file: always retain maintenance evidence except for verify-signing"
                }
            }
            if ("appStoreRelease" in phases) {
                require(steps.any { artifact(it) && it["if"] == "always()" }) {
                    "$file: always retain App Store release evidence"
                }
            }
            if (finish >= 0) {
                val evidence = steps.indexOfFirst { artifact(it) && it["if"] == "always()" }
                require(evidence >= 0 && evidence < finish) {
                    "$file: always retain evidence before the final sweep"
                }
            }
        }
    }

    // Validate the caller, rather than maintaining a second copy of the permission contract.
    fun validateCaller(root: Path, file: String, job: String, action: String) {
        val mode =
            mapOf(
                    "testflight" to "delivery",
                    "maintenance" to "maintenance",
                    "app-store-release" to "release",
                    "app-store-rollout" to "rollout",
                )
                .getValue(action.removePrefix("./"))
        JobPolicy(
                mapOf(
                    "GITHUB_WORKFLOW_REF" to
                        "local/repository/.github/workflows/$file@refs/heads/trunk",
                    "GITHUB_JOB" to job,
                    "DELIVERY_VALIDATION_MODE" to mode,
                )
            )
            .validate(root)
    }

    private fun artifact(step: Map<*, *>) =
        step["uses"].toString().startsWith("actions/upload-artifact@")

    private fun validateDelivery(file: String, steps: List<Map<*, *>>) {
        fun command(name: String) =
            requireNotNull(steps.singleOrNull { phase(it) == name }) {
                "$file: expected one $name phase"
            }
        fun condition(step: Map<*, *>) =
            step["if"].toString().trim().removePrefix("\${{").removeSuffix("}}").trim()
        val guards =
            mapOf(
                "startRun" to
                    ("start" to
                        "steps.gate.outputs.run == 'true' || steps.gate.outputs.verify == 'true'"),
                "verify" to ("verify" to "steps.start.outputs.operation == 'verify'"),
                "buildBeta" to ("prepare" to "steps.start.outputs.operation == 'deliver'"),
                "uploadBeta" to ("upload" to "steps.prepare.outputs.publish == 'true'"),
            )
        guards.forEach { (phase, expected) ->
            val step = command(phase)
            require(step["id"] == expected.first && condition(step) == expected.second) {
                "$file: $phase must use id ${expected.first} and if: ${expected.second}"
            }
        }
        val packageStep =
            requireNotNull(steps.singleOrNull { it["id"] == "package" && artifact(it) }) {
                "$file: expected the package artifact step"
            }
        require(condition(packageStep) == "steps.prepare.outputs.retain == 'true'") {
            "$file: retain the package when prepare.outputs.retain is true"
        }
        val finish = command("finishRun")
        require(
            condition(finish) ==
                "!cancelled() && steps.start.outcome == 'success' && (steps.start.outputs.operation == 'deliver' || steps.start.outputs.operation == 'sweep')"
        ) {
            "$file: finishRun must run after a successful start, including failed delivery steps, unless cancelled"
        }
        val observed =
            listOf("android-setup", "android", "verify", "prepare", "package", "upload", "evidence")
        require(observed.all { id -> steps.count { it["id"] == id } == 1 }) {
            "$file: give each observed delivery step its unique id: $observed"
        }
        val expected =
            observed.joinToString(" || ", "\${{ ", " }}") { "steps.$it.outcome == 'failure'" }
        require((finish["env"] as? Map<*, *>)?.get("IOS_EXECUTION_FAILED") == expected) {
            "$file: IOS_EXECUTION_FAILED must derive from the composite's own step outcomes, including artifact failures"
        }
    }

    private fun credentialReferences(value: String): Set<String> {
        val named = Regex("\\binputs\\s*\\.\\s*([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
        return buildSet {
            expressionBodies(value).forEach { body ->
                require(
                    !Regex("\\binputs\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(named.replace(body, ""))
                ) {
                    "Use named composite inputs, never indirect or wholesale inputs access"
                }
                require(!Regex("\\bsecrets\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                    "Composite steps receive named credential inputs, never the secrets context"
                }
                named
                    .findAll(body)
                    .map { it.groupValues[1].lowercase() }
                    .filter { it in bindings }
                    .forEach(::add)
            }
        }
    }

    private fun githubCredential(value: String): Boolean =
        expressionBodies(value).any { body ->
            val context = "(?<![A-Za-z0-9_.-])github\\b"
            val named = Regex("$context\\s*\\.\\s*([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE)
            require(
                !Regex(context, RegexOption.IGNORE_CASE).containsMatchIn(named.replace(body, ""))
            ) {
                "Use named GitHub context properties, never indirect or wholesale GitHub context access"
            }
            named.findAll(body).any { it.groupValues[1].equals("token", ignoreCase = true) }
        }

    private fun validateStep(file: String, step: Map<*, *>) {
        val phase = phase(step)
        val location = "$file step ${phase ?: step["id"] ?: step["name"] ?: step["uses"]}"
        step["uses"]?.toString()?.let { uses ->
            require(Regex("[\\w./-]+@[a-f0-9]{40}").matches(uses)) {
                "$location: pin third-party actions to a full SHA"
            }
            if (uses.startsWith("actions/checkout@"))
                require(
                    (step["with"] as? Map<*, *>)?.let {
                        it["persist-credentials"] == false && it["fetch-depth"] == 0
                    } == true
                ) {
                    "$location: checkout needs full history and must not persist credentials"
                }
        }
        if (artifact(step))
            require((step["with"] as? Map<*, *>)?.get("retention-days") == 90) {
                "$location: retain artifacts for 90 days"
            }
        require(credentialReferences(step.filterKeys { it != "env" }.toString()).isEmpty()) {
            "$location: bind credentials only in the permitted step environment"
        }
        require(!githubCredential(step.filterKeys { it != "env" }.toString())) {
            "$location: GitHub credentials belong only in the permitted step environment"
        }
        val env = (step["env"] as? Map<*, *>).orEmpty()
        env.forEach { (key, raw) ->
            val value = raw.toString()
            val sources = credentialReferences(value)
            if (phase == "validate" && key == "DELIVERY_SECRET_PRESENCE") {
                val encoded =
                    Regex("\\$\\{\\{ inputs\\.([A-Za-z0-9_-]+) != '' }}").replace(value) { match ->
                        require(match.groupValues[1] in bindings) {
                            "$location: unknown credential presence input"
                        }
                        "true"
                    }
                require(
                    json.parseToJsonElement(encoded).jsonObject.values.all {
                        (it as? JsonPrimitive)?.booleanOrNull != null
                    }
                ) {
                    "$location: validation receives only credential presence booleans"
                }
            } else {
                sources.forEach { source ->
                    val allowed =
                        when (source) {
                            "receipt-token" -> receiptPhases
                            "distribution-p12",
                            "distribution-p12-password",
                            "provisioning-profile" -> signingPhases
                            else -> applePhases
                        }
                    require(
                        phase in allowed &&
                            key == bindings[source] &&
                            value == "\${{ inputs.$source }}"
                    ) {
                        "$location: $source may bind only ${bindings[source]} on phases $allowed"
                    }
                }
                if (githubCredential(value))
                    require(phase in applePhases && key == "GH_TOKEN") {
                        "$location: GitHub credentials belong only on the permitted API phases"
                    }
            }
        }
    }
}
