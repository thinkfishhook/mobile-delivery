package delivery

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.*
import kotlinx.serialization.encodeToString

val config = DeliveryConfig.parse(resource("delivery.json"))
val clock: Clock = Clock.fixed(Instant.parse("2026-09-16T11:00:00Z"), ZoneId.of(config.timezone))
val now = config.now(clock)

class ProcessInterrupted : Error("Simulated hard process termination")

// App Store Connect in memory: builds, uploads, TestFlight distribution and App Store versions.
// Reads are logged in `reads`, changes in `calls`.
class FakeApple : Apple {
    val calls = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val builds = mutableListOf<TestFlightBuild>()
    val uploads = mutableListOf<AppleUpload>()
    val versions = mutableListOf<StoreVersion>()
    val testers = mutableSetOf<String>()
    val notesById = mutableMapOf<String, String>()
    // Response loss happens after the effect, unlike an interruption before the call.
    var failureAt: String? = null

    fun loseResponseAfter(effect: String, action: () -> Unit) {
        val previous = failureAt
        failureAt = effect
        try {
            action()
        } finally {
            failureAt = previous
        }
    }

    var interrupt: ((String) -> Unit)? = null

    // How the next upload ends: accepted and visible, accepted but invisible, accepted but not
    // yet listed anywhere (LOST), or rejected. Like Apple, a repeat of a number it already has is
    // always refused, listed as a failed upload with code 90189.
    var uploadOutcome = "VALID"
    val uploadedNumbers = mutableListOf<String>()
    private val unlisted = mutableListOf<AppleUpload>()
    var rejection: AppleUpload? = null
    var distributionReachesTesters = true
    var submitAccepted = true

    // Review submissions by the version they carry, and whether each was sent. `asc review
    // submit` creates one, adds the version and sends it in separate calls; a cut-off submit
    // leaves it created but unsent. Apple keeps one open submission per platform.
    val submissions = mutableListOf<Pair<String, Boolean>>()
    var submissionCutOff = false

    // The state a cut-off submit leaves its version in. App Store Connect may show a version
    // added to an unsent submission as READY_FOR_REVIEW rather than still editable.
    var unsentVersionState: String? = null

    override fun reviewSubmissions() =
        submissions
            .mapIndexed { index, (versionId, sent) ->
                ReviewSubmission("submission-$index", submissionState(versionId, sent))
            }
            .also { reads += "submissions" }

    fun submissionState(versionId: String, sent: Boolean) =
        when {
            !sent -> "READY_FOR_REVIEW"
            // App Review keeps a rejected submission open until a person resubmits or cancels it.
            versions.find { it.id == versionId }?.state in setOf("REJECTED", "METADATA_REJECTED") ->
                "UNRESOLVED_ISSUES"
            else ->
                versions
                    .find { it.id == versionId }
                    ?.state
                    ?.takeIf { it in setOf("WAITING_FOR_REVIEW", "IN_REVIEW") } ?: "COMPLETE"
        }

    var stagedTemplate: String? = null

    private fun call(name: String, effect: () -> Unit = {}) {
        interrupt?.invoke("before-$name")
        calls += name
        effect()
        interrupt?.invoke("after-$name")
        if (name == failureAt) throw CommandError("Response lost: $name")
    }

    override fun uploads() = uploads.toList().also { reads += "uploads" }

    override fun lookup(version: MarketingVersion, number: BuildNumber) =
        builds
            .find { it.version == version.value && it.number == number.value }
            ?.let { AppleBuild(it.id, it.processingState, it.expired) }
            .also { reads += "lookup" }

    // TestFlight states that will never become testable, by build ID.
    val terminal = mutableMapOf<String, String>()

    override fun testable(buildId: String): Boolean {
        reads += "testable"
        terminal[buildId]?.let { throw TerminalTestFlightState(it) }
        return buildId in testers
    }

    override fun notes(buildId: String) = notesById[buildId].also { reads += "notes" }

    override fun distribute(buildId: String, notes: String, output: Path) =
        call("distribute") {
            notesById[buildId] = notes
            if (distributionReachesTesters) testers += buildId
        }

    override fun upload(ipa: Path, output: Path) {
        val manifest =
            json.decodeFromString<ArtifactManifest>(ipa.resolveSibling("manifest.json").readText())
        val version = manifest.version.value
        val number = manifest.number.value
        val redundant =
            (uploads + unlisted).any { it.version == version && it.number == number && it.received }
        call("upload") {
            uploadedNumbers += number
            if (redundant) {
                uploads +=
                    AppleUpload(
                        version,
                        number,
                        "FAILED",
                        "90189",
                        "Redundant Binary Upload",
                        now.toOffsetDateTime().toString(),
                    )
                return@call
            }
            if (uploadOutcome == "LOST") {
                unlisted += AppleUpload(version, number, "COMPLETE")
                return@call
            }
            rejection?.let {
                uploads += it.copy(version = version, number = number)
                return@call
            }
            uploads +=
                AppleUpload(
                    version,
                    number,
                    "COMPLETE",
                    uploadedAt = now.toOffsetDateTime().toString(),
                )
            if (uploadOutcome != "INVISIBLE")
                builds +=
                    TestFlightBuild(
                        "build-$number",
                        version,
                        number,
                        uploadOutcome,
                        false,
                        now.toOffsetDateTime().toString(),
                    )
        }
        if (redundant) throw CommandError("Redundant binary upload")
        rejection?.let { throw CommandError("Apple rejected the upload") }
        if (uploadOutcome == "LOST") throw CommandError("Upload timed out")
    }

    // Apple finally lists the uploads it accepted without saying so, and their valid builds.
    fun reveal() {
        uploads.addAll(0, unlisted)
        unlisted.forEach {
            builds += TestFlightBuild("build-${it.number}", it.version, it.number, "VALID", false)
        }
        unlisted.clear()
    }

    override fun testFlightBuilds() = builds.toList().also { reads += "builds" }

    override fun storeVersions() = versions.toList().also { reads += "versions" }

    override fun validateVersion(id: String) {
        reads += "validate"
    }

    private fun update(id: String, change: (StoreVersion) -> StoreVersion) {
        val index = versions.indexOfFirst { it.id == id }
        versions[index] = change(versions[index])
    }

    override fun createVersion(version: MarketingVersion): String {
        call("create") {
            versions +=
                StoreVersion(
                    "version-$version",
                    version.value,
                    "PREPARE_FOR_SUBMISSION",
                    null,
                    null,
                    null,
                )
        }
        return "version-$version"
    }

    override fun renameVersion(id: String, version: MarketingVersion) =
        call("rename") { update(id) { it.copy(version = version.value) } }

    override fun stage(
        version: MarketingVersion,
        buildId: String,
        template: String,
        notes: String,
    ): String {
        val id = versions.single { it.version == version.value }.id
        call("stage") {
            stagedTemplate = template
            update(id) {
                it.copy(
                    buildId = buildId,
                    buildNumber = builds.single { b -> b.id == buildId }.number,
                )
            }
        }
        return id
    }

    override fun requestPhasedRelease(versionId: String) =
        call("phase") {
            update(versionId) {
                it.copy(rollout = Rollout("rollout-$versionId", "INACTIVE", null, 0))
            }
        }

    override fun removePhasedRelease(rolloutId: String) =
        call("unphase") {
            versions.replaceAll { if (it.rollout?.id == rolloutId) it.copy(rollout = null) else it }
        }

    override fun setRollout(rolloutId: String, state: String) =
        call("rollout:$state") {
            versions.replaceAll {
                if (it.rollout?.id == rolloutId) it.copy(rollout = it.rollout.copy(state = state))
                else it
            }
        }

    override fun submit(versionId: String, buildId: String) =
        call("submit") {
            if (!submitAccepted) return@call
            if (submissions.any { (id, sent) -> submissionState(id, sent) != "COMPLETE" })
                throw CommandError("Apple allows one open review submission per platform")
            if (submissionCutOff) {
                submissions += versionId to false
                unsentVersionState?.let { state -> update(versionId) { it.copy(state = state) } }
                throw CommandError("Connection reset before the submission was sent")
            }
            submissions += versionId to true
            update(versionId) { it.copy(state = "WAITING_FOR_REVIEW") }
        }
}

class FakeTags : ReceiptTags {
    val tags = linkedMapOf<String, Pair<String, Receipt>>()
    var failWrites = false
    var failReads = false
    val unreadable = mutableSetOf<String>()
    var commitReads = 0

    override fun names(prefix: String) = tags.keys.filter { it.startsWith(prefix) }.toSet()

    override fun commit(tag: String): String {
        commitReads++
        if (failReads || tag in unreadable)
            throw CommandError("GitHub API failed: git/ref/tags (HTTP 502)")
        return tags.getValue(tag).first
    }

    override fun write(tag: String, sha: String, receipt: Receipt) {
        if (failWrites) throw CommandError("tag write failed")
        val existing = tags[tag]
        require(
            existing == null ||
                (existing.first == sha &&
                    existing.second.number == receipt.number &&
                    existing.second.ascBuildId == receipt.ascBuildId)
        ) {
            "Receipt collision: $tag"
        }
        if (existing == null) tags[tag] = sha to receipt
    }
}

// Actions artifacts across runs; each run sees only its own when looking for its build.
class FakeArtifacts {
    val retained = linkedMapOf<String, Pair<String, Map<String, ByteArray>>>()
    val expired = mutableSetOf<String>()
    var corrupt = false

    fun retain(run: String, build: Build, directory: Path) {
        retained[requireNotNull(build.artifact).artifactName] =
            run to localPackage(build, directory)
    }

    // A retained build with dSYMs, as an earlier delivery run left it.
    fun retain(
        version: String,
        number: String,
        sha: String,
        symbols: String = "dsym-$number",
    ) {
        val ipa = "ipa".toByteArray()
        val dsym = symbols.toByteArray()
        val name = artifactName(BuildNumber(number))
        val manifest =
            ArtifactManifest(
                sha = sha,
                version = MarketingVersion(version),
                number = BuildNumber(number),
                workflow = "https://github.com/owner/repo/actions/runs/1",
                artifactName = name,
                ipa = ArtifactFile("app.ipa", digest(ipa), ipa.size.toLong()),
                symbols = ArtifactFile("symbols.zip", digest(dsym), dsym.size.toLong()),
            )
        retained[name] =
            "run-$number" to
                mapOf(
                    "app.ipa" to ipa,
                    "symbols.zip" to dsym,
                    "manifest.json" to json.encodeToString(manifest).toByteArray(),
                )
    }

    private fun found(name: String) =
        retained[name]?.let { RetainedBuild(name, it.first, name in expired) }

    fun forRun(run: String) =
        object : BuildArtifacts {
            override fun find(name: String) = found(name)

            override fun retainedByThisRun() =
                retained
                    .filterValues { it.first == run }
                    .keys
                    .mapNotNull(::found)
                    .maxByOrNull { it.number.attempt }

            override fun download(build: RetainedBuild, output: Path): RetainedPackage {
                require(build.name !in expired) { "Artifact expired" }
                val files = retained.getValue(build.name).second.toMutableMap()
                if (corrupt) files["app.ipa"] = "corrupt".toByteArray()
                val manifest =
                    json.decodeFromString<ArtifactManifest>(
                        files.getValue("manifest.json").decodeToString()
                    )
                validatePackage(manifest, files)
                output.createDirectories()
                files.forEach { (file, bytes) -> output.resolve(file).writeBytes(bytes) }
                return RetainedPackage(output.resolve(manifest.ipa.name), manifest)
            }
        }
}

// Releases on tags with their assets' bytes. `lostResponses` makes writes succeed but throw.
class FakeReleases : ReleaseAssets {
    val releases = linkedMapOf<String, MutableMap<String, ByteArray>>()
    val writes = mutableListOf<String>()
    var lostResponses = false

    override fun assets(tag: String) = releases[tag]?.mapValues { digest(it.value) }

    override fun create(tag: String, title: String, notes: String, files: List<Path>) {
        require(tag !in releases) { "Release exists: $tag" }
        writes += "create"
        releases[tag] = files.associate { it.name to it.readBytes() }.toMutableMap()
        if (lostResponses) throw CommandError("Response lost: create")
    }

    override fun upload(tag: String, files: List<Path>) {
        val release = releases.getValue(tag)
        files.forEach {
            require(it.name !in release) { "Asset exists: ${it.name}" }
            writes += "upload ${it.name}"
            release[it.name] = it.readBytes()
        }
        if (lostResponses) throw CommandError("Response lost: upload")
    }
}

class FakeSource : Source {
    var shas = listOf("source-sha")

    override fun commits(baseline: String, tip: String) = shas

    override fun count(sha: String) = 234 + shas.indexOf(sha).coerceAtLeast(0)

    override fun commitWithCount(count: Int) = shas.getOrNull(count - 234)

    // `shas` is linear history, oldest first.
    override fun isAncestor(ancestor: String, descendant: String) =
        shas.indexOf(ancestor).let { it >= 0 && it <= shas.indexOf(descendant) }

    val notesSince = mutableListOf<String>()

    override fun notes(base: String, tip: String) =
        "Customer-facing changes".also { notesSince += base }

    override fun <T> snapshot(sha: String, action: (Path) -> T): T =
        action(Path.of("/fixture", sha))
}

class FakeBuilds : BuildOperations {
    var environmentError: Exception? = null
    val verified = mutableListOf<String>()
    val archived = mutableListOf<String>()
    var verifyError: Exception? = null
    var archiveError: Exception? = null

    // Runs while verification is under way, which in reality takes hours.
    var duringVerify: () -> Unit = {}

    override fun checkEnvironment() {
        environmentError?.let { throw it }
    }

    override fun verify(source: Path, output: Path) {
        verified += source.fileName.toString()
        duringVerify()
        verifyError?.let { throw it }
    }

    override fun archive(source: Path, output: Path, build: Build): Path {
        archived += build.sha
        archiveError?.let { throw it }
        build.ipaSha256 = digest("ipa".toByteArray())
        build.ipaBytes = 3
        build.artifact =
            ArtifactManifest(
                sha = build.sha,
                version = build.version,
                number = build.number,
                workflow = requireNotNull(build.workflow),
                artifactName = artifactName(build.number),
                ipa = ArtifactFile("app.ipa", requireNotNull(build.ipaSha256), 3),
                symbolsAbsentReason = "fixture",
            )
        output.createDirectories().resolve("app.ipa").writeText("ipa")
        output.resolve("manifest.json").writeText(json.encodeToString(build.artifact))
        return output.resolve("app.ipa")
    }
}

class FakeIncidents : Incidents {
    val synced = mutableListOf<List<SweepFinding>>()
    var failure = false

    override fun sync(findings: List<SweepFinding>, tip: String, checked: (String) -> Boolean) {
        if (failure) throw CommandError("issue unavailable")
        synced += findings
    }
}

// Every source of truth a delivery reads, in memory. Each job gets a fresh run ID, like Actions.
class World(val output: Path, val at: Clock = clock) {
    val apple = FakeApple()
    val tags = FakeTags()
    val artifacts = FakeArtifacts()
    val source = FakeSource()
    val builds = FakeBuilds()
    val incidents = FakeIncidents()
    val queued = mutableSetOf<String>()
    private var runs = 10

    fun env(run: String) =
        mapOf(
            "GITHUB_REPOSITORY" to "owner/repo",
            "GITHUB_RUN_ID" to run,
            "IOS_APPSTORE_ENABLED" to "true",
        )

    fun beta(run: String = "${runs++}") =
        BetaDelivery(config, apple, tags, artifacts.forRun(run), source, builds, env(run), at)

    fun symbols() = ReleaseSymbols(artifacts.forRun("release"), FakeReleases(), output)

    fun sweep(incidents: Incidents = this.incidents) =
        Sweep(
            config,
            beta(),
            AppStoreRelease(config, apple, tags, source, symbols(), env("sweep"), at),
            apple,
            tags,
            source,
            incidents,
            { it in queued },
            WorkflowHints(env("sweep")),
        )

    // One delivery job: decide, build, retain, upload, as the workflow runs them.
    fun job(operation: Operation, run: String = "${runs++}") {
        val beta = beta(run)
        val decision = beta.start(operation)
        if (decision is StartDecision.Refused) error(decision.reason)
        if (decision !is StartDecision.Deliver) return
        beta.build(operation, output)?.let {
            artifacts.retain(run, it, output.resolve(operation.sha))
        }
        beta.upload(operation, output)
    }

    fun deliver(sha: String) = job(Operation.Deliver(sha))

    fun retry(sha: String, reason: String = "Recover") =
        job(Operation.Retry(sha, beta().traces(sha).expectedAttempt, reason))
}

// Tests run in a throwaway directory; JUnit test methods must return nothing.
fun temp(action: (Path) -> Unit) {
    val root = createTempDirectory("delivery-test-")
    try {
        action(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}
