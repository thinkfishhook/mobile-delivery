package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.json.*

// Runs the delivery tool's workflow entry points against fake git, GitHub and asc commands.
class DeliveryToolHarness(val root: Path) {
    var tip = sha('b')
    var remoteTip: String? = null
    var failBranchRead = false
    var failBranchFetch = false
    val counts = mutableMapOf<String, Int>()
    val issues = mutableListOf<JsonObject>()
    var failIssueWrites = false
    val labels = mutableSetOf<String>()
    val tags = mutableMapOf<String, Pair<String, String>>()
    val builds = mutableListOf<JsonObject>()
    val uploads = mutableListOf<JsonObject>()
    var storeVersions = """{"data":[]}"""
    val commands = RecordingCommands()
    private var pendingTag: Pair<String, Pair<String, String>>? = null

    val remoteCalls
        get() = commands.calls.count { it.args.first() in setOf("gh", "asc") }

    init {
        commands.respond = { call ->
            when (call.args.first()) {
                "git" -> git(call.args.drop(1))
                "gh" -> github(call)
                "asc" -> apple(call.args.drop(1))
                else -> error("Unexpected command: ${call.args}")
            }
        }
    }

    fun count(sha: String) = counts[sha] ?: 500

    private fun ok(body: String) = CommandResult(0, body, "")

    private fun git(args: List<String>): CommandResult =
        when {
            args.first() == "-c" -> git(args.drop(2))
            args.first() == "fetch" ->
                if (failBranchFetch) CommandResult(1, "", "fetch failed")
                else {
                    tip = remoteTip ?: tip
                    ok("")
                }
            args.first() == "rev-parse" ->
                ok(if (args[1] == "origin/${"trunk"}") tip else args[1].removeSuffix("^{commit}"))
            args.first() == "merge-base" -> ok("")
            args.take(2) == listOf("rev-list", "--count") -> ok(count(args[2]).toString())
            args.take(2) == listOf("rev-list", "--first-parent") ->
                ok(counts.entries.sortedBy { it.value }.joinToString("\n") { it.key })
            else -> error("Unexpected git: $args")
        }

    // Pipeline builds in the shape `asc builds list --include preReleaseVersion` returns.
    fun build(version: String, number: String, uploadedAt: String = "2026-08-01T00:00:00Z") {
        builds += buildJsonObject {
            put("type", "builds")
            put("id", "build-$number")
            putJsonObject("attributes") {
                put("version", number)
                put("processingState", "VALID")
                put("expired", false)
                put("uploadedDate", uploadedAt)
            }
            putJsonObject("relationships") {
                putJsonObject("preReleaseVersion") {
                    putJsonObject("data") { put("id", "pre-$version") }
                }
            }
        }
    }

    fun upload(version: String, number: String, state: String, code: String? = null) {
        uploads += buildJsonObject {
            put("id", "upload-$number")
            putJsonObject("attributes") {
                put("cfBundleShortVersionString", version)
                put("cfBundleVersion", number)
                put("uploadedDate", "2026-08-01T00:00:00Z")
                putJsonObject("state") {
                    put("state", state)
                    code?.let { putJsonArray("errors") { addJsonObject { put("code", it) } } }
                }
            }
        }
    }

    private fun apple(args: List<String>): CommandResult =
        when {
            args.take(2) == listOf("versions", "list") -> ok(storeVersions)
            args.take(2) == listOf("review", "submissions-list") -> ok("""{"data":[]}""")
            args.take(3) == listOf("versions", "phased-release", "update") -> ok("{}")
            args.take(3) == listOf("builds", "uploads", "list") ->
                ok(buildJsonObject { put("data", JsonArray(uploads)) }.toString())
            args.take(2) == listOf("builds", "list") ->
                ok(
                    buildJsonObject {
                        put("data", JsonArray(builds))
                        putJsonArray("included") {
                            builds
                                .map { it.toString().substringAfter("pre-").substringBefore('"') }
                                .distinct()
                                .forEach { version ->
                                    addJsonObject {
                                        put("type", "preReleaseVersions")
                                        put("id", "pre-$version")
                                        putJsonObject("attributes") { put("version", version) }
                                    }
                                }
                        }
                    }
                        .toString()
                )
            args.take(2) == listOf("builds", "build-beta-detail") ->
                ok("""{"data":{"attributes":{"internalBuildState":"IN_BETA_TESTING"}}}""")
            args.take(3) == listOf("builds", "groups", "list") ->
                ok("""{"groups":[{"id":"${config.internalGroup}"}]}""")
            args.take(3) == listOf("builds", "test-notes", "list") -> ok("""{"data":[]}""")
            else -> error("Unexpected asc: $args")
        }

    private fun github(call: RecordingCommands.Call): CommandResult {
        val path = call.args[2].substringAfter("repos/owner/repo/")
        return when {
            path.startsWith("labels/") ->
                if (path.removePrefix("labels/") in labels) ok("{}")
                else CommandResult(1, "", "HTTP 404")
            path == "labels" -> {
                labels +=
                    json.parseToJsonElement(requireNotNull(call.input)).jsonObject.string("name")
                ok("{}")
            }
            path == "git/ref/heads/trunk" ->
                if (failBranchRead) CommandResult(1, "", "HTTP 403")
                else ok("""{"object":{"type":"commit","sha":"${remoteTip ?: tip}"}}""")
            path.startsWith("actions/runs?") -> ok("""{"workflow_runs":[]}""")
            path.startsWith("actions/runs/10/artifacts?") -> ok("""{"artifacts":[]}""")
            path.startsWith("actions/artifacts?") -> ok("""{"artifacts":[]}""")
            path.startsWith("git/matching-refs/tags/") ->
                ok(
                    JsonArray(
                            tags.keys
                                .filter {
                                    it.startsWith(path.removePrefix("git/matching-refs/tags/"))
                                }
                                .map { buildJsonObject { put("ref", "refs/tags/$it") } }
                        )
                        .toString()
                )
            path.startsWith("git/ref/tags/") ->
                if (path.removePrefix("git/ref/tags/") in tags)
                    ok(
                        """{"object":{"type":"tag","sha":"tag-${path.removePrefix("git/ref/tags/")}"}}"""
                    )
                else CommandResult(1, "", "HTTP 404")
            path.startsWith("git/tags/tag-") -> {
                val (target, message) = tags.getValue(path.removePrefix("git/tags/tag-"))
                ok(
                    buildJsonObject {
                        putJsonObject("object") { put("sha", target) }
                        put("message", message)
                    }
                        .toString()
                )
            }
            path == "git/tags" -> {
                val payload = json.parseToJsonElement(requireNotNull(call.input)).jsonObject
                pendingTag =
                    payload.string("tag") to (payload.string("object") to payload.string("message"))
                ok("""{"sha":"tag-${payload.string("tag")}"}""")
            }
            path == "git/refs" -> {
                val (tag, target) = requireNotNull(pendingTag)
                tags[tag] = target
                ok("{}")
            }
            path.startsWith("issues?") -> {
                val state = Regex("state=(\\w+)").find(path)!!.groupValues[1]
                val label = Regex("labels=([\\w-]+)").find(path)?.groupValues?.get(1)
                ok(
                    JsonArray(
                            issues.filter {
                                it.string("state") == state &&
                                    (label == null || label in it["labels"].toString())
                            }
                        )
                        .toString()
                )
            }
            path == "issues" && failIssueWrites -> CommandResult(1, "", "HTTP 503")
            path == "issues" -> {
                val payload = json.parseToJsonElement(requireNotNull(call.input)).jsonObject
                issues +=
                    JsonObject(
                        payload +
                            mapOf(
                                "number" to JsonPrimitive(issues.size + 1),
                                "state" to JsonPrimitive("open"),
                            )
                    )
                ok(issues.last().toString())
            }
            path.startsWith("issues/") -> {
                val index = issues.indexOfFirst {
                    it.string("number") == path.removePrefix("issues/")
                }
                issues[index] =
                    JsonObject(
                        issues[index] +
                            json.parseToJsonElement(requireNotNull(call.input)).jsonObject
                    )
                ok(issues[index].toString())
            }
            else -> error("Unexpected API: $path")
        }
    }

    fun run(
        mode: String,
        event: String,
        sha: String = tip,
        inputs: Map<String, String> = emptyMap(),
        attempt: Int = 1,
        extraEnv: Map<String, String> = emptyMap(),
    ): Result<String> {
        val output = root.resolve("output-${commands.calls.size}").also { it.writeText("") }
        val summary = root.resolve("summary-${commands.calls.size}").also { it.writeText("") }
        val eventFile = root.resolve("event.json")
        eventFile.writeText(
            buildJsonObject {
                if (event == "schedule") put("schedule", "23 * * * *")
                if (inputs.isNotEmpty())
                    putJsonObject("inputs") { inputs.forEach { (key, value) -> put(key, value) } }
            }
                .toString()
        )
        val env =
            mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REF" to "refs/heads/${"trunk"}",
                "GITHUB_REPOSITORY" to "owner/repo",
                "GITHUB_WORKFLOW_REF" to
                    "owner/repo/.github/workflows/ship-it.yml@refs/heads/trunk",
                "DELIVERY_WORKFLOW_REPOSITORY" to "tools/delivery",
                "DELIVERY_INPUTS" to
                    JsonObject(
                            (inputs + ("branch" to "trunk")).mapValues { JsonPrimitive(it.value) }
                        )
                        .toString(),
                "GITHUB_SHA" to sha,
                "GITHUB_EVENT_NAME" to event,
                "GITHUB_EVENT_PATH" to eventFile.toString(),
                "GITHUB_RUN_ID" to "10",
                "GITHUB_RUN_ATTEMPT" to attempt.toString(),
                "GITHUB_OUTPUT" to output.toString(),
                "GITHUB_STEP_SUMMARY" to summary.toString(),
                "IOS_DELIVERY_ENABLED" to "true",
            ) + extraEnv
        val stdout = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(stdout, true))
        val result = runCatching { DeliveryTool(root, config, commands, env).run(mode) }
        System.setOut(original)
        val text =
            stdout.toString() +
                listOf(output, summary).filter { it.exists() }.joinToString("") { it.readText() }
        return result.map { text }.recoverCatching { throw ToolFailure(it, text) }
    }

    class ToolFailure(cause: Throwable, val output: String) :
        RuntimeException("${cause.message}\n$output", cause)

    companion object {
        fun sha(character: Char) = character.toString().repeat(40)

        // A history in which every build is delivered and receipted, long enough ago that sweeps
        // leave it alone.
        fun settled(h: DeliveryToolHarness, builds: Int): String {
            var last = ""
            repeat(builds) { index ->
                val sha = "%040x".format(index + 1)
                val number = "${index + 1}.1"
                h.counts[sha] = index + 1
                h.build("26.09.01", number)
                h.upload("26.09.01", number, "COMPLETE")
                h.tags["deploy/testflight/26.09.01/$number"] =
                    sha to
                        json.encodeToString(
                            Receipt.serializer(),
                            Receipt(
                                sha,
                                MarketingVersion("26.09.01"),
                                BuildNumber(number),
                                "build-$number",
                            ),
                        )
                last = sha
            }
            return last
        }
    }
}
