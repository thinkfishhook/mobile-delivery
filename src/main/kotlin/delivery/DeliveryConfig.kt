package delivery

import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Every delivery job shares this environment and queued concurrency group; the group is the run
// lock. Concurrency groups are per repository, so apps never queue behind each other.
const val DELIVERY_ENVIRONMENT = "ios-delivery"

@Serializable data class AscAsset(val name: String, val sha256: String)

@Serializable data class JdkAsset(val url: String, val sha256: String)

@Serializable data class JdkToolchain(val version: String, val assets: Map<String, JdkAsset>)

// The asc release the tool is written against ships with the tool, not with each app.
@Serializable
data class Toolchain(
    val asc: String,
    @SerialName("asc_assets") val ascAssets: Map<String, AscAsset>,
    val jdk: JdkToolchain,
) {
    companion object {
        val bundled by lazy { json.decodeFromString<Toolchain>(resource("tool.json")) }
    }
}

@Serializable
data class XcodeApp(
    // The .xcodeproj holding the app target; its signing is overlaid for the archive.
    val project: String,
    // Archive through this workspace instead (CocoaPods or multi-project apps).
    val workspace: String? = null,
    val scheme: String,
    // The application target whose Release configuration is signed for distribution.
    val target: String,
    // File name for the archive and IPA.
    val name: String,
)

@Serializable
data class VerificationConfig(
    // Each command runs in order in a clean snapshot of the commit, without deployment secrets.
    val commands: List<List<String>>,
    // JUnit report directories (TEST-*.xml) or .xcresult bundles the commands write. Each must
    // report at least one test and no failures; they are deleted before the commands run.
    @SerialName("test_reports") val testReports: List<String>,
    // Extra directories kept with the run's evidence.
    val evidence: List<String> = emptyList(),
)

@Serializable
data class ReleaseNotes(
    // Markdown files added or changed here since the last delivery become the TestFlight notes.
    val directory: String = "release-notes.d",
    val fallback: String,
)

@Serializable
data class DeliveryConfig(
    @SerialName("app_id") val appId: String,
    @SerialName("bundle_id") val bundleId: String,
    @SerialName("team_id") val teamId: String,
    @SerialName("internal_group") val internalGroup: String,
    val baseline: String,
    val timezone: String,
    val xcode: String,
    val ios: XcodeApp,
    val verification: VerificationConfig,
    @SerialName("tester_notes") val testerNotes: String,
    @SerialName("release_notes") val releaseNotes: ReleaseNotes,
    // Owner marker in incident issue bodies. Change it only while no incident is open.
    @SerialName("incident_marker") val incidentMarker: String = "mobile-delivery",
    // Android SDK packages installed before verification (Kotlin Multiplatform apps).
    @SerialName("android_packages") val androidPackages: List<String> = emptyList(),
    @SerialName("build_environment") val buildEnvironment: List<String> = emptyList(),
    // Optional public literals specific to this app's executable, never tool-owned SDK keys.
    @SerialName("binary_checks") val binaryChecks: BinaryChecks = BinaryChecks(),
    @Transient val toolchain: Toolchain = Toolchain.bundled,
) {
    init {
        requireBuildEnvironmentNames(buildEnvironment)
        require(verification.commands.isNotEmpty() && verification.commands.none { it.isEmpty() }) {
            "verification.commands needs at least one non-empty command"
        }
        require(verification.testReports.isNotEmpty()) {
            "verification.test_reports needs at least one report: delivery requires passing tests"
        }
        (verification.testReports + verification.evidence + releaseNotes.directory).forEach {
            require(it.isNotBlank() && !it.startsWith("/") && ".." !in it.split('/')) {
                "$it must be a path inside the repository"
            }
        }
        require(Regex("[a-z0-9-]+").matches(incidentMarker)) {
            "incident_marker may use only lowercase letters, digits and dashes"
        }
        ZoneId.of(timezone)
    }

    val asc: String
        get() = toolchain.asc

    val ascAssets: Map<String, AscAsset>
        get() = toolchain.ascAssets

    fun now(clock: Clock = Clock.systemUTC()): ZonedDateTime =
        ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(timezone))

    companion object {
        const val DEFAULT_PATH = ".github/delivery.json"

        // Unknown keys are refused, so a misspelt setting cannot fall back to a default.
        fun parse(text: String, location: String = DEFAULT_PATH): DeliveryConfig {
            requireConfiguration(configurationProblems(text), location)
            return json.decodeFromString<DeliveryConfig>(text)
        }

        fun load(root: Path, env: Map<String, String>, mode: String? = null): DeliveryConfig {
            val name = env["DELIVERY_CONFIG"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PATH
            val path = root.resolve(name)
            val text = if (path.exists()) path.readText() else null
            val buildEnvironment =
                text
                    ?.let {
                        runCatching { json.decodeFromString<DeliveryConfig>(it).buildEnvironment }
                            .getOrDefault(emptyList())
                    }
                    .orEmpty()
            val problems =
                (text?.let(::configurationProblems) ?: listOf("configuration file is missing"))
                    .map { "$name: $it" } +
                    (mode?.let { workflowProblems(it, env, buildEnvironment) } ?: emptyList())
            require(problems.isEmpty()) { problems.joinToString("\n", "Configuration problems:\n") }
            return json.decodeFromString<DeliveryConfig>(
                requireNotNull(text) { "Expected delivery configuration at $path; file is missing" }
            )
        }
    }
}

fun resource(name: String): String =
    requireNotNull(DeliveryConfig::class.java.getResource("/$name")) {
            "Engine resource /$name is missing; reinstall mobile-delivery from the pinned source"
        }
        .readText()
