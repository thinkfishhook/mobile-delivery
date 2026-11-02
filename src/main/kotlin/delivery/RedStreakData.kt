package delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class RedStreakLinks(
    val run: String? = null,
    val artifact: String? = null,
    val artifactName: String? = null,
    val reports: List<String> = emptyList(),
    val compare: String? = null,
)

@Serializable
internal data class RedStreakData(
    val schemaVersion: Int = 1,
    val app: String = "",
    val branch: String = "",
    val firstRed: String? = null,
    val tested: List<RedStreakCommit> = emptyList(),
    val failures: List<FailureChange> = emptyList(),
    val suspectRange: SuspectRange? = null,
    val reproduce: List<String> = emptyList(),
    val links: RedStreakLinks = RedStreakLinks(),
    val failingCommand: FailingCommand? = null,
    val artifactAvailable: Boolean = true,
    val testedCount: Int = tested.size,
    val truncation: List<String> = emptyList(),
    val updateKey: String = "",
)

private val streakJson = Json(json) { ignoreUnknownKeys = true }

private const val DATA_START = "\n\n## Data\n\n```json\n"

internal fun redStreakData(body: String): RedStreakData? {
    val index = body.lastIndexOf(DATA_START)
    if (index < 0) return null
    val data =
        streakJson.decodeFromString<RedStreakData>(
            body.substring(index + DATA_START.length).substringBefore("\n```")
        )
    require(data.schemaVersion == 1) {
        "Unsupported red-streak schemaVersion ${data.schemaVersion}; update the delivery tool before editing this issue"
    }
    return data
}

internal fun redStreakModel(
    commits: List<RedStreakCommit>,
    failures: List<TestFailure>,
    previous: List<TestFailure>,
    suspects: SuspectRange?,
    repo: String,
    details: RedStreakDetails?,
    app: String,
    secrets: Collection<String> = emptyList(),
): RedStreakData {
    val reproduce =
        details
            ?.let { detail ->
                if (detail.commands.isEmpty()) listOfNotNull(detail.failingCommand?.command)
                else
                    detail.commands.map {
                        TestReport.reproduce(it, failures)
                            .map { argument -> redactSecrets(argument, secrets) }
                            .joinToString(" ", transform = ::shellArgument)
                    }
            }
            .orEmpty()
    val data =
        RedStreakData(
            app = app,
            branch = details?.branch.orEmpty(),
            firstRed = commits.firstOrNull()?.sha,
            tested = commits,
            failures = diffFailures(previous, failures),
            suspectRange = suspects,
            reproduce = reproduce,
            links =
                RedStreakLinks(
                    details?.runUrl,
                    details?.artifactUrl,
                    details?.artifactName,
                    details?.reportPaths.orEmpty(),
                    suspects?.let {
                        "https://github.com/$repo/compare/${it.base}...${it.firstRed}"
                    },
                ),
            failingCommand = details?.failingCommand,
            artifactAvailable = details?.artifactAvailable ?: true,
        )
    return json.decodeFromJsonElement(redactJson(json.encodeToJsonElement(data), secrets))
}

internal fun renderRedStreak(
    commits: List<RedStreakCommit>,
    failures: List<TestFailure>,
    previous: List<TestFailure> = emptyList(),
    suspects: SuspectRange? = null,
    repo: String = "",
    details: RedStreakDetails? = null,
    app: String = "",
    secrets: Collection<String> = emptyList(),
): String =
    fitRedStreak(
        redStreakModel(commits, failures, previous, suspects, repo, details, app, secrets)
    ) {
        renderRedStreakData(it)
    }

internal fun renderRedStreakData(data: RedStreakData): String {
    val encoded =
        json
            .encodeToString(data)
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("`", "\\u0060")
    val note =
        if (data.truncation.isEmpty()) ""
        else
            "\n\nTruncated to fit GitHub's issue body limit: ${data.truncation.joinToString("; ")}. See the run and compare links for more evidence."
    return renderRedStreakProse(data) + note + DATA_START + encoded + "\n```"
}

// Transform the model and reserialize on each pass: JSON is never sliced. Include wrappers,
// persisted state and human notes in measure(), not just the visible diagnostic prose.
internal fun fitRedStreak(initial: RedStreakData, measure: (RedStreakData) -> String): String {
    var data = initial
    repeat(80) {
        val body = measure(data)
        if (body.toByteArray(Charsets.UTF_8).size <= 65000) return body
        data =
            when {
                !data.failingCommand?.tail.isNullOrEmpty() ->
                    data.copy(
                        failingCommand =
                            data.failingCommand.copy(
                                tail =
                                    data.failingCommand.tail.takeLast(
                                        data.failingCommand.tail.length / 2
                                    )
                            ),
                        truncation = (data.truncation + "output tail").distinct(),
                    )
                data.tested.any { it.subject.length > 80 } ||
                    data.suspectRange?.commits.orEmpty().any { it.subject.length > 80 } ->
                    data.copy(
                        tested = data.tested.map { it.copy(subject = it.subject.take(80)) },
                        suspectRange =
                            data.suspectRange?.let { range ->
                                range.copy(
                                    commits =
                                        range.commits.map { it.copy(subject = it.subject.take(80)) }
                                )
                            },
                        truncation = (data.truncation + "commit subjects").distinct(),
                    )
                data.tested.size > 2 || data.suspectRange?.commits.orEmpty().size > 2 ->
                    data.copy(
                        tested = retainEnds(data.tested),
                        suspectRange =
                            data.suspectRange?.let { it.copy(commits = retainEnds(it.commits)) },
                        truncation =
                            (data.truncation + "commit lists (testedCount retains the total)")
                                .distinct(),
                    )
                data.failures.any { it.failure.message.length > 128 } ->
                    data.copy(
                        failures =
                            data.failures.map {
                                it.copy(
                                    failure =
                                        it.failure.copy(message = it.failure.message.take(128))
                                )
                            },
                        truncation = (data.truncation + "failure messages").distinct(),
                    )
                data.failures.isNotEmpty() ||
                    data.reproduce.isNotEmpty() ||
                    data.links.reports.isNotEmpty() ->
                    data.copy(
                        failures = data.failures.take(data.failures.size / 2),
                        reproduce = data.reproduce.take(data.reproduce.size / 2),
                        links =
                            data.links.copy(
                                reports = data.links.reports.take(data.links.reports.size / 2)
                            ),
                        truncation =
                            (data.truncation + "failure and reproduction lists").distinct(),
                    )
                else -> {
                    // Bound pathological scalar fields too (for example one enormous command).
                    val bounded = boundStrings(json.encodeToJsonElement(data))
                    val next =
                        json
                            .decodeFromJsonElement<RedStreakData>(bounded)
                            .copy(
                                truncation = (data.truncation + "oversized text fields").distinct()
                            )
                    require(next != data) {
                        "Human notes leave no space for the red-streak data within GitHub's body limit"
                    }
                    next
                }
            }
    }
    error("Red-streak size guard could not converge")
}

private fun <T> retainEnds(items: List<T>): List<T> =
    if (items.size <= 2) items else items.take(1) + items.takeLast(maxOf(1, items.size / 2))

private fun boundStrings(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.mapValues { boundStrings(it.value) })
        is JsonArray -> JsonArray(element.map(::boundStrings))
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(element.content.take(256)) else element
    }
