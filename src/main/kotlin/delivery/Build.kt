package delivery

import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
@JvmInline
value class MarketingVersion(val value: String) {
    init {
        require(Regex("\\d{2}\\.(0[1-9]|1[0-2])\\.(0[1-9]|[1-9]\\d)").matches(value)) {
            "Invalid marketing version: $value"
        }
    }

    companion object {
        fun accepts(value: String) = runCatching { MarketingVersion(value) }.isSuccess
    }

    val revision: Int
        get() = value.substringAfterLast('.').toInt()

    override fun toString() = value
}

@Serializable
@JvmInline
value class BuildNumber(val value: String) {
    init {
        require(Regex("[1-9]\\d{0,3}\\.[1-9]\\d?").matches(value)) {
            "Invalid build number: $value"
        }
    }

    val count: Int
        get() = value.substringBefore('.').toInt()

    val attempt: Int
        get() = value.substringAfter('.').toInt()

    override fun toString() = value
}

// One archived IPA for one commit. Nothing about it is stored; its identity and package
// manifest travel with the retained artifact and end up in the receipt.
@Serializable
data class Build(
    val sha: String,
    val version: MarketingVersion,
    val number: BuildNumber,
    var workflow: String? = null,
    var xcode: String? = null,
    var asc: String? = null,
    var ipaSha256: String? = null,
    var ipaBytes: Long? = null,
    var artifact: ArtifactManifest? = null,
)

object Policy {
    // New builds use the month's `YY.MM.RR` one past the highest version of that month Apple
    // already has submitted or shipped; each submission closes its train.
    fun marketingVersion(now: ZonedDateTime, closed: Set<String>): MarketingVersion {
        val prefix = "%02d.%02d.".format(Locale.ROOT, now.year % 100, now.monthValue)
        val revision =
            closed
                .filter { MarketingVersion.accepts(it) && it.startsWith(prefix) }
                .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
                .maxOrNull()
                ?.plus(1) ?: 1
        require(revision <= 99) { "Monthly release number exceeds YY.MM.RR" }
        return MarketingVersion(prefix + "%02d".format(Locale.ROOT, revision))
    }

    fun buildNumber(count: Int, attempt: Int): BuildNumber {
        require(count in 1..9999) { "Commit count exceeds the supported Apple build-number range" }
        require(attempt in 1..99) {
            "Upload attempt exceeds the supported Apple build-number range"
        }
        return BuildNumber("$count.$attempt")
    }
}
