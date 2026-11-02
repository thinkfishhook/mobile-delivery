package delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class RedStreakCommit(val sha: String, val subject: String, val runUrl: String)

private fun redStreakText(text: String) =
    text.replace("\r", " ").replace("\n", " ").replace("<", "&lt;").replace(">", "&gt;")

internal fun renderRedStreak(commits: List<RedStreakCommit>, failures: List<TestFailure>): String =
    "Commits tested red:\n" +
        commits.joinToString("\n") {
            "- ${it.sha.take(12)} ${redStreakText(it.subject)} — Run: ${it.runUrl}"
        } +
        "\n\nCurrent failing tests:\n" +
        failures.joinToString("\n") { "- ${redStreakText(it.test)}" }

class RedStreaks(private val github: GitHub, private val owner: String) {
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

    internal fun sync(commit: RedStreakCommit, failures: List<TestFailure>) {
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
        val state =
            Json.encodeToString(updatedCommits).replace("<", "\\u003c").replace(">", "\\u003e")
        val block =
            "$start\nState: OPEN\n\n${renderRedStreak(updatedCommits, failures)}\n\n$stateStart$state -->\n$end"
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
