package delivery

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Resolve once, when the streak starts. Later red updates must not move its lower bound.
internal fun redStreakSuspects(
    firstRed: String,
    baseline: String,
    source: GitSource,
    tags: ReceiptTags,
    github: GitHub,
): SuspectRange {
    val delivered =
        requireReceiptNames("deploy/testflight/", tags.names("deploy/testflight/"))
            .sortedWith(
                compareByDescending<String> { BuildNumber(it.substringAfterLast('/')).count }
                    .thenByDescending { BuildNumber(it.substringAfterLast('/')).attempt }
            )
            .asSequence()
            .map(tags::commit)
            .firstOrNull { source.isAncestor(it, firstRed) }
    val base = delivered ?: baseline
    val commits =
        source
            .git("rev-list", "--first-parent", "--reverse", "$base..$firstRed")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { sha ->
                val runs = if (sha == firstRed) emptyList() else github.deliveryRuns(sha)
                // Cancellation alone is not proof: require cancellation at selection, before
                // verification.
                // A tested rerun prevents the untested label.
                val untested =
                    runs.isNotEmpty() &&
                        runs.all {
                            it["conclusion"]?.jsonPrimitive?.contentOrNull == "cancelled" &&
                                github
                                    .pages("actions/runs/${it.string("id")}/jobs", "jobs")
                                    .any(::supersededBeforeVerification)
                        }
                SuspectCommit(sha, source.git("log", "-1", "--format=%s", sha), untested)
            }
            .toList()
    return SuspectRange(base, firstRed, commits, delivered == null)
}

private fun supersededBeforeVerification(job: kotlinx.serialization.json.JsonObject): Boolean {
    val steps = job["steps"]?.jsonArray.orEmpty().map { it.jsonObject }
    val cancelledSelection = steps.any {
        it.string("name").endsWith("Select the delivery operation") &&
            it["conclusion"]?.jsonPrimitive?.contentOrNull == "cancelled"
    }
    val verification = steps.filter {
        it.string("name").endsWith("Verify the app") ||
            it.string("name").endsWith("Verify and archive the selected commit")
    }
    return cancelledSelection &&
        verification.isNotEmpty() &&
        verification.all {
            it["conclusion"]?.jsonPrimitive?.contentOrNull == "skipped"
        }
}
