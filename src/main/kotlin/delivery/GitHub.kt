package delivery

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.*

// JSON stays inside the transport adapter. State and receipts leave it as typed objects.
class GitHub(
    internal val repo: String,
    internal val commands: Commands,
    internal val env: Map<String, String>,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    internal val actionRoleCache = mutableMapOf<String, Set<String>>()

    fun currentBranchTip(branch: String): String {
        val encoded = URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20")
        val target = api("git/ref/heads/$encoded")?.jsonObject?.get("object")?.jsonObject
        val sha = target?.get("sha")?.jsonPrimitive?.content.orEmpty()
        require(
            target?.get("type")?.jsonPrimitive?.content == "commit" &&
                Regex("[a-f0-9]{40}").matches(sha)
        ) {
            "Expected a full commit SHA for branch $branch from GitHub; received $target"
        }
        return sha
    }

    internal fun retryRead(
        error: CommandError,
        attempt: Int,
        budget: LongArray = longArrayOf(60_000),
    ): Boolean {
        val failure = error as? GitHubFailure ?: return false
        if (!failure.transient || attempt >= 3) return false
        val delay = maxOf(1000L shl attempt, failure.retryAfterMs ?: 0)
        // Four attempts share a maximum 60-second waiting budget.
        if (delay > budget[0]) return false
        budget[0] -= delay
        sleep(delay)
        return true
    }

    internal fun api(
        path: String,
        payload: JsonObject? = null,
        missing: Boolean = false,
        receiptToken: Boolean = false,
        method: String? = null,
    ): JsonElement? {
        val args = mutableListOf("gh", "api", "repos/$repo/$path")
        val read = payload == null && (method == null || method == "GET")
        args += "--include"
        if (payload != null)
            args +=
                listOf(
                    "--method",
                    method ?: if (path.startsWith("contents/")) "PUT" else "POST",
                    "--input",
                    "-",
                )
        val token = token(receiptToken)
        val environment = environment(token)
        var attempt = 0
        val budget = longArrayOf(60_000)
        while (true) {
            val result = commands.execute(args, env = environment, input = payload?.toString())
            val normalized = result.stdout.replace("\r\n", "\n")
            val headers =
                if (normalized.startsWith("HTTP/")) normalized.substringBefore("\n\n") else ""
            val body = if (headers.isEmpty()) normalized else normalized.substringAfter("\n\n", "")
            if (result.exit == 0)
                return body.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) }
            val status =
                Regex("HTTP(?:/[^ ]+)? (\\d{3})")
                    .find(headers + " " + result.stderr)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            if (missing && status == 404) return null
            val errorBody = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            var message = errorBody?.get("message")?.jsonPrimitive?.content.orEmpty()
            listOfNotNull(token, env["GH_TOKEN"], env["GITHUB_TOKEN"])
                .filter { it.isNotEmpty() }
                .forEach { message = message.replace(it, "***") }
            val retryAfter =
                Regex("(?im)^retry-after: *([0-9]+)")
                    .find(headers)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
                    ?.times(1000)
            val transient =
                status in setOf(429, 500, 502, 503, 504) ||
                    (status == 403 &&
                        (retryAfter != null || message.contains("rate limit", true))) ||
                    (status == null &&
                        Regex(
                                "(?i)timeout|timed out|connection reset|unexpected EOF|TLS handshake|connection refused|no such host"
                            )
                            .containsMatchIn(result.stderr))
            val error =
                GitHubFailure(
                    "GitHub API failed: ${path.substringBefore('?')} (${status?.let { "HTTP $it" } ?: "exit ${result.exit}"}${if (message.isEmpty()) "" else ": " + message.replace(Regex("\\s+"), " ").take(1000)})",
                    transient,
                    retryAfter,
                    labelAlreadyExists =
                        status == 422 &&
                            (errorBody?.get("errors") as? JsonArray).orEmpty().any {
                                val detail = it as? JsonObject
                                detail?.get("resource")?.jsonPrimitive?.contentOrNull == "Label" &&
                                    detail["field"]?.jsonPrimitive?.contentOrNull == "name" &&
                                    detail["code"]?.jsonPrimitive?.contentOrNull == "already_exists"
                            },
                )
            if (!read || !retryRead(error, attempt++, budget)) throw error
        }
    }

    private fun token(receiptToken: Boolean) =
        if (receiptToken) env["IOS_RECEIPT_TOKEN"]?.takeIf { it.isNotBlank() } else null

    private fun environment(token: String?) =
        toolEnvironment(env, "GH_TOKEN", "GITHUB_TOKEN") +
            (token?.let { mapOf("GH_TOKEN" to it) } ?: emptyMap())

    // A gh subcommand on this repository, with the same token as api(). Writes are not retried.
    internal fun cli(args: List<String>, receiptToken: Boolean = false): String =
        commands.run(
            listOf("gh") + args + listOf("--repo", repo),
            env = environment(token(receiptToken)),
        )

    internal fun pages(path: String, field: String? = null): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var page = 1
        while (true) {
            val value =
                requireNotNull(
                    api("$path${if ('?' in path) '&' else '?'}per_page=100&page=$page")
                ) {
                    "GitHub $path page $page returned no JSON; retry the read before continuing"
                }
            val rows =
                if (field == null) value.jsonArray else value.jsonObject.getValue(field).jsonArray
            result += rows.map { it.jsonObject }
            if (rows.size < 100) return result
            page++
        }
    }
}

// A push run for this exact SHA that has not finished is healthy queued work. The run asking is
// not: its own sweep runs after its delivery failed.
internal fun GitHub.deliveryRuns(sha: String? = null): List<JsonObject> {
    return pages(
            "actions/runs?event=push${sha?.let { "&head_sha=$it" }.orEmpty()}",
            "workflow_runs",
        )
        .filter { run -> runUsesWorkflow(run, "testflight-delivery.yml") }
}

fun GitHub.queuedPush(sha: String) =
    deliveryRuns(sha).any {
        it.string("head_sha") == sha &&
            it.string("status") != "completed" &&
            it["id"]?.jsonPrimitive?.content.let { id -> id == null || id != env["GITHUB_RUN_ID"] }
    }

internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

class GitHubFailure(
    message: String,
    val transient: Boolean,
    val retryAfterMs: Long?,
    val labelAlreadyExists: Boolean = false,
) : CommandError(message)
