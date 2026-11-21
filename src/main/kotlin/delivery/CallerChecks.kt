package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.json.*

/** Structural validation of an app checkout, without a copy of this tool's action sources. */
class CallerChecks {
    fun validate(root: Path, repository: String = "thinkfishhook/mobile-delivery") {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) {
            "callerChecks requires an owner/repository name"
        }
        val pins = mutableSetOf<String>()
        val configuration = root.resolve(".github/delivery.json")
        val buildNames =
            if (configuration.exists()) {
                Json.parseToJsonElement(configuration.readText())
                    .jsonObject["build_environment"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
            } else emptyList()
        requireBuildEnvironmentNames(buildNames)
        val actions =
            setOf(
                "ci",
                "testflight-deploy",
                "ai-review",
                "report-failure",
                "verify",
                "select-xcode",
                "testflight",
                "maintenance",
                "app-store-release",
                "app-store-rollout",
            )
        val directory = root.resolve(".github/workflows")
        require(directory.isDirectory()) { "Missing .github/workflows" }
        for (path in
            directory.listDirectoryEntries().filter { it.extension in setOf("yml", "yaml") }) {
            val document = workflowDocument(path.readText())
            for ((name, value) in yamlMapping(document["jobs"])) {
                val job = yamlMapping(value)
                for (step in workflowSteps(job)) {
                    val reference = step["uses"]?.toString() ?: continue
                    if (!reference.startsWith("$repository/")) continue
                    val match =
                        requireNotNull(
                            Regex("${Regex.escape(repository)}/([a-z-]+)@([a-f0-9]{40})")
                                .matchEntire(reference)
                        ) {
                            "${path.name}: pin shared actions to full immutable SHAs"
                        }
                    val action = match.groupValues[1]
                    require(action in actions) { "${path.name}: unknown shared action $action" }
                    pins += match.groupValues[2]
                    require(
                        document["env"] == null &&
                            job["env"] == null &&
                            (step["env"] as? Map<*, *>).orEmpty().keys.all { it in buildNames }
                    ) {
                        "${path.name}: credentials belong to named action inputs"
                    }
                    when (action) {
                        "ai-review" -> validateHostedReviewWorkflow(document, name.toString())
                        "report-failure" -> validateReportingJob(document, name.toString())
                        else ->
                            CompositeChecks()
                                .validateCaller(root, path.name, name.toString(), "./$action")
                    }
                    if (action in setOf("verify", "select-xcode")) {
                        require(
                            secretReferences(job.toString()).all { it in buildNames } &&
                                !Regex("github[.]token|GH_TOKEN|IOS_RECEIPT_TOKEN")
                                    .containsMatchIn(job.toString())
                        ) {
                            "${path.name}: read-only verification must not receive credentials"
                        }
                    }
                }
            }
        }
        val localActions = root.resolve(".github/actions")
        if (localActions.isDirectory()) {
            localActions
                .walk()
                .filter { it.isRegularFile() && it.name in setOf("action.yml", "action.yaml") }
                .forEach { path ->
                    val document = workflowDocument(path.readText())
                    workflowSteps(yamlMapping(document["runs"])).forEach { step ->
                        val reference = step["uses"]?.toString().orEmpty()
                        if (reference.startsWith("$repository/")) {
                            val match =
                                requireNotNull(
                                    Regex("${Regex.escape(repository)}/([a-z-]+)@([a-f0-9]{40})")
                                        .matchEntire(reference)
                                ) {
                                    "${path.name}: pin nested shared actions to full immutable SHAs"
                                }
                            require(match.groupValues[1] in actions) {
                                "${path.name}: unknown nested shared action"
                            }
                            pins += match.groupValues[2]
                        }
                    }
                }
        }
        require(pins.size == 1) {
            "Use one coherent full SHA for all shared actions; found ${pins.size} pins"
        }
    }
}
