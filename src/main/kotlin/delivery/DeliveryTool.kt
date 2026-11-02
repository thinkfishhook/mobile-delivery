package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val eventJson = Json { ignoreUnknownKeys = true }

private class ReportedSweepFailure :
    CommandError("Sweep hit a repair error; see structured summary")

class DeliveryTool(
    private val root: Path,
    private val config: DeliveryConfig,
    private val commands: Commands,
    private val env: Map<String, String>,
) {
    private val receiptProblems = linkedMapOf<String, String>()
    private val credentialRepairErrors = mutableListOf<String>()
    private var summaryWritten = false
    private val apple = Asc(config, commands, env)

    fun run(mode: String) {
        try {
            execute(mode)
        } catch (error: Exception) {
            if (error is CredentialProblem) {
                reportCredentialProblem(error, root, config, commands, env)
                summaryWritten = true
            }
            if (!summaryWritten)
                writeSummary(
                    RunSummary(
                        "failed",
                        "No delivery outcome inferred",
                        listOfNotNull(error.displayMessage()),
                        emptyList(),
                        testFailures = (error as? VerificationFailure)?.testFailures.orEmpty(),
                        failingCommand = (error as? VerificationFailure)?.failingCommand,
                        verificationFailureKind = (error as? VerificationFailure)?.kind,
                    )
                )
            throw error
        }
    }

    private fun execute(mode: String) {
        requireWorkflow(mode, env)
        val branch =
            requireNotNull(workflowInputs(env)?.branch) {
                "$mode requires a branch in DELIVERY_INPUTS; set the caller's with: branch input"
            }
        val github = GitHub(env.getValue("GITHUB_REPOSITORY"), commands, env)
        val hints =
            github.workflowHints(
                when (mode) {
                    "appStoreRelease",
                    "rolloutControl" -> "app-store-release"
                    "startRun",
                    "buildBeta",
                    "uploadBeta",
                    "finishRun" -> "testflight"
                    else -> null
                }
            )
        val source = GitSource(root, config, commands, branch)
        val tags =
            GitHubReceiptTags(github).apply {
                onCredentialProblem = { problem ->
                    if (receiptProblems.put(problem.secret, problem.message!!) == null) {
                        credentialRepairErrors +=
                            publishCredentialProblem(problem, config, commands, env)
                    }
                }
            }
        val artifacts = GitHubBuildArtifacts(github, env["GITHUB_RUN_ID"] ?: "local")
        val symbols =
            ReleaseSymbols(
                artifacts,
                GitHubReleaseAssets(github),
                root.resolve("build/release-symbols"),
            )
        val appStore =
            AppStoreRelease(
                config,
                apple,
                tags,
                source,
                symbols,
                env,
                hints = hints,
                holdIssues = GitHubHoldIssues(github),
            )
        val redStreaks = RedStreaks(github, config.incidentMarker)
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
                    redStreaks.closeDelivered(receipt.sha, "${receipt.version}/${receipt.number}")
                },
            )
        requireActionsWriter(env, branch)
        val payload =
            env["GITHUB_EVENT_PATH"]?.let {
                eventJson.decodeFromString<WorkflowEvent>(Path.of(it).readText())
            } ?: WorkflowEvent()
        val event = workflowInputs(env)?.let { payload.copy(inputs = it) } ?: payload
        // Kotlin Multiplatform apps need the Android SDK before verification.
        if (mode == "startRun") outputs("android=${config.androidPackages.isNotEmpty()}\n")
        if (mode in setOf("startRun", "sweep") && env["IOS_DELIVERY_ENABLED"] != "true") {
            if (mode == "startRun") {
                val verify =
                    env["GITHUB_EVENT_NAME"] == "push" ||
                        event.inputs?.operation == "deliver" ||
                        (env["DELIVERY_COMPOSITE"] == "true" &&
                            env["GITHUB_EVENT_NAME"] == "schedule")
                outputs("operation=${if (verify) "verify" else "none"}\n")
                if (verify) outputs("sha=${env.getValue("GITHUB_SHA")}\n")
            }
            writeSummary(RunSummary("completed", "Delivery disabled", emptyList(), emptyList()))
            return
        }
        if (mode in setOf("appStoreRelease", "appStorePreflight", "rolloutControl")) {
            appStore(mode, event, appStore, symbols)
            return
        }
        if (mode == "startRun") {
            startRun(event, StartRun(config, source, github, env, branch), beta)
            return
        }
        val selected = (event.inputs?.sha?.takeIf { it.isNotBlank() } ?: env.getValue("GITHUB_SHA"))
        require(Regex("[a-f0-9]{40}").matches(selected)) { "Select an exact full commit SHA" }
        val resolved = source.git("rev-parse", "$selected^{commit}")
        require(resolved == selected) {
            "Selected SHA $selected must resolve to that exact commit; git resolved $resolved"
        }
        source.git("merge-base", "--is-ancestor", config.baseline, selected)
        source.git("merge-base", "--is-ancestor", selected, "origin/${branch}")
        val healthTip =
            when {
                mode in setOf("sweep", "audit", "finishRun") -> source.refreshBranchTip(env)
                else -> source.git("rev-parse", "origin/${branch}")
            }
        val sweep =
            Sweep(
                config,
                beta,
                appStore,
                apple,
                tags,
                source,
                GitHubIncidents(github, config.incidentMarker, branch),
                github::queuedPush,
                hints,
                redStreak = { sha ->
                    val summaryFile = root.resolve("build/delivery/run-summary.json")
                    val current =
                        if (
                            mode == "finishRun" &&
                                selected == sha &&
                                env["IOS_EXECUTION_FAILED"] == "true" &&
                                summaryFile.exists()
                        )
                            json.decodeFromString<RunSummary>(summaryFile.readText())
                        else null
                    if (
                        current != null && current.verificationFailureKind != FailureKind.VALIDATION
                    )
                        null
                    else redStreaks.covering(sha)
                },
                onDelivered = { sha, trace ->
                    val version =
                        requireNotNull(trace.version) {
                            "Delivered receipt for $sha build ${trace.number} must name its marketing version"
                        }
                    redStreaks.closeDelivered(sha, "$version/${trace.number}")
                },
            )
        val output = root.resolve("build/delivery").createDirectories()
        try {
            when (mode) {
                "buildBeta" -> {
                    require(env["IOS_DELIVERY_ENABLED"] == "true") { "Delivery disabled" }
                    val operation = intent(event, selected)
                    val build = beta.build(operation, output)
                    val packageRoot = root.resolve("build/retained-package")
                    if (build != null) {
                        val bytes = localPackage(build, output.resolve(selected))
                        packageRoot.toFile().deleteRecursively()
                        val destination = packageRoot.resolve(selected).createDirectories()
                        bytes.forEach { (name, value) ->
                            destination.resolve(name).writeBytes(value)
                        }
                    }
                    // Upload runs for a new build or for one Apple already has (resume).
                    val publish = build != null || beta.start(operation) is StartDecision.Deliver
                    outputs(
                        "publish=$publish\nretain=${build != null}\nartifact_name=${build?.artifact?.artifactName.orEmpty()}\nartifact_path=$packageRoot\n"
                    )
                }
                "uploadBeta" -> {
                    val receipt = beta.upload(intent(event, selected), output)
                    if (receipt != null && receiptProblems.isNotEmpty()) {
                        writeSummary(
                            RunSummary(
                                "completed",
                                "Delivered to TestFlight ${receipt.version}/${receipt.number}",
                                emptyList(),
                                emptyList(),
                            )
                        )
                    }
                    outputs(
                        "published=${receipt != null}\nversion=${receipt?.version ?: ""}\nbuild=${receipt?.number ?: ""}\n"
                    )
                }
                "finishRun" -> {
                    syncRedStreak(github, source, selected, output, branch)
                    report(sweep, sweep.run(healthTip), env["IOS_EXECUTION_FAILED"] == "true")
                }
                "sweep" -> {
                    github.ensureDeliveryLabels()
                    report(sweep, sweep.run(healthTip), false)
                }
                "audit" -> report(sweep, sweep.run(healthTip, audit = true), false)
                else -> error("Unsupported delivery task: $mode")
            }
        } catch (error: Exception) {
            deliveryFailed(error)
        }
    }

    private fun syncRedStreak(
        github: GitHub,
        source: GitSource,
        sha: String,
        output: Path,
        branch: String,
    ) {
        if (
            env["GITHUB_EVENT_NAME"] != "push" ||
                env["IOS_DELIVERY_ENABLED"] != "true" ||
                env["IOS_EXECUTION_FAILED"] != "true"
        )
            return
        val file = output.resolve("run-summary.json")
        if (!file.exists()) return
        val summary = json.decodeFromString<RunSummary>(file.readText())
        if (
            summary.runHealth != "failed" ||
                summary.verificationFailureKind != FailureKind.VALIDATION
        )
            return
        RedStreaks(github, config.incidentMarker).sync(
            RedStreakCommit(
                sha,
                source.git("log", "-1", "--format=%s", sha),
                workflowUrl(env),
                env["GITHUB_RUN_ATTEMPT"]?.toIntOrNull() ?: 1,
            ),
            summary.failures,
            redStreakDetails(github, config, env, sha, branch, summary, output),
        ) { firstRed ->
            redStreakSuspects(firstRed, config.baseline, source, GitHubReceiptTags(github), github)
        }
    }

    private fun startRun(event: WorkflowEvent, start: StartRun, beta: BetaDelivery) {
        when (val selection = start.select(event)) {
            is StartRunSelection.Superseded -> {
                outputs("operation=none\n")
                start.cancelSuperseded(selection, ::writeSummary)
            }
            is StartRunSelection.Proceed -> {
                val selected = selection.sha
                root.resolve("build/delivery").createDirectories()
                try {
                    when (val decision = beta.start(intent(event, selected))) {
                        is StartDecision.Deliver -> outputs("operation=deliver\nsha=$selected\n")
                        StartDecision.AlreadyDelivered ->
                            outputs("operation=sweep\nsha=$selected\n")
                        StartDecision.SweepOnly -> outputs("operation=sweep\nsha=$selected\n")
                        is StartDecision.Refused -> error(decision.reason)
                    }
                } catch (error: Exception) {
                    deliveryFailed(error)
                }
            }
        }
    }

    private fun deliveryFailed(error: Exception): Nothing {
        if (error is ReportedSweepFailure || error is CredentialProblem) throw error
        // Red runs say why on the run page, not only in the log.
        error.displayMessage()?.let { annotate(Annotation("error", it)) }
        writeSummary(
            RunSummary(
                "failed",
                "See the run evidence",
                listOfNotNull(error.displayMessage()),
                emptyList(),
                testFailures = (error as? VerificationFailure)?.testFailures.orEmpty(),
                failingCommand = (error as? VerificationFailure)?.failingCommand,
                verificationFailureKind = (error as? VerificationFailure)?.kind,
            )
        )
        throw error
    }

    private fun appStore(
        mode: String,
        event: WorkflowEvent,
        release: AppStoreRelease,
        symbols: ReleaseSymbols,
    ) {
        if (mode == "appStorePreflight") {
            val checks = AppStorePreflight(release, apple, symbols).verify()
            writeSummary(
                RunSummary("completed", checks.joinToString("; "), emptyList(), emptyList())
            )
            return
        }
        if (env["IOS_APPSTORE_ENABLED"] == "false") {
            writeSummary(
                RunSummary("completed", "App Store release disabled", emptyList(), emptyList())
            )
            return
        }
        val operation = event.inputs?.operation ?: "promote"
        val rollout = operation in setOf("pause", "resume", "release-to-all")
        // Rollout changes run in their own job so they never wait behind a delivery.
        if (mode == "rolloutControl")
            require(env["GITHUB_EVENT_NAME"] == "workflow_dispatch" && rollout) {
                "rolloutControl runs only pause, resume and release-to-all dispatches"
            }
        else require(!rollout) { "$operation runs in the rollout-control job" }
        val result =
            when {
                mode == "rolloutControl" -> release.control(operation, event.inputs?.reason)
                env["GITHUB_EVENT_NAME"] == "schedule" ->
                    release.promote(ReleaseRequest(scheduled = true, reason = null, phased = true))
                operation == "promote" ->
                    release.promote(
                        ReleaseRequest(
                            scheduled = false,
                            reason = event.inputs?.reason,
                            phased = event.inputs?.rollout != "all",
                        )
                    )
                else -> error("Unsupported App Store operation: $operation")
            }
        result.action?.let { annotate(Annotation("warning", it)) }
        env["GITHUB_OUTPUT"]?.let {
            writeEnvironment(
                mapOf(
                    "outcome" to result.outcome,
                    "version" to result.version.orEmpty(),
                    "build" to result.build.orEmpty(),
                ),
                Path.of(it),
            )
        }
        writeSummary(
            RunSummary("completed", result.outcome, listOfNotNull(result.action), emptyList())
        )
    }

    private fun intent(event: WorkflowEvent, sha: String): Operation {
        if (env["IOS_DELIVERY_ENABLED"] != "true") return Operation.SweepOnly(sha)
        val operation =
            if (env["GITHUB_EVENT_NAME"] == "push") "deliver"
            else event.inputs?.operation ?: "deliver"
        // A rerun replays the original event, so a retry's expected attempt is still here.
        if ((env["GITHUB_RUN_ATTEMPT"]?.toIntOrNull() ?: 1) > 1)
            return Operation.Rerun(
                sha,
                event.inputs?.expectedAttempt?.takeIf { operation == "retry" },
                event.inputs?.newAttempt == true && operation == "retry",
            )
        return when (operation) {
            "deliver" -> Operation.Deliver(sha)
            "retry" ->
                Operation.Retry(
                        sha,
                        requireNotNull(event.inputs?.expectedAttempt) {
                            "retry requires expected_attempt"
                        },
                        requireNotNull(event.inputs.reason) { "retry requires reason" },
                        event.inputs.newAttempt,
                    )
                    .also { require(!event.inputs.sha.isNullOrBlank()) { "retry requires sha" } }
            else -> error("Unsupported dispatch operation: $operation")
        }
    }

    private fun outputs(value: String) {
        env["GITHUB_OUTPUT"]?.let { Path.of(it).appendText(value) }
        print(value)
    }

    private fun writeSummary(summary: RunSummary) {
        summaryWritten = true
        writeRunSummary(
            root,
            env,
            summary.copy(
                deliveries =
                    summary.deliveries +
                        if (receiptProblems.isEmpty()) "" else "; receipts weren't written",
                actionNeeded = (summary.actionNeeded + receiptProblems.values).distinct(),
                repairErrors = summary.repairErrors + credentialRepairErrors,
                ignoredVersions = apple.ignoredVersions.sorted(),
            ),
        )
    }

    private fun report(sweep: Sweep, result: SweepReport, failed: Boolean) {
        writeSummary(sweep.summary(result, failed))
        result.findings.forEach { finding ->
            finding.operatorAction?.let { annotate(Annotation("warning", it)) }
            finding.repairError?.let { annotate(Annotation("error", it)) }
        }
        if (result.broken) throw ReportedSweepFailure()
    }
}

@Serializable
data class RunSummary(
    val runHealth: String,
    val deliveries: String,
    val actionNeeded: List<String>,
    val repairErrors: List<String>,
    val ignoredVersions: List<String> = emptyList(),
    val testFailures: List<TestFailure> = emptyList(),
    val failingCommand: FailingCommand? = null,
    val failures: List<TestFailure> = testFailures,
    val verificationFailureKind: FailureKind? = null,
)

internal fun writeRunSummary(root: Path, env: Map<String, String>, summary: RunSummary) {
    val encoded = json.encodeToString(summary)
    root
        .resolve("build/delivery")
        .createDirectories()
        .resolve("run-summary.json")
        .writeText(encoded + "\n")
    env["GITHUB_STEP_SUMMARY"]?.let {
        Path.of(it).appendText("\n```json\n$encoded\n```\n" + failureTable(summary.failures))
    }
    // JSON escapes preserve the data without replaying workflow commands from app output.
    println(encoded.replace("::", "\\u003a\\u003a"))
}
