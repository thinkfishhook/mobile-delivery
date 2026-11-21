package delivery

import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

private class ReservationRepository {
    val commands = RecordingCommands()
    private var tip: String? = null
    private val trees = mutableMapOf<String, String>()
    private val commits = mutableMapOf<String, Pair<String, String?>>()
    var loseRefResponse = false
    var refuseWrites = false

    init {
        commands.respond = { call ->
            val path = call.args[2].removePrefix("repos/owner/repo/")
            val body = call.input?.let { json.parseToJsonElement(it).jsonObject }
            fun ok(value: JsonObject) = CommandResult(0, value.toString(), "")
            fun failure(status: Int) = CommandResult(1, "HTTP/2 $status\r\n\r\n{}", "HTTP $status")
            when {
                body == null && path == "git/ref/heads/mobile-delivery-state" ->
                    if (tip == null) failure(404)
                    else
                        ok(
                            buildJsonObject {
                                putJsonObject("object") {
                                    put("type", "commit")
                                    put("sha", tip)
                                }
                            }
                        )
                body == null && path.startsWith("contents/") -> {
                    val content =
                        trees.getValue(commits.getValue(path.substringAfter("ref=")).first)
                    ok(
                        buildJsonObject {
                            put("type", "file")
                            put("encoding", "base64")
                            put(
                                "content",
                                Base64.getEncoder().encodeToString(content.toByteArray()),
                            )
                        }
                    )
                }
                path == "git/trees" -> {
                    val id = "tree-${trees.size}"
                    trees[id] =
                        body!!.getValue("tree").jsonArray.single().jsonObject.string("content")
                    ok(buildJsonObject { put("sha", id) })
                }
                path == "git/commits" -> {
                    val id = (commits.size + 1).toString().padStart(40, '0')
                    commits[id] =
                        body!!.string("tree") to
                            body
                                .getValue("parents")
                                .jsonArray
                                .singleOrNull()
                                ?.jsonPrimitive
                                ?.content
                    ok(buildJsonObject { put("sha", id) })
                }
                path.startsWith("git/refs") -> {
                    val target = body!!.string("sha")
                    when {
                        refuseWrites -> failure(403)
                        path == "git/refs" && tip != null -> failure(422)
                        path != "git/refs" &&
                            (body["force"] != JsonPrimitive(false) ||
                                commits.getValue(target).second != tip) -> failure(422)
                        else -> {
                            tip = target
                            if (loseRefResponse) failure(502)
                            else
                                ok(
                                    buildJsonObject {
                                        put("ref", "refs/heads/mobile-delivery-state")
                                    }
                                )
                        }
                    }
                }
                else -> error("Unexpected request $path")
            }
        }
    }

    fun store() =
        GitHubPreparationStore(
            GitHub(
                "owner/repo",
                commands,
                mapOf(
                    "GH_TOKEN" to "job",
                    "IOS_RECEIPT_TOKEN" to "receipts",
                    "ASC_PRIVATE_KEY" to "apple-secret",
                ),
            )
        )
}

class GitHubPreparationStoreTest {
    @Test
    fun `allocation floor includes historical receipts expired packages and every Apple state`() {
        val apple = FakeApple()
        apple.builds += TestFlightBuild("old", "26.09.01", "800.1", "VALID", true)
        apple.uploads += AppleUpload("26.09.01", "850.1", "FAILED")
        apple.versions +=
            StoreVersion("released", "26.09.01", "READY_FOR_SALE", "old", "950.1", null)
        val tags = FakeTags()
        tags.write(
            "deploy/testflight/26.09.01/875.1",
            "historical",
            Receipt("historical", MarketingVersion("26.09.01"), BuildNumber("875.1"), "id"),
        )
        val commands =
            RecordingCommands().apply {
                response("""{"artifacts":[{"name":"ios-build-900.1","expired":true}]}""")
            }
        val artifacts = GitHubBuildArtifacts(GitHub("owner/repo", commands, emptyMap()), "123")
        val occupied = occupiedPreparationNumbers(apple, tags, artifacts)
        assertEquals(setOf("800.1", "850.1", "875.1", "900.1", "950.1"), occupied)
        assertEquals(BuildNumber("950.1.1"), nextPreparationNumber(occupied))
    }

    @Test
    fun `git ref CAS rejects conflicting initialization and sibling updates without force`() {
        val remote = ReservationRepository()
        val store = remote.store()
        val empty = store.read()
        val p =
            Preparation(preparationRequest(), MarketingVersion("26.11.01"), BuildNumber("234.1.1"))
        val first = PreparationRecord(p)
        assertTrue(store.write(empty, listOf(first)))
        assertFalse(
            store.write(
                empty,
                listOf(first.copy(preparation = p.copy(number = BuildNumber("234.1.2")))),
            )
        )
        val snapshot = store.read()
        val second =
            PreparationRecord(
                p.copy(request = p.request.copy(rebuild = 2), number = BuildNumber("234.1.2"))
            )
        assertTrue(store.write(snapshot, listOf(first, second)))
        val third =
            PreparationRecord(
                p.copy(request = p.request.copy(rebuild = 3), number = BuildNumber("234.1.3"))
            )
        assertFalse(store.write(snapshot, listOf(first, third)))
        assertEquals(listOf(first, second), store.read().records)
        assertTrue(
            remote.commands.calls.all {
                it.env["GH_TOKEN"] == "job" &&
                    "ASC_PRIVATE_KEY" !in it.env &&
                    "IOS_RECEIPT_TOKEN" !in it.env
            }
        )
        assertFalse(remote.commands.calls.any { it.args.any { arg -> "refs/tags/deploy/" in arg } })
    }

    @Test
    fun `lost ref responses recover exact reservation while authorization errors never allocate`() {
        val remote = ReservationRepository().apply { loseRefResponse = true }
        val allocator = PreparationReservations(remote.store()) { setOf("234.1") }
        val p = allocator.reserve(preparationRequest(), MarketingVersion("26.11.01"))
        assertEquals(p, allocator.reserve(preparationRequest(), MarketingVersion("26.12.01")))
        assertEquals(1, remote.commands.calls.count { it.args[2] == "repos/owner/repo/git/refs" })
        remote.refuseWrites = true
        assertFailsWith<GitHubApiError> {
            allocator.reserve(preparationRequest(run = "124"), MarketingVersion("26.11.01"))
        }
        assertEquals(1, remote.store().read().records.size)
    }

    @Test
    fun `artifact inventory retains expired numbers and rejects ambiguous names`() {
        val commands = RecordingCommands()
        commands.respond = { call ->
            val rows = if ("name=" in call.args[2]) 2 else 1
            val response = buildJsonObject {
                putJsonArray("artifacts") {
                    repeat(rows) {
                        addJsonObject {
                            put("name", "ios-build-234.1")
                            put("expired", true)
                            putJsonObject("workflow_run") { put("id", "123") }
                        }
                    }
                }
            }
            CommandResult(0, response.toString(), "")
        }
        val artifacts = GitHubBuildArtifacts(GitHub("owner/repo", commands, emptyMap()), "123")
        assertEquals(setOf(BuildNumber("234.1")), artifacts.numbers())
        assertFails { artifacts.find("ios-build-234.1") }
    }
}
