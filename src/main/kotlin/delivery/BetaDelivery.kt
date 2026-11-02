package delivery

import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.io.path.*

sealed interface Operation {
    val sha: String

    data class Deliver(override val sha: String) : Operation

    data class Retry(
        override val sha: String,
        val expectedAttempt: String,
        val reason: String,
        val newAttempt: Boolean = false,
    ) : Operation

    // An Actions rerun. `expectedAttempt` is the original dispatch's when the run was a retry.
    data class Rerun(
        override val sha: String,
        val expectedAttempt: String? = null,
        val newAttempt: Boolean = false,
    ) : Operation

    data class SweepOnly(override val sha: String) : Operation
}

sealed interface Plan {
    data object New : Plan

    // Apple already has the build; only distribution and the receipt remain.
    data class Resume(val trace: Trace) : Plan

    // Apple shows no upload of a retained build. Upload the same bytes under the same number:
    // Apple refuses a number it already has, so an upload it has not listed yet cannot become a
    // second build.
    data class Reupload(val trace: Trace) : Plan
}

sealed interface StartDecision {
    data class Deliver(val plan: Plan) : StartDecision

    data object SweepOnly : StartDecision

    data object AlreadyDelivered : StartDecision

    data class Refused(val reason: String) : StartDecision
}

class BetaDelivery(
    private val config: DeliveryConfig,
    private val apple: Apple,
    private val tags: ReceiptTags,
    private val artifacts: BuildArtifacts,
    private val source: Source,
    private val builds: BuildOperations,
    private val env: Map<String, String>,
    private val clock: Clock = Clock.systemUTC(),
) {
    private fun now() = config.now(clock)

    fun facts() =
        BetaFacts(
            requireReceiptNames("deploy/testflight/", tags.names("deploy/testflight/")),
            apple.testFlightBuilds(),
            apple.uploads(),
            Policy.marketingVersion(
                now(),
                apple
                    .storeVersions()
                    .filter { it.state !in StoreStates.editable }
                    .map { it.version }
                    .toSet(),
            ),
        )

    fun traces(sha: String, facts: BetaFacts = facts()): CommitTraces {
        val count = source.count(sha)
        // Only pipeline builds count: YY.MM.RR versions and <count>.<attempt> numbers.
        fun ours(number: String, version: String) = runCatching {
            MarketingVersion(version) to BuildNumber(number)
        }
            .getOrNull()
            ?.second
            ?.takeIf { it.count == count }
        val receipts =
            facts.receipts.mapNotNull { tag ->
                val (version, number) = tag.removePrefix("deploy/testflight/").split('/')
                ours(number, version)?.let { Triple(it, version, tag) }
            }
        val numbers =
            (receipts.map { it.first to it.second } +
                    facts.builds.mapNotNull { b ->
                        ours(b.number, b.version)?.let { it to b.version }
                    } +
                    facts.uploads.mapNotNull { u ->
                        ours(u.number, u.version)?.let { it to u.version }
                    })
                .distinctBy { it.first }
        // Builds retained after the last number Apple or a receipt shows, found by exact name
        // whatever version they were built for.
        val retained =
            generateSequence((numbers.maxOfOrNull { it.first.attempt } ?: 0) + 1) { it + 1 }
                .map { artifacts.retained(Policy.buildNumber(count, it)) }
                .takeWhile { it != null }
                .filterNotNull()
                .map { Trace(it.number, null, null, null, null, it) }
        return CommitTraces(
            sha,
            count,
            facts.version,
            numbers.map { (number, version) ->
                Trace(
                    number,
                    version,
                    receipts.find { it.first == number }?.let { tags.commit(it.third) },
                    facts.uploads
                        .filter { it.number == number.value && it.version == version }
                        .decisive(),
                    facts.builds.find { it.number == number.value && it.version == version },
                )
            } + retained,
        )
    }

    fun start(operation: Operation): StartDecision =
        if (operation is Operation.SweepOnly) StartDecision.SweepOnly
        else decide(operation, traces(operation.sha))

    private fun decide(operation: Operation, traces: CommitTraces): StartDecision {
        val retry = WorkflowHints(env).retry(operation.sha, traces.expectedAttempt)
        if (operation is Operation.Retry) {
            require(operation.reason.isNotBlank()) { "Retry requires a reason" }
            require(operation.expectedAttempt == traces.expectedAttempt) {
                "Stale retry: the latest build for ${operation.sha} is ${traces.expectedAttempt}; inspect it before retrying: $retry"
            }
        }
        val latest = traces.latest
        val newAttempt =
            when (operation) {
                is Operation.Retry -> operation.newAttempt
                is Operation.Rerun -> operation.newAttempt
                else -> false
            }
        // Ordinary pushes are idempotent even while a deliberate rebuild is unfinished.
        if (operation is Operation.Deliver && traces.delivered != null)
            return StartDecision.AlreadyDelivered
        val ownNumber =
            if (operation is Operation.Rerun) artifacts.retainedByThisRun()?.number else null
        val ownsLatest = latest != null && latest.number == ownNumber
        when (operation) {
            is Operation.Retry -> Unit
            // A rerun continues its own run: the build it retained, or the retry it was.
            is Operation.Rerun ->
                if (
                    latest != null &&
                        !ownsLatest &&
                        latest.number.value != operation.expectedAttempt
                )
                    return StartDecision.Refused(
                        "This rerun is superseded: ${latest.detail} belongs to a later run; retry explicitly: $retry"
                    )
            else ->
                if (latest != null)
                    return StartDecision.Refused(
                        "${operation.sha} already used ${latest.detail}; retry explicitly: $retry"
                    )
        }
        if (latest?.state == TraceState.DELIVERED) {
            // A rerun that already delivered its own retained package must not rebuild it.
            if (!newAttempt || ownsLatest) return StartDecision.AlreadyDelivered
        } else if (newAttempt && !ownsLatest) {
            return StartDecision.Refused(
                "A new attempt requires the latest build to be delivered; recover ${traces.expectedAttempt} first"
            )
        }
        return when (latest?.state) {
            null -> StartDecision.Deliver(Plan.New)
            TraceState.UNCERTAIN ->
                StartDecision.Refused(
                    "Apple is still processing ${latest.detail}; wait for a sweep to confirm it instead of uploading again"
                )
            TraceState.RESUMABLE -> StartDecision.Deliver(Plan.Resume(latest))
            // Once Actions has expired the bytes, any upload would long be visible in Apple.
            TraceState.INTERRUPTED ->
                if (latest.retained?.expired == false) StartDecision.Deliver(Plan.Reupload(latest))
                else StartDecision.Deliver(Plan.New)
            else -> StartDecision.Deliver(Plan.New)
        }
    }

    private fun plan(operation: Operation): Plan? =
        when (val decision = start(operation)) {
            is StartDecision.Deliver -> decision.plan
            StartDecision.AlreadyDelivered,
            StartDecision.SweepOnly -> null
            is StartDecision.Refused -> error(decision.reason)
        }

    // Apple rejects every upload for a day after error 90382.
    private fun requireUploadCooldownElapsed(uploads: List<AppleUpload>) {
        uploads
            .filter { it.code == "90382" && it.uploadedAt != null }
            .map { OffsetDateTime.parse(it.uploadedAt).plusDays(1) }
            .maxOrNull()
            ?.let { until ->
                require(!now().toOffsetDateTime().isBefore(until)) {
                    "Apple upload cooldown lasts until $until"
                }
            }
    }

    // Verifies and archives the exact commit, or returns null when there is nothing to build.
    fun build(operation: Operation, output: Path): Build? {
        val sha = operation.sha
        if (plan(operation) != Plan.New) return null
        require(sha in source.commits(config.baseline, sha)) {
            "$sha does not descend from the configured baseline"
        }
        requireUploadCooldownElapsed(apple.uploads())
        builds.checkEnvironment()
        val destination = output.resolve(sha).createDirectories()
        return source.snapshot(sha) { checkout ->
            try {
                builds.verify(checkout, destination.resolve("verification"))
                // Verification takes hours; decide again on fresh facts before taking a number.
                // An upload that finished meanwhile (a job the queue lost track of) must not be
                // followed by a second build under the next number.
                val traces = traces(sha)
                when (val decision = decide(operation, traces)) {
                    StartDecision.Deliver(Plan.New) -> Unit
                    is StartDecision.Refused ->
                        error("${decision.reason} (found after verification; nothing was archived)")
                    else ->
                        error("$sha changed during verification ($decision); nothing was archived")
                }
                val number =
                    Policy.buildNumber(traces.count, (traces.latest?.number?.attempt ?: 0) + 1)
                val build =
                    Build(
                        sha,
                        traces.version,
                        number,
                        workflowUrl(env),
                        config.xcode,
                        config.asc,
                    )
                builds.archive(checkout, destination, build)
                requireNotNull(build.artifact) { "Archive did not produce a manifest" }
                build
            } finally {
                destination.resolve("DerivedData").toFile().deleteRecursively()
            }
        }
    }

    // Uploads the retained build, or resumes one Apple already has, then confirms it.
    fun upload(operation: Operation, output: Path): Receipt? {
        val sha = operation.sha
        val destination = output.resolve(sha).createDirectories()
        val retained =
            artifacts.retainedByThisRun()
                ?: when (val plan = plan(operation)) {
                    null -> return null
                    is Plan.Resume -> {
                        val trace = plan.trace
                        return confirm(
                            sha,
                            MarketingVersion(
                                requireNotNull(trace.version) {
                                    "Cannot resume $sha build ${trace.number}: Apple reported no marketing version"
                                }
                            ),
                            trace.number,
                            destination,
                            null,
                        )
                    }
                    is Plan.Reupload ->
                        requireNotNull(plan.trace.retained) {
                            "Cannot reupload $sha build ${plan.trace.number}: no retained package was found"
                        }
                    Plan.New -> error("This run retained no build to upload")
                }
        // Read the retained bytes back before anything reaches Apple.
        val retainedPackage = artifacts.download(retained, destination)
        val manifest = retainedPackage.manifest
        require(manifest.sha == sha) { "The retained build belongs to ${manifest.sha}" }
        val facts = facts()
        val listed =
            (facts.uploads.filter { it.number == manifest.number.value }.map { it.version } +
                    facts.builds.filter { it.number == manifest.number.value }.map { it.version })
                .toSet()
        // Numbers are never reused: Apple holding this one under another version is a stop.
        require(listed.all { it == manifest.version.value }) {
            "Apple already has ${manifest.number} under ${(listed - manifest.version.value).joinToString()}; ${manifest.version}/${manifest.number} is not uploaded"
        }
        if (listed.isEmpty()) {
            requireUploadCooldownElapsed(facts.uploads)
            try {
                apple.upload(retainedPackage.ipa, destination)
            } catch (error: Exception) {
                val upload = runCatching {
                    apple.uploads()
                }
                    .getOrNull()
                    ?.filter {
                        it.number == manifest.number.value && it.version == manifest.version.value
                    }
                    ?.decisive()
                if (upload?.state == "FAILED" && !upload.received)
                    throw CommandError(
                        "Apple rejected ${manifest.number}: ${upload.code} ${upload.reason}"
                    )
                throw CommandError(
                    "Upload outcome of ${manifest.number} is uncertain (${error.displayMessage()}); never upload it again, let a sweep confirm it"
                )
            }
        }
        return confirm(sha, manifest.version, manifest.number, destination, manifest)
    }

    // The receipt means testers can install the build, so it follows Apple's confirmation.
    private fun confirm(
        sha: String,
        version: MarketingVersion,
        number: BuildNumber,
        output: Path,
        manifest: ArtifactManifest?,
    ): Receipt {
        val remote =
            requireNotNull(apple.lookup(version, number)) {
                "$version/$number is not visible in App Store Connect yet; never upload it again, let a sweep confirm it"
            }
        require(!remote.expired && remote.processingState !in setOf("FAILED", "INVALID")) {
            "Apple marked $version/$number invalid; an explicit retry takes a new build number"
        }
        val notes = testerNotes(config, sha)
        try {
            if (!(apple.testable(remote.id) && sameNotes(apple.notes(remote.id), notes))) {
                apple.distribute(remote.id, notes, output)
                require(apple.testable(remote.id)) {
                    "Distribution finished before the internal group could test; no receipt written"
                }
            }
        } catch (error: TerminalTestFlightState) {
            // Apple still calls the build valid, so a retry would resume it again. Expiring it
            // makes the build count as failed, and the retry then takes a new number.
            throw CommandError(
                "TestFlight will never make $version/$number testable (${error.state}). Expire it in App Store Connect, then retry; the retry takes a new build number."
            )
        }
        val receipt =
            Receipt(
                sha,
                version,
                number,
                remote.id,
                workflowUrl(env),
                manifest?.ipa?.sha256,
                manifest?.ipa?.bytes,
                now().toOffsetDateTime().toString(),
                config.xcode,
                config.asc,
            )
        output
            .resolve("receipt.json")
            .writeText(json.encodeToString(Receipt.serializer(), receipt) + "\n")
        // A failed tag write leaves the delivery successful; the run's final sweep repairs it.
        attemptEffect { tags.write("deploy/testflight/$version/$number", sha, receipt) }
            .exceptionOrNull()
            ?.let {
                println(
                    "::warning::Receipt for $version/$number not written yet: ${it.displayMessage()}"
                )
            }
        return receipt
    }
}
