package delivery

// Retain GitHubFailure compatibility for retry and label-conflict handling. Header names
// are lower case, with the last value retained when the response repeats a field.
class GitHubApiError(
    val path: String,
    val status: Int?,
    message: String,
    val headers: Map<String, String>,
    exitCode: Int = 1,
    transient: Boolean = false,
    retryAfterMs: Long? = null,
    labelAlreadyExists: Boolean = false,
) :
    GitHubFailure(
        "GitHub API failed: ${path.substringBefore('?')} (${status?.let { "HTTP $it" } ?: "exit $exitCode"}${if (message.isEmpty()) "" else ": " + message.replace(Regex("\\s+"), " ").take(1000)})",
        transient,
        retryAfterMs,
        labelAlreadyExists,
    )
