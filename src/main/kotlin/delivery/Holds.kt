package delivery

import kotlinx.serialization.json.*

const val HOLD_LABEL = "testflight-hold"

// A hold issue as GitHub reports it. `fix` is the commit that closed it, if any.
// `closedByPerson` needs a close event showing a person; a bot or a missing event is not one.
data class HoldIssue(
    val number: Int,
    val open: Boolean,
    val body: String,
    val fix: String? = null,
    val closedByPerson: Boolean = false,
)

fun interface HoldIssues {
    // Closed holds can block fresh descendants and rebuilds of commits that lack the fix.
    fun all(): List<HoldIssue>

    fun closedDetails(issue: HoldIssue): HoldIssue = issue
}

class GitHubHoldIssues(private val github: GitHub) : HoldIssues {
    override fun all(): List<HoldIssue> =
        github
            .pages("issues?labels=$HOLD_LABEL&state=all")
            .filter { "pull_request" !in it }
            .map { issue ->
                val number = issue.string("number").toInt()
                val body = issue["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                HoldIssue(number, issue.string("state") == "open", body)
            }

    override fun closedDetails(issue: HoldIssue): HoldIssue {
        val close =
            github.pages("issues/${issue.number}/events").lastOrNull {
                it.string("event") == "closed"
            }
        val actor = close?.get("actor")?.takeUnless { it == JsonNull }?.jsonObject
        return issue.copy(
            fix = close?.get("commit_id")?.jsonPrimitive?.contentOrNull,
            closedByPerson = actor != null && actor["type"]?.jsonPrimitive?.contentOrNull != "Bot",
        )
    }
}

// The "### Build" section of an issue-form hold, as typed.
fun heldBuildField(body: String): String? =
    Regex("(?m)^### Build[ \\t]*\\r?\\n\\s*(.+)").find(body)?.groupValues?.get(1)?.trim()

// The build a hold names, spelled as its receipt is: `26.09.01/404.1`. People may also type it
// the way TestFlight shows it, `26.09.01 (404.1)`.
fun heldBuild(body: String): String? =
    heldBuildField(body)
        ?.let {
            Regex("(\\d{2}\\.\\d{2}\\.\\d{2})(?:/(\\d+\\.\\d+)|\\s*\\(\\s*(\\d+\\.\\d+)\\s*\\))")
                .matchEntire(it)
        }
        ?.groupValues
        ?.let { (_, version, slashed, shown) -> "$version/${slashed.ifEmpty { shown }}" }

// `commit` is the held build's commit; null means the hold names no receipted build. `typed` is
// the Build field as the person wrote it.
data class Hold(
    val issue: Int,
    val open: Boolean,
    val build: String?,
    val commit: String?,
    val fix: String?,
    val lifted: Boolean,
    val typed: String? = build,
) {
    val malformed: Boolean
        get() = open && commit == null

    // Why a malformed hold names no build, quoting what was typed.
    val unresolved: String
        get() =
            when {
                typed == null -> "no Build field"
                build == null -> "\"$typed\" is not a build like 26.09.01/404.1"
                else -> "\"$typed\" has no TestFlight receipt"
            }

    // The held commit and everything after it, until a commit that contains the fix.
    fun blocks(sha: String, isAncestor: (String, String) -> Boolean) =
        !lifted &&
            commit != null &&
            isAncestor(commit, sha) &&
            !(fix != null && isAncestor(fix, sha))
}
