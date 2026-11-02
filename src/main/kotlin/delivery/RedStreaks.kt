package delivery

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class RedStreakCommit(
    val sha: String,
    val subject: String,
    val runUrl: String,
    val runAttempt: Int = 1,
)

internal fun redStreakCommits(body: String, owner: String): List<RedStreakCommit> {
    val content = OwnedStatusBlock(owner).content(body) ?: return emptyList()
    val marker = "<!-- $owner-red-commits: "
    if (marker !in content) return emptyList()
    return json.decodeFromString(content.substringAfter(marker).substringBefore(" -->"))
}

private fun redStreakText(text: String) =
    text.replace("\r", " ").replace("\n", " ").replace("<", "&lt;").replace(">", "&gt;")

@Serializable
internal data class SuspectCommit(
    val sha: String,
    val subject: String,
    val untested: Boolean = false,
)

@Serializable
internal data class SuspectRange(
    val base: String,
    val firstRed: String,
    val commits: List<SuspectCommit>,
    val fromBaseline: Boolean = false,
)

internal data class FailureChange(val failure: TestFailure, val status: String)

// Messages and source line numbers can change while the same test remains broken.
internal fun diffFailures(
    previous: List<TestFailure>,
    current: List<TestFailure>,
): List<FailureChange> {
    fun identity(failure: TestFailure) = Triple(failure.test, failure.file, failure.command)
    val before = previous.map(::identity).toSet()
    val after = current.map(::identity).toSet()
    return current.distinctBy(::identity).map {
        FailureChange(it, if (identity(it) in before) "still failing" else "new")
    } +
        previous
            .distinctBy(::identity)
            .filter { identity(it) !in after }
            .map {
                FailureChange(it, "now passing")
            }
}

internal fun renderRedStreak(
    commits: List<RedStreakCommit>,
    failures: List<TestFailure>,
    previous: List<TestFailure> = emptyList(),
    suspects: SuspectRange? = null,
    repo: String = "",
): String = buildString {
    append("Commits tested red:\n")
    append(
        commits.joinToString("\n") {
            "- ${it.sha.take(12)} ${redStreakText(it.subject)} — Run: ${it.runUrl}"
        }
    )
    append("\n\n## Failing tests\n")
    append(
        diffFailures(previous, failures).joinToString("\n") { (failure, status) ->
            val location =
                failure.file?.let { "$it:${failure.line ?: "?"}" } ?: "location unavailable"
            "- **$status** ${redStreakText(failure.test)} — ${redStreakText(failure.message)} — ${redStreakText(location)}"
        }
    )
    append("\n\n## Suspect commits\n")
    if (suspects != null) {
        append(
            "Range: ${if (suspects.fromBaseline) "baseline " else ""}${suspects.base}..${suspects.firstRed}\n\n"
        )
        append(
            "[Compare](https://github.com/$repo/compare/${suspects.base}...${suspects.firstRed})\n\n"
        )
        append(
            suspects.commits.joinToString("\n") {
                "- ${it.sha.take(12)} ${redStreakText(it.subject)}" +
                    if (it.untested) " — untested" else ""
            }
        )
        append("\n\nCommits marked untested were superseded and never verified.")
    } else append("Range unavailable for this older update.")
}

@Serializable
private data class RedStreakEvidence(val failures: List<TestFailure>, val suspects: SuspectRange?)

class RedStreaks(
    private val github: GitHub,
    private val owner: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val marker = "<!-- $owner:${IncidentSubject.RedStreak.key} -->"
    private val status = OwnedStatusBlock(owner)
    private val start = status.start
    private val end = status.end
    private val stateStart = "<!-- $owner-red-commits: "

    private fun open(): JsonObject? {
        val issues =
            github.pages("issues?labels=$INCIDENT_LABEL&state=open").filter {
                "pull_request" !in it &&
                    marker in it["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        require(issues.size <= 1) { "Duplicate owned red-streak incident" }
        return issues.singleOrNull()
    }

    internal fun covering(sha: String): String? {
        val issue = open() ?: return null
        val recorded =
            redStreakCommits(issue.string("body"), owner).lastOrNull { it.sha == sha }
                ?: return null
        val latest =
            github
                .pages("actions/runs?head_sha=$sha", "workflow_runs")
                .filter { github.runUsesWorkflow(it, "testflight") }
                .maxWithOrNull(
                    compareBy<JsonObject> { it.string("id").toLong() }
                        .thenBy { it["run_attempt"]?.jsonPrimitive?.intOrNull ?: 1 }
                )
        if (
            latest != null &&
                (latest.string("id") != recorded.runUrl.substringAfterLast('/') ||
                    (latest["run_attempt"]?.jsonPrimitive?.intOrNull ?: 1) != recorded.runAttempt)
        )
            return null
        return "https://github.com/${github.repo}/issues/${issue.string("number")}"
    }

    internal fun closeDelivered(sha: String, build: String) {
        val issue = open() ?: return
        val original = issue["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = status.content(original)
        val commits =
            content
                ?.takeIf { stateStart in it }
                ?.let {
                    json.decodeFromString<List<RedStreakCommit>>(
                        it.substringAfter(stateStart).substringBefore(" -->")
                    )
                }
        val started = issue["created_at"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
        val duration =
            started?.let {
                Duration.between(it, clock.instant()).coerceAtLeast(Duration.ZERO).toString()
            } ?: "unknown (issue creation time unavailable)"
        val resolution =
            "## Resolution\nfixed by `$sha`, delivered `$build`\n\nDuration: $duration\nCommits tested red: ${commits?.distinctBy { it.sha }?.size ?: 1}"
        val history = content.orEmpty().replaceFirst("State: OPEN", "State: RESOLVED").trim()
        val block =
            if (content == null) "$start\nState: RESOLVED\n\n$resolution\n$end"
            else "$start\n$history\n\n$resolution\n$end"
        github.api(
            "issues/${issue.string("number")}",
            buildJsonObject {
                put("body", status.replace(original, block))
                put("state", "closed")
                put("state_reason", "completed")
            },
            method = "PATCH",
        )
    }

    internal fun sync(
        commit: RedStreakCommit,
        failures: List<TestFailure>,
        suspectRange: (String) -> SuspectRange = { SuspectRange("baseline", it, emptyList()) },
    ) {
        val issue = open()
        val original = issue?.get("body")?.jsonPrimitive?.contentOrNull ?: "$marker\n"
        val content = status.content(original)
        // The create-only version stored one short SHA and no status delimiters.
        val legacy =
            if (content == null)
                Regex(
                        "(?m)^Red commit: ([a-f0-9]{12}) ([^\\n]*)\\n\\nRun: ([^\\n]*)\\n\\nFailing tests:\\n(?:- [^\\n]*(?:\\n|$))*"
                    )
                    .find(original)
            else null
        val commits =
            if (content != null && stateStart in content) {
                json.decodeFromString<List<RedStreakCommit>>(
                    content.substringAfter(stateStart).substringBefore(" -->")
                )
            } else
                legacy
                    ?.let {
                        listOf(
                            RedStreakCommit(it.groupValues[1], it.groupValues[2], it.groupValues[3])
                        )
                    }
                    .orEmpty()
        val updatedCommits = commits.toMutableList()
        val index = updatedCommits.indexOfFirst {
            it.sha == commit.sha || (it.sha.length == 12 && commit.sha.startsWith(it.sha))
        }
        if (index < 0) updatedCommits += commit else updatedCommits[index] = commit
        val evidenceStart = "<!-- $owner-red-evidence: "
        val evidence =
            content
                ?.takeIf { evidenceStart in it }
                ?.let {
                    json.decodeFromString<RedStreakEvidence>(
                        it.substringAfter(evidenceStart).substringBefore(" -->")
                    )
                }
        if (updatedCommits == commits && evidence?.failures == failures) return
        val suspects = evidence?.suspects ?: suspectRange(updatedCommits.first().sha)
        val evidenceState =
            Json.encodeToString(RedStreakEvidence(failures, suspects))
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
        val state =
            Json.encodeToString(updatedCommits).replace("<", "\\u003c").replace(">", "\\u003e")
        val block =
            "$start\nState: OPEN\n\n${renderRedStreak(updatedCommits, failures, evidence?.failures.orEmpty(), suspects, github.repo)}\n\n$stateStart$state -->\n$evidenceStart$evidenceState -->\n$end"
        val updated =
            if (legacy == null) status.replace(original, block)
            else
                original.replaceRange(
                    legacy.range.first,
                    legacy.range.first + legacy.value.trimEnd('\n').length,
                    block,
                )
        if (issue != null) {
            if (updated != original)
                github.api(
                    "issues/${issue.string("number")}",
                    buildJsonObject { put("body", updated) },
                    method = "PATCH",
                )
            return
        }
        github.ensureDeliveryLabels()
        val write = attemptEffect {
            github.api(
                "issues",
                buildJsonObject {
                    put("title", IncidentSubject.RedStreak.title)
                    put("body", updated)
                    putJsonArray("labels") { add(INCIDENT_LABEL) }
                },
            )
        }
        // A response lost after GitHub accepts the issue must not cause a duplicate write.
        if (write.isSuccess || open() != null) return
        write.getOrThrow()
    }
}
