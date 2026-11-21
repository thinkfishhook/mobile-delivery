package delivery

import java.nio.file.Path
import kotlin.io.path.*

internal class SplitCompositeChecks {
    private val phases =
        mapOf(
            "ci" to
                listOf(
                    "selectSplit",
                    "selectXcode",
                    "setupAndroid",
                    "verifySource",
                    "installAsc",
                    "preparePackage",
                    "compositeSummary",
                ),
            "testflight-deploy" to
                listOf(
                    "selectSplit",
                    "selectXcode",
                    "setupAndroid",
                    "installAsc",
                    "preparePackage",
                    "deployPackage",
                    "compositeSummary",
                ),
        )
    private val bindings =
        mapOf(
            "github-token" to "GH_TOKEN",
            "receipt-token" to "IOS_RECEIPT_TOKEN",
            "asc-key-id" to "ASC_KEY_ID",
            "asc-issuer-id" to "ASC_ISSUER_ID",
            "asc-private-key" to "ASC_PRIVATE_KEY",
            "distribution-p12" to "IOS_DISTRIBUTION_P12_BASE64",
            "distribution-p12-password" to "IOS_DISTRIBUTION_P12_PASSWORD",
            "provisioning-profile" to "IOS_PROFILE_BASE64",
        )

    private fun phase(step: Map<*, *>) =
        Regex("mobile-delivery ([A-Za-z]+)")
            .matchEntire(step["run"].toString().trim())
            ?.groupValues
            ?.get(1)

    fun validate(root: Path) {
        if (phases.keys.none { root.resolve("$it/action.yml").exists() }) return
        for ((action, expected) in phases) {
            val file = root.resolve("$action/action.yml")
            require(file.isRegularFile()) { "Missing split action $action" }
            val doc = workflowDocument(file.readText())
            val steps = workflowSteps(yamlMapping(doc["runs"]))
            require(
                yamlMapping(doc["runs"])["using"] == "composite" &&
                    steps.mapNotNull(::phase) == expected
            ) {
                "$action: split operation phases must remain ordered and isolated"
            }
            val inputs = yamlMapping(doc["inputs"])
            require(
                inputs.keys.none {
                    Regex("(?i)artifact|run.?id|archive|ipa").containsMatchIn(it.toString())
                }
            ) {
                "$action: no manual artifact-selection inputs"
            }
            for (step in steps) {
                val operation = phase(step)
                step["uses"]?.let {
                    require(Regex("[\\w./-]+@[a-f0-9]{40}").matches(it.toString())) {
                        "$action: pin third-party actions"
                    }
                }
                require(secretReferences(step.toString()).isEmpty()) {
                    "$action: composites receive named inputs, not the secrets context"
                }
                val env = (step["env"] as? Map<*, *>).orEmpty()
                require(
                    operation != "verifySource" ||
                        env.keys.none { it.toString() in bindings.values }
                ) {
                    "Verification cannot receive signing/upload credentials"
                }
                for ((input, variable) in bindings) {
                    val reference = Regex("inputs\\.${Regex.escape(input)}(?![A-Za-z0-9_-])")
                    val references = env.filterValues { reference.containsMatchIn(it.toString()) }
                    val allowed =
                        when (input) {
                            "github-token" ->
                                setOf("selectSplit", "preparePackage", "deployPackage")
                            "receipt-token" -> setOf("deployPackage")
                            "distribution-p12",
                            "distribution-p12-password",
                            "provisioning-profile" -> setOf("preparePackage")
                            else -> setOf("preparePackage", "deployPackage")
                        }
                    require(
                        references.all { (key, value) ->
                            key == variable &&
                                value == "\${{ inputs.$input }}" &&
                                operation in allowed
                        }
                    ) {
                        "$action: credential $input escaped its operation boundary"
                    }
                    require(
                        !reference.containsMatchIn(step.filterKeys { it != "env" }.toString())
                    ) {
                        "$action: credentials belong only in phase environments"
                    }
                }
                if (step["uses"].toString().startsWith("actions/upload-artifact@"))
                    require(
                        yamlMapping(step["with"])["retention-days"] ==
                            PREPARED_PACKAGE_RETENTION_DAYS
                    ) {
                        "$action: bounded package/evidence retention is required"
                    }
            }
            val prepare = steps.single { phase(it) == "preparePackage" }
            val retained = steps.single {
                yamlMappingOrEmpty(it["with"])["name"] ==
                    "\${{ steps.prepare.outputs.artifact_name }}"
            }
            require(
                steps.indexOf(retained) > steps.indexOf(prepare) &&
                    yamlMapping(retained["with"])["if-no-files-found"] == "error"
            ) {
                "$action: retain the sealed package after preparation"
            }
            if (action == "testflight-deploy") {
                require(
                    prepare["if"] ==
                        "steps.select.outputs.manual == 'true' && steps.select.outputs.selected == 'true'"
                ) {
                    "Automatic deployment must not build/sign or verify source"
                }
                require(
                    steps.indexOf(retained) < steps.indexOfFirst { phase(it) == "deployPackage" }
                ) {
                    "Retain manual bytes before upload"
                }
            }
        }
    }
}

private fun yamlMappingOrEmpty(value: Any?) = (value as? Map<*, *>).orEmpty()

internal fun validateSplitWorkflow(document: Map<*, *>, job: Map<*, *>, role: String) {
    val jobs = yamlMapping(document["jobs"])
    val operationId = jobs.entries.singleOrNull { it.value == job }?.key
    val reporters = jobs.filterValues { candidate ->
        val reportingJob = yamlMapping(candidate)
        workflowSteps(reportingJob).any { reportingAction(it["uses"]) } &&
            (reportingJob["needs"] == operationId || reportingJob["needs"] == listOf(operationId))
    }
    require(reporters.size == 1) {
        "Split operation requires one independent boundary reporter job, even when checkout or engine installation fails"
    }
    val reporter = reporters.entries.single()
    validateReportingJob(document, reporter.key.toString())
    require(
        yamlMapping(workflowSteps(yamlMapping(reporter.value)).single()["with"])["role"] == role
    ) {
        "Boundary reporter role must match its CI/deployment operation"
    }
    val triggers = yamlMapping(document["on"] ?: document[true])
    require(job["env"] == null && document["env"] == null) {
        "Split workflow credentials belong to individual steps"
    }
    val allSteps = yamlMapping(document["jobs"]).values.flatMap { workflowSteps(yamlMapping(it)) }
    require(allSteps.none { it["uses"].toString().contains("/ai-review@") }) {
        "AI review must be an independent workflow, outside the CI/deploy aggregate conclusion"
    }
    if (role == "deploy") {
        require(
            document["name"] == "TestFlight Deploy" && triggers.containsKey("workflow_dispatch")
        ) {
            "Standalone deployment needs its stable name and human branch dispatch"
        }
        val automatic = yamlMapping(triggers["workflow_run"])
        require("completed" in (automatic["types"] as? List<*>).orEmpty()) {
            "Automatic deployment needs completed upstream CI"
        }
        require(
            job["needs"] == null &&
                !Regex("github\\.ref|GITHUB_REF").containsMatchIn(job["if"].toString())
        ) {
            "Manual branch dispatch cannot depend on CI/review or a main-only guard"
        }
        val manual = (triggers["workflow_dispatch"] as? Map<*, *>).orEmpty()
        val inputs = (manual["inputs"] as? Map<*, *>).orEmpty()
        require(
            inputs.keys.none {
                Regex("(?i)artifact|run.?id|archive|ipa").containsMatchIn(it.toString())
            }
        ) {
            "No manual artifact-reference form"
        }
    } else {
        require(document["name"] == "Continuous Integration" && triggers.containsKey("push")) {
            "CI needs its stable name and integration push trigger"
        }
        val push = yamlMapping(triggers["push"])
        require((push["branches"] as? List<*>).orEmpty().isNotEmpty() && push["tags"] == null) {
            "CI must filter branch pushes so receipt tags cannot verify/deploy"
        }
    }
}
