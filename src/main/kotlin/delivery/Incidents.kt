package delivery

import kotlinx.serialization.json.*

const val INCIDENT_LABEL = "ios-delivery"
const val OWNER_LABEL = "needs-owner"

private enum class IncidentKind(val marker: String, val ownerOnly: Boolean) {
    DELIVERY("delivery", false),
    RECORD_REPAIR("record-repair", true),
}

private const val OWNER_PREFIX = "Owner action required:\n\n"

interface Incidents {
    // Opens or updates an incident for every finding that needs a person. Closes the other open
    // incidents, but only those whose key this sweep `checked`. Closed incidents are never read
    // or reopened: a problem that comes back gets a new issue.
    fun sync(findings: List<SweepFinding>, tip: String, checked: (IncidentSubject) -> Boolean)
}

// Incidents are labelled issues found by an owner marker; nothing about them is stored.
// Only the owned status block is edited, so people's notes survive.
class GitHubIncidents(
    private val github: GitHub,
    private val owner: String,
    private val branch: String,
) : Incidents {
    private val status = OwnedStatusBlock(owner)
    private val start = status.start
    private val end = status.end

    private fun marker(subject: IncidentSubject) = "<!-- $owner:${subject.key} -->"

    private fun owned(): Map<IncidentSubject, JsonObject> {
        val rows =
            github.pages("issues?labels=$INCIDENT_LABEL&state=open").filter {
                "pull_request" !in it
            }
        val keyed = rows.mapNotNull { issue ->
            Regex("<!-- ${Regex.escape(owner)}:(\\S+) -->")
                .find(issue["body"]?.jsonPrimitive?.contentOrNull.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.let { IncidentSubject.fromKey(it) to issue }
        }
        keyed
            .groupBy { it.first }
            .forEach { (key, issues) ->
                require(issues.size == 1) { "Duplicate owned incident for ${key.key}" }
            }
        return keyed.toMap()
    }

    override fun sync(
        findings: List<SweepFinding>,
        tip: String,
        checked: (IncidentSubject) -> Boolean,
    ) {
        val problems =
            findings
                .filter { it.operatorAction != null || it.repairError != null }
                .groupBy { it.operationKey }
        val open = owned()
        problems.forEach { (key, rows) ->
            val kind =
                if (rows.any { it.repairError != null }) IncidentKind.RECORD_REPAIR
                else IncidentKind.DELIVERY
            val ownerOnly = key.ownerOnly || kind.ownerOnly
            val evidence =
                rows.mapNotNull { it.operatorAction ?: it.repairError }.joinToString("\n")
            val block = "$start\nState: OPEN\nKind: ${kind.marker}\n\n$evidence\n$end"
            val issue = open[key]
            if (issue == null)
                create(
                    key,
                    (if (ownerOnly) OWNER_PREFIX else "") + marker(key) + "\n\n" + block,
                    ownerOnly,
                )
            else {
                val original = issue["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val updated =
                    (if (ownerOnly) OWNER_PREFIX else "") +
                        status.replace(original.removePrefix(OWNER_PREFIX), block)
                val labels =
                    issue["labels"]
                        ?.jsonArray
                        .orEmpty()
                        .map {
                            if (it is JsonObject) it.string("name") else it.jsonPrimitive.content
                        }
                        .toSet()
                val desiredLabels =
                    (labels - OWNER_LABEL) +
                        INCIDENT_LABEL +
                        (if (ownerOnly) setOf(OWNER_LABEL) else emptySet())
                if (updated != original || labels != desiredLabels)
                    github.api(
                        "issues/${issue.string("number")}",
                        buildJsonObject {
                            put("body", updated)
                            putJsonArray("labels") { desiredLabels.forEach { add(it) } }
                        },
                        method = "PATCH",
                    )
            }
        }
        val streak = open[IncidentSubject.RedStreak]
        val redCommits = streak?.let { redStreakCommits(it.string("body"), owner) }.orEmpty()
        (open - problems.keys).forEach { (key, issue) ->
            if (!checked(key)) return@forEach
            val covered =
                key is IncidentSubject.BetaCommit &&
                    redCommits.any {
                        it.sha == key.sha || (it.sha.length == 12 && key.sha.startsWith(it.sha))
                    }
            val superseded =
                !covered &&
                    when (key) {
                        is IncidentSubject.BetaCommit -> key.sha != tip
                        is IncidentSubject.AppStoreVersion,
                        is IncidentSubject.ReviewSubmission,
                        is IncidentSubject.Credential,
                        is IncidentSubject.Hold,
                        is IncidentSubject.Receipt,
                        IncidentSubject.RedStreak,
                        IncidentSubject.Audit,
                        IncidentSubject.AppStore,
                        IncidentSubject.Incidents,
                        is IncidentSubject.Unknown -> false
                    }
            val resolution =
                if (covered)
                    "Verification failure tracked in https://github.com/${github.repo}/issues/${streak!!.string("number")}."
                else if (superseded) "Superseded by $branch $tip; this commit was not delivered."
                else "Resolved; the last sweep found nothing to do."
            github.api(
                "issues/${issue.string("number")}",
                buildJsonObject {
                    put(
                        "body",
                        status.replace(
                            issue["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            "$start\nState: ${if (superseded) "SUPERSEDED" else "RESOLVED"}\n\n$resolution\n$end",
                        ),
                    )
                    put("state", "closed")
                    put("state_reason", if (superseded) "not_planned" else "completed")
                },
                method = "PATCH",
            )
        }
    }

    // A lost create response is read back from the open issues, never written twice.
    private fun create(key: IncidentSubject, body: String, ownerOnly: Boolean) {
        val write = attemptEffect {
            github.api(
                "issues",
                buildJsonObject {
                    put("title", key.title)
                    put("body", body)
                    putJsonArray("labels") {
                        add(INCIDENT_LABEL)
                        if (ownerOnly) add(OWNER_LABEL)
                    }
                },
            )
        }
        if (write.isSuccess || key in owned()) return
        write.getOrThrow()
    }
}
