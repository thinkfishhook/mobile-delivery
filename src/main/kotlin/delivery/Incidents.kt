package delivery

import kotlinx.serialization.json.*

const val INCIDENT_LABEL = "ios-delivery"

fun incidentTitle(operationKey: String) =
    when {
        operationKey.startsWith("internal/") ->
            "Beta delivery needs attention: ${operationKey.removePrefix("internal/").take(12)}"
        operationKey.startsWith("appstore/") ->
            "App Store release needs attention: ${operationKey.removePrefix("appstore/")}"
        else -> "iOS delivery needs attention: $operationKey"
    }

interface Incidents {
    // Opens or updates an incident for every finding that needs a person. Closes the other open
    // incidents, but only those whose key this sweep `checked`. Closed incidents are never read
    // or reopened: a problem that comes back gets a new issue.
    fun sync(findings: List<SweepFinding>, tip: String, checked: (String) -> Boolean)
}

// Incidents are labelled issues found by an owner marker; nothing about them is stored.
// Only the owned status block is edited, so people's notes survive.
class GitHubIncidents(
    private val github: GitHub,
    private val owner: String,
    private val branch: String,
) : Incidents {
    private val start = "<!-- $owner-status:start -->"
    private val end = "<!-- $owner-status:end -->"

    private fun marker(key: String) = "<!-- $owner:$key -->"

    private fun owned(): Map<String, JsonObject> {
        val rows =
            github.pages("issues?labels=$INCIDENT_LABEL&state=open").filter {
                "pull_request" !in it
            }
        val keyed = rows.mapNotNull { issue ->
            Regex("<!-- ${Regex.escape(owner)}:(\\S+) -->")
                .find(issue["body"]?.jsonPrimitive?.contentOrNull.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.let { it to issue }
        }
        keyed
            .groupBy { it.first }
            .forEach { (key, issues) ->
                require(issues.size == 1) { "Duplicate owned incident for $key" }
            }
        return keyed.toMap()
    }

    private fun body(original: String, block: String): String {
        if (start !in original && end !in original) return original + "\n" + block
        require(
            original.split(start).size == 2 &&
                original.split(end).size == 2 &&
                original.indexOf(start) < original.indexOf(end)
        ) {
            "Owned incident block is malformed; preserve human content"
        }
        return original.substringBefore(start) + block + original.substringAfter(end)
    }

    override fun sync(findings: List<SweepFinding>, tip: String, checked: (String) -> Boolean) {
        val problems =
            findings
                .filter { it.operatorAction != null || it.repairError != null }
                .groupBy { it.operationKey }
        val open = owned()
        problems.forEach { (key, rows) ->
            val kind = if (rows.any { it.repairError != null }) "record-repair" else "delivery"
            val evidence =
                rows.mapNotNull { it.operatorAction ?: it.repairError }.joinToString("\n")
            val block = "$start\nState: OPEN\nKind: $kind\n\n$evidence\n$end"
            val issue = open[key]
            if (issue == null) create(key, marker(key) + "\n\n" + block)
            else {
                val original = issue["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val updated = body(original, block)
                if (updated != original)
                    github.api(
                        "issues/${issue.string("number")}",
                        buildJsonObject { put("body", updated) },
                        method = "PATCH",
                    )
            }
        }
        (open - problems.keys).forEach { (key, issue) ->
            if (!checked(key)) return@forEach
            val superseded = key.startsWith("internal/") && key != "internal/$tip"
            val resolution =
                if (superseded) "Superseded by $branch $tip; this commit was not delivered."
                else "Resolved; the last sweep found nothing to do."
            github.api(
                "issues/${issue.string("number")}",
                buildJsonObject {
                    put(
                        "body",
                        body(
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
    private fun create(key: String, body: String) {
        val write = attemptEffect {
            github.api(
                "issues",
                buildJsonObject {
                    put("title", incidentTitle(key))
                    put("body", body)
                    putJsonArray("labels") { add(INCIDENT_LABEL) }
                },
            )
        }
        if (write.isSuccess || key in owned()) return
        write.getOrThrow()
    }
}
