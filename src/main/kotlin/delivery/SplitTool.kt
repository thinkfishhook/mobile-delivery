package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.json.*

class SplitSuperseded :
    IllegalStateException(
        "Automatic package was superseded before upload; no older-success fallback"
    )

// Thin Actions/CLI adapter around shared preparation, signing, artifact and Apple operations.
class SplitTool(
    private val root: Path,
    private val config: DeliveryConfig,
    private val commands: Commands,
    private val env: Map<String, String>,
) {
    companion object {
        val modes = setOf("selectSplit", "verifySource", "preparePackage", "deployPackage")
    }

    fun run(mode: String) {
        val github = GitHub(env.getValue("GITHUB_REPOSITORY"), commands, env)
        val branch =
            requireNotNull(workflowInputs(env)?.branch) { "Set the configured integration branch" }
        require(env["IOS_DELIVERY_ENABLED"] in setOf("true", "false")) {
            "IOS_DELIVERY_ENABLED must be exactly true or false"
        }
        val isCi = env["DELIVERY_VALIDATION_MODE"] == "ci"
        val automatic = !isCi && env["GITHUB_EVENT_NAME"] == "workflow_run"
        val request =
            when {
                isCi -> ciPreparationRequest(env, config.bundleId, branch)
                automatic -> {
                    val event =
                        json
                            .parseToJsonElement(
                                Path.of(env.getValue("GITHUB_EVENT_PATH")).readText()
                            )
                            .jsonObject
                    require(event.string("action") == "completed") { "Wait for completed CI" }
                    val eventRun = event.getValue("workflow_run").jsonObject
                    val workflow = env["DELIVERY_CI_WORKFLOW"] ?: "ci.yml"
                    automaticPreparationRequest(
                        env,
                        eventRun,
                        github.api("actions/runs/${eventRun.string("id")}")!!.jsonObject,
                        github.api("actions/workflows/$workflow")!!.jsonObject,
                        branch,
                        workflow,
                        config.bundleId,
                    )
                }
                else -> manualPreparationRequest(env, config.bundleId)
            }
        if (mode == "selectSplit") {
            val tip = if (isCi) github.currentBranchTip(branch) else request.sha
            val selected = request.sha == tip && (isCi || env["IOS_DELIVERY_ENABLED"] == "true")
            outputs(
                mapOf(
                    "selected" to selected.toString(),
                    "sha" to request.sha,
                    "manual" to (!isCi && !automatic).toString(),
                    "android" to config.androidPackages.isNotEmpty().toString(),
                )
            )
            if (!selected && isCi)
                StartRun(config, GitSource(root, config, commands, branch), github, env, branch)
                    .cancelSuperseded(StartRunSelection.Superseded(request.sha, tip)) {
                        writeRunSummary(root, env, it)
                    }
            if (!selected && !isCi) summary("skipped", "Delivery disabled", request)
            return
        }
        val source = GitSource(root, config, commands, branch)
        if (mode != "deployPackage")
            require(source.git("rev-parse", "${request.sha}^{commit}") == request.sha) {
                "Selected source must resolve to its exact commit"
            }
        val apple = Asc(config, commands, env)
        val store = GitHubPreparationStore(github)
        val tags =
            GitHubReceiptTags(github, store::sealedPreparation).apply {
                onCredentialSuccess = {
                    resolveCredentialIncidents(setOf("IOS_RECEIPT_TOKEN"), config, commands, env)
                }
                onCredentialProblem = { problem ->
                    annotate(Annotation("error", problem.secret, "mobile-delivery-owner"))
                    annotate(Annotation("warning", problem.message!!))
                }
            }
        val artifacts = GitHubBuildArtifacts(github, env.getValue("GITHUB_RUN_ID"))
        val packages = PreparedPackages(artifacts, config, commands, env)
        val service =
            SplitDelivery(
                config,
                apple,
                source,
                Verification(config, commands, env),
                PreparationReservations(store) {
                    occupiedPreparationNumbers(apple, tags, artifacts).also {
                        annotate(
                            Annotation(
                                "notice",
                                "RECEIPT_RECORD",
                                "mobile-delivery-credential-used",
                            )
                        )
                    }
                },
                store,
                packages::download,
            )
        when (mode) {
            "verifySource" -> {
                require(isCi) { "Verification belongs only to CI" }
                service.verify(request, root)
                summary("completed", "Verified frozen source", request)
            }
            "preparePackage" -> {
                require(env["IOS_DELIVERY_ENABLED"] == "true") {
                    "Delivery disabled; no signed package prepared"
                }
                require(isCi || !automatic) { "Automatic deployment must never rebuild a package" }
                val result = service.prepare(request, root)
                val manifest =
                    requireNotNull(result.record.manifest) {
                        "Preparation returned no sealed manifest"
                    }
                outputs(
                    mapOf(
                        "retain" to result.retain.toString(),
                        "artifact_name" to manifest.artifactName,
                        "artifact_path" to result.directory.toString(),
                        "source_sha" to request.sha,
                        "preparation_id" to request.id,
                        "package_digest" to manifest.ipa.sha256,
                        "version" to manifest.version.value,
                        "build" to manifest.number.value,
                        "producer_run" to result.record.preparation.request.producer.runId,
                        "producer_attempt" to manifest.producingAttempt.toString(),
                    )
                )
                summary(
                    "completed",
                    "Prepared ${manifest.version}/${manifest.number}; IPA ${manifest.ipa.sha256}; package produced by ${manifest.workflow}",
                    request,
                )
            }
            "deployPackage" -> {
                require(!isCi && env["IOS_DELIVERY_ENABLED"] == "true") {
                    "CI cannot upload; deployment must be enabled"
                }
                val record =
                    requireNotNull(
                        store.read().records.singleOrNull { it.preparation.id == request.id }
                    ) {
                        "No preparation from this exact producing CI/manual run; never select latest artifact or rebuild automatically"
                    }
                require(request.resumes(record.preparation.request)) {
                    "Upstream package source/origin differs"
                }
                val destination = root.resolve("build/delivery/${request.id}").createDirectories()
                val manifest = requireNotNull(record.manifest) { "Upstream package was not sealed" }
                require(
                    requireNotNull(manifest.producingAttempt) {
                        "Expected a schema-2 producing attempt"
                    } <= request.producer.attempt
                ) {
                    "Package was produced by a later attempt than the successful upstream run"
                }
                val beta =
                    BetaDelivery(
                        config,
                        apple,
                        tags,
                        artifacts,
                        source,
                        Verification(config, commands, env),
                        env,
                        onReceipted = { receipt ->
                            if (automatic && receipt.sha == github.currentBranchTip(branch))
                                RedStreaks(
                                        github,
                                        config.incidentMarker,
                                        app = config.bundleId,
                                        secretValues = config.buildEnvironment.mapNotNull(env::get),
                                    )
                                    .closeDelivered(
                                        receipt.sha,
                                        "${receipt.version}/${receipt.number}",
                                    )
                        },
                    )
                try {
                    requireReceiptNames("deploy/testflight/", tags.names("deploy/testflight/"))
                    annotate(
                        Annotation("notice", "RECEIPT_RECORD", "mobile-delivery-credential-used")
                    )
                    val receipt =
                        beta.publishPrepared(record, destination, packages::download) {
                            if (
                                automatic &&
                                    !automaticUploadEligible(request) {
                                        github.currentBranchTip(branch)
                                    }
                            )
                                throw SplitSuperseded()
                        }
                    outputs(
                        mapOf(
                            "published" to "true",
                            "version" to receipt.version.value,
                            "build" to receipt.number.value,
                            "package_digest" to manifest.ipa.sha256,
                        )
                    )
                    summary(
                        "completed",
                        "Internal TestFlight available ${receipt.version}/${receipt.number}; IPA ${manifest.ipa.sha256}; package produced by ${manifest.workflow}",
                        request,
                    )
                    annotate(Annotation("notice", receipt.sha, "mobile-delivery-confirmed"))
                } catch (_: SplitSuperseded) {
                    summary("skipped", "Superseded immediately before upload", request)
                }
            }
            else -> error("Unsupported split operation: $mode")
        }
    }

    private fun outputs(values: Map<String, String>) {
        env["GITHUB_OUTPUT"]?.let { writeEnvironment(values, Path.of(it)) }
    }

    private fun summary(health: String, outcome: String, request: PreparationRequest) =
        writeRunSummary(
            root,
            env,
            RunSummary(
                health,
                "$outcome; ${request.origin} ${request.ref}@${request.sha}; producing run ${request.producer.runId} attempt ${request.producer.attempt}",
                emptyList(),
                emptyList(),
            ),
        )
}
