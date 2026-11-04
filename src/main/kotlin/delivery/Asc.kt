package delivery

import java.nio.file.Path
import kotlinx.serialization.json.*

// asc returns some single resources flat and others inside a JSON:API `data` object.
private fun JsonElement.resourceId(): String =
    (jsonObject["data"]?.jsonObject ?: jsonObject).string("id")

class Asc(
    private val config: DeliveryConfig,
    private val commands: Commands,
    env: Map<String, String>,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val nanoTime: () -> Long = System::nanoTime,
) : Apple {
    val ignoredVersions = linkedSetOf<String>()

    private val recoveryEnvironment = env

    private val environment =
        toolEnvironment(env, "ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_PRIVATE_KEY") +
            mapOf("ASC_STRICT_AUTH" to "true")

    private var credentialConfirmed = false

    private fun invoke(args: List<String>, log: Path? = null): String {
        try {
            return executeAsc(commands, args, environment, log).also {
                if (!credentialConfirmed) {
                    resolveCredentialIncidents(
                        setOf("ASC_PRIVATE_KEY"),
                        config,
                        commands,
                        recoveryEnvironment,
                    )
                    credentialConfirmed = true
                }
            }
        } catch (error: AscError) {
            throw error.credentialProblem() ?: error
        }
    }

    private fun call(vararg args: String): JsonElement =
        json.parseToJsonElement(invoke(listOf(*args, "--output", "json")))

    private fun logged(args: List<String>, log: Path) {
        invoke(args, log)
    }

    override fun uploads() =
        call(
                "builds",
                "uploads",
                "list",
                "--app",
                config.appId,
                "--platform",
                "IOS",
                "--limit",
                "200",
                "--paginate",
            )
            .jsonObject
            .getValue("data")
            .jsonArray
            .map {
                val attrs = it.jsonObject.getValue("attributes").jsonObject
                val status = attrs.getValue("state").jsonObject
                val error = status["errors"]?.jsonArray?.firstOrNull()?.jsonObject
                AppleUpload(
                    attrs.string("cfBundleShortVersionString"),
                    attrs.string("cfBundleVersion"),
                    status.string("state"),
                    error?.get("code")?.jsonPrimitive?.content,
                    error?.get("description")?.jsonPrimitive?.content?.take(1000),
                    attrs["uploadedDate"]?.jsonPrimitive?.contentOrNull,
                )
            }

    override fun lookup(version: MarketingVersion, number: BuildNumber): AppleBuild? {
        val rows =
            call(
                    "builds",
                    "list",
                    "--app",
                    config.appId,
                    "--version",
                    version.value,
                    "--build-number",
                    number.value,
                    "--platform",
                    "IOS",
                    "--processing-state",
                    "all",
                    "--paginate",
                )
                .jsonObject
                .getValue("data")
                .jsonArray
        require(rows.size <= 1) { "Ambiguous ASC build identity" }
        return rows.singleOrNull()?.jsonObject?.let { row ->
            val attrs = row.getValue("attributes").jsonObject
            AppleBuild(
                row.string("id"),
                attrs.string("processingState"),
                attrs["expired"]?.jsonPrimitive?.boolean ?: false,
            )
        }
    }

    override fun upload(ipa: Path, output: Path) =
        logged(
            listOf(
                "publish",
                "testflight",
                "--app",
                config.appId,
                "--ipa",
                ipa.toString(),
                "--upload-only",
                "--wait",
                "--timeout",
                "30m",
            ),
            output.resolve("upload.log"),
        )

    override fun distribute(buildId: String, notes: String, output: Path) {
        logged(
            listOf(
                "builds",
                "wait",
                "--build-id",
                buildId,
                "--timeout",
                "20m",
                "--fail-on-invalid",
            ),
            output.resolve("processing.log"),
        )
        logged(
            listOf("builds", "update", "--build-id", buildId, "--uses-non-exempt-encryption=false"),
            output.resolve("distribution.log"),
        )
        val rows =
            call("builds", "test-notes", "list", "--build-id", buildId)
                .jsonObject
                .getValue("data")
                .jsonArray
        val operation =
            if (
                rows.any {
                    it.jsonObject.getValue("attributes").jsonObject.string("locale") == "en-US"
                }
            )
                "update"
            else "create"
        call(
            "builds",
            "test-notes",
            operation,
            "--build-id",
            buildId,
            "--locale",
            "en-US",
            "--whats-new",
            notes,
        )
        if (!inGroup(buildId))
            call("builds", "add-groups", "--build-id", buildId, "--group", config.internalGroup)
        val deadline = nanoTime() + 600_000_000_000L
        repeat(41) { poll ->
            if (testable(buildId) && sameNotes(notes(buildId), notes)) return
            val remaining = (deadline - nanoTime()) / 1_000_000
            if (remaining <= 0 || poll == 40) error("TestFlight readiness timed out")
            pause(minOf(15_000, remaining))
        }
    }

    private fun inGroup(buildId: String) =
        call("builds", "groups", "list", "--build-id", buildId)
            .jsonObject
            .getValue("groups")
            .jsonArray
            .any { it.jsonObject.string("id") == config.internalGroup }

    override fun testable(buildId: String): Boolean {
        val state =
            call("builds", "build-beta-detail", "view", "--build-id", buildId)
                .jsonObject
                .getValue("data")
                .jsonObject
                .getValue("attributes")
                .jsonObject
                .string("internalBuildState")
        if (state in setOf("EXPIRED", "INVALID", "PROCESSING_EXCEPTION"))
            throw TerminalTestFlightState(state)
        return state == "IN_BETA_TESTING" && inGroup(buildId)
    }

    override fun notes(buildId: String) =
        call("builds", "test-notes", "list", "--build-id", buildId)
            .jsonObject
            .getValue("data")
            .jsonArray
            .map { it.jsonObject.getValue("attributes").jsonObject }
            .singleOrNull { it.string("locale") == "en-US" }
            ?.get("whatsNew")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun included(document: JsonObject): Map<Pair<String, String>, JsonObject> =
        document["included"]?.jsonArray.orEmpty().associate {
            val row = it.jsonObject
            (row.string("type") to row.string("id")) to row.getValue("attributes").jsonObject
        }

    private fun related(row: JsonObject, name: String): String? =
        row["relationships"]
            ?.jsonObject
            ?.get(name)
            ?.jsonObject
            ?.get("data")
            ?.takeUnless { it == JsonNull }
            ?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotEmpty() }

    override fun testFlightBuilds(): List<TestFlightBuild> {
        val document =
            call(
                    "builds",
                    "list",
                    "--app",
                    config.appId,
                    "--platform",
                    "IOS",
                    "--processing-state",
                    "all",
                    "--include",
                    "preReleaseVersion",
                    "--limit",
                    "200",
                    "--paginate",
                )
                .jsonObject
        val versions = included(document)
        return document.getValue("data").jsonArray.map {
            val row = it.jsonObject
            val attrs = row.getValue("attributes").jsonObject
            TestFlightBuild(
                row.string("id"),
                related(row, "preReleaseVersion")
                    ?.let { id -> versions["preReleaseVersions" to id]?.string("version") }
                    .orEmpty(),
                attrs.string("version"),
                attrs.string("processingState"),
                attrs["expired"]?.jsonPrimitive?.boolean ?: false,
                attrs["uploadedDate"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override fun storeVersions(): List<StoreVersion> {
        val document =
            call(
                    "versions",
                    "list",
                    "--app",
                    config.appId,
                    "--platform",
                    "IOS",
                    "--include",
                    "build,appStoreVersionPhasedRelease",
                    "--limit",
                    "200",
                    "--paginate",
                )
                .jsonObject
        val related = included(document)
        return document
            .getValue("data")
            .jsonArray
            .map {
                val row = it.jsonObject
                val attrs = row.getValue("attributes").jsonObject
                val build = related(row, "build")
                StoreVersion(
                    row.string("id"),
                    attrs["versionString"]?.jsonPrimitive?.content.orEmpty(),
                    attrs.string("appStoreState"),
                    build,
                    build?.let { related["builds" to it]?.string("version") },
                    related(row, "appStoreVersionPhasedRelease")?.let { id ->
                        related["appStoreVersionPhasedReleases" to id]?.let { phased ->
                            Rollout(
                                id,
                                phased.string("phasedReleaseState"),
                                phased["currentDayNumber"]?.jsonPrimitive?.intOrNull,
                                phased["totalPauseDuration"]?.jsonPrimitive?.intOrNull ?: 0,
                            )
                        }
                    },
                )
            }
            .also { versions ->
                ignoredVersions +=
                    versions.filterNot { MarketingVersion.accepts(it.version) }.map { it.version }
            }
    }

    override fun reviewSubmissions() =
        call(
                "review",
                "submissions-list",
                "--app",
                config.appId,
                "--platform",
                "IOS",
                "--limit",
                "200",
                "--paginate",
            )
            .jsonObject
            .getValue("data")
            .jsonArray
            .map {
                val row = it.jsonObject
                ReviewSubmission(
                    row.string("id"),
                    row.getValue("attributes").jsonObject.string("state"),
                )
            }

    override fun validateVersion(id: String) {
        call("validate", "--app", config.appId, "--version-id", id)
    }

    override fun createVersion(version: MarketingVersion) =
        call(
                "versions",
                "create",
                "--app",
                config.appId,
                "--version",
                version.value,
                "--platform",
                "IOS",
                "--release-type",
                "AFTER_APPROVAL",
            )
            .resourceId()

    override fun renameVersion(id: String, version: MarketingVersion) {
        call("versions", "update", "--version-id", id, "--version", version.value)
    }

    override fun stage(
        version: MarketingVersion,
        buildId: String,
        template: String,
        notes: String,
    ): String {
        // `release stage` attaches the build and copies the metadata from `template`.
        call(
            "release",
            "stage",
            "--app",
            config.appId,
            "--version",
            version.value,
            "--build-id",
            buildId,
            "--copy-metadata-from",
            template,
            "--confirm",
        )
        val id =
            call("versions", "view", "--app", config.appId, "--version", version.value).resourceId()
        call("localizations", "update", "--version", id, "--locale", "en-US", "--whats-new", notes)
        call("versions", "update", "--version-id", id, "--release-type", "AFTER_APPROVAL")
        call("validate", "--app", config.appId, "--version-id", id)
        return id
    }

    override fun requestPhasedRelease(versionId: String) {
        call(
            "versions",
            "phased-release",
            "create",
            "--version-id",
            versionId,
            "--state",
            "INACTIVE",
        )
    }

    override fun removePhasedRelease(rolloutId: String) {
        call("versions", "phased-release", "delete", "--id", rolloutId, "--confirm")
    }

    // asc asks for confirmation before a rollout reaches everyone at once.
    override fun setRollout(rolloutId: String, state: String) {
        val confirm = if (state == "COMPLETE") arrayOf("--confirm") else emptyArray()
        call("versions", "phased-release", "update", "--id", rolloutId, "--state", state, *confirm)
    }

    override fun submit(versionId: String, buildId: String) {
        call(
            "review",
            "submit",
            "--app",
            config.appId,
            "--version-id",
            versionId,
            "--build-id",
            buildId,
            "--confirm",
        )
    }
}
