package delivery

import java.nio.file.Path

// One build upload as App Store Connect records it. Apple keeps abandoned uploads in
// AWAITING_UPLOAD indefinitely.
data class AppleUpload(
    val version: String,
    val number: String,
    val state: String,
    val code: String? = null,
    val reason: String? = null,
    val uploadedAt: String? = null,
) {
    // Apple has the file: it is processing or processed it, or refused a repeat of a number it
    // already has (ITMS-90189, redundant binary upload).
    val received: Boolean
        get() = state in setOf("PROCESSING", "COMPLETE", "SUCCEEDED", "SUCCESS") || code == "90189"
}

// Of several uploads of one build number, the one that got furthest decides its outcome: Apple
// can still be processing the first while it refuses a repeat. Apple's list order means nothing.
fun List<AppleUpload>.decisive(): AppleUpload? =
    maxWithOrNull(
        compareBy<AppleUpload>({ it.received }, { it.state == "FAILED" }, { it.uploadedAt })
    )

// What testers see under "What to Test"; the commit also lets a sweep place a missing receipt.
fun testerNotes(config: DeliveryConfig, sha: String) = config.testerNotes.trim() + "\n\nCommit $sha"

// App Store Connect may return notes with other line breaks, trailing spaces or blank lines
// dropped, so notes match when their non-blank lines do.
fun sameNotes(returned: String?, sent: String): Boolean {
    fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return returned != null && lines(returned) == lines(sent)
}

fun commitInNotes(notes: String?) = notes?.let {
    Regex("Commit ([0-9a-f]{40})").find(it)?.groupValues?.get(1)
}

// TestFlight will never make this build installable again (expired, invalid or failed).
class TerminalTestFlightState(val state: String) : CommandError("Terminal TestFlight state: $state")

data class AppleBuild(val id: String, val processingState: String, val expired: Boolean)

// A TestFlight build as App Store Connect lists it, with its marketing version.
data class TestFlightBuild(
    val id: String,
    val version: String,
    val number: String,
    val processingState: String,
    val expired: Boolean,
    val uploadedAt: String? = null,
) {
    val count: Int?
        get() = number.substringBefore('.').toIntOrNull()

    val attempt: Int
        get() = number.substringAfter('.', "0").toIntOrNull() ?: 0
}

// Apple's 7-day phased release of one App Store version.
data class Rollout(val id: String, val state: String, val day: Int?, val pausedDays: Int) {
    val percent: Int?
        get() = day?.let { listOf(1, 2, 5, 10, 20, 50, 100).getOrNull(it - 1) ?: 100 }
}

data class StoreVersion(
    val id: String,
    val version: String,
    val state: String,
    val buildId: String?,
    val buildNumber: String?,
    val rollout: Rollout?,
)

// An App Review submission. `asc review submit` creates one, adds the version and sends it in
// separate calls, so a cut-off submit can leave one created but never sent (READY_FOR_REVIEW).
data class ReviewSubmission(val id: String, val state: String)

interface Apple {
    fun uploads(): List<AppleUpload>

    fun lookup(version: MarketingVersion, number: BuildNumber): AppleBuild?

    // In the internal group and IN_BETA_TESTING: testers can install it.
    fun testable(buildId: String): Boolean

    fun notes(buildId: String): String?

    fun distribute(buildId: String, notes: String, output: Path)

    fun upload(ipa: Path, output: Path)

    fun testFlightBuilds(): List<TestFlightBuild>

    fun storeVersions(): List<StoreVersion>

    fun reviewSubmissions(): List<ReviewSubmission>

    fun validateVersion(id: String)

    fun createVersion(version: MarketingVersion): String

    fun renameVersion(id: String, version: MarketingVersion)

    fun stage(version: MarketingVersion, buildId: String, template: String, notes: String): String

    fun requestPhasedRelease(versionId: String)

    fun removePhasedRelease(rolloutId: String)

    fun setRollout(rolloutId: String, state: String)

    fun submit(versionId: String, buildId: String)
}
