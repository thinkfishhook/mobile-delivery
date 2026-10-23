package delivery

import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class IncidentsTest {
    // Issues in memory, served through the real GitHub adapter.
    private class Issues {
        val issues = mutableListOf<JsonObject>()
        var loseCreate = false
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    val path = call.args[2].substringAfter("repos/owner/repo/")
                    when {
                        path.startsWith("issues?") -> {
                            val state = Regex("state=(\\w+)").find(path)!!.groupValues[1]
                            val rows = issues.filter {
                                it.string("state") == state &&
                                    INCIDENT_LABEL in it["labels"].toString()
                            }
                            CommandResult(0, JsonArray(rows).toString(), "")
                        }
                        path == "issues" -> {
                            val payload = json.parseToJsonElement(call.input!!).jsonObject
                            issues +=
                                JsonObject(
                                    payload +
                                        mapOf(
                                            "number" to JsonPrimitive(issues.size + 1),
                                            "state" to JsonPrimitive("open"),
                                        )
                                )
                            if (loseCreate) CommandResult(1, "", "HTTP 502")
                            else CommandResult(0, issues.last().toString(), "")
                        }
                        path.startsWith("issues/") -> {
                            val index = issues.indexOfFirst {
                                it.string("number") == path.removePrefix("issues/")
                            }
                            issues[index] =
                                JsonObject(
                                    issues[index] + json.parseToJsonElement(call.input!!).jsonObject
                                )
                            CommandResult(0, issues[index].toString(), "")
                        }
                        else -> error("Unexpected API: $path")
                    }
                }
            }
        val incidents =
            GitHubIncidents(
                GitHub("owner/repo", commands, emptyMap(), {}),
                config.incidentMarker,
                "trunk",
            )

        fun writes() = commands.calls.count { it.input != null }
    }

    private fun problem(key: String, action: String) = SweepFinding(key, "needs action", action)

    @Test
    fun `incidents open once update in place and resolve with human notes kept`() {
        val remote = Issues()
        repeat(2) {
            remote.incidents.sync(listOf(problem("internal/sha", "Tests failed")), "sha") { true }
        }
        assertEquals(1, remote.writes())
        val issue = remote.issues.single()
        assertEquals("Beta delivery needs attention: sha", issue.string("title"))
        assertContains(issue["labels"].toString(), INCIDENT_LABEL)
        remote.issues[0] =
            JsonObject(
                issue +
                    ("body" to JsonPrimitive("Human note\n" + issue.string("body") + "\nKeep this"))
            )
        remote.incidents.sync(listOf(problem("internal/sha", "Receipt missing")), "sha") { true }
        assertContains(remote.issues[0].string("body"), "Receipt missing")
        remote.incidents.sync(listOf(SweepFinding("internal/sha", "delivered")), "sha") { true }
        assertEquals("closed", remote.issues[0].string("state"))
        assertEquals("completed", remote.issues[0].string("state_reason"))
        assertTrue(remote.issues[0].string("body").startsWith("Human note"))
        assertTrue(remote.issues[0].string("body").endsWith("Keep this"))
    }

    @Test
    fun `a problem that comes back gets a new incident and closed ones are never read`() {
        val remote = Issues()
        remote.incidents.sync(listOf(problem("internal/sha", "Tests failed")), "sha") { true }
        remote.incidents.sync(listOf(SweepFinding("internal/sha", "delivered")), "sha") { true }
        remote.incidents.sync(listOf(problem("internal/sha", "Receipt deleted")), "sha") { true }
        assertEquals(listOf("closed", "open"), remote.issues.map { it.string("state") })
        assertContains(remote.issues[0].string("body"), "Resolved")
        assertContains(remote.issues[1].string("body"), "Receipt deleted")
        assertTrue(remote.commands.calls.none { "state=closed" in it.args[2] })
    }

    @Test
    fun `an older commit's incident closes as superseded once trunk moves on`() {
        val remote = Issues()
        remote.incidents.sync(listOf(problem("internal/old", "Tests failed")), "old") { true }
        remote.incidents.sync(emptyList(), "new") { true }
        assertEquals("not_planned", remote.issues.single().string("state_reason"))
        assertContains(remote.issues.single().string("body"), "Superseded by trunk new")
    }

    @Test
    fun `a lost create response is read back instead of creating twice`() {
        val remote = Issues().apply { loseCreate = true }
        remote.incidents.sync(listOf(problem("internal/sha", "Failed")), "sha") { true }
        remote.loseCreate = false
        remote.incidents.sync(listOf(problem("internal/sha", "Failed")), "sha") { true }
        assertEquals(1, remote.issues.size)
    }

    @Test
    fun `unlabelled issues are never edited and duplicate owners fail closed`() {
        val remote = Issues()
        remote.issues += buildJsonObject {
            put("number", 9)
            put("state", "open")
            put("body", "<!-- example-app-delivery:internal/sha -->\nAn old incident")
        }
        remote.incidents.sync(emptyList(), "sha") { true }
        assertEquals(0, remote.writes())
        repeat(2) {
            remote.issues += buildJsonObject {
                put("number", 10 + it)
                put("state", "open")
                putJsonArray("labels") { add(buildJsonObject { put("name", INCIDENT_LABEL) }) }
                put("body", "<!-- example-app-delivery:internal/dup -->")
            }
        }
        assertFails { remote.incidents.sync(emptyList(), "sha") { true } }
        assertEquals(0, remote.writes())
    }

    @Test
    fun `App Store incidents stay open when the sweep could not read App Store state`() =
        temp { output ->
            val remote = Issues()
            val world = World(output)
            remote.incidents.sync(listOf(problem("appstore/26.09.02", "Rejected")), "sha") { true }
            val unreadable =
                AppStoreRelease(
                    config,
                    world.apple,
                    world.tags,
                    world.source,
                    world.symbols(),
                    world.env("s"),
                    clock,
                    holdIssues = HoldIssues { throw CommandError("GitHub timed out") },
                )
            Sweep(
                    config,
                    world.beta(),
                    unreadable,
                    world.apple,
                    world.tags,
                    world.source,
                    remote.incidents,
                    { false },
                )
                .run("sha")
            assertEquals("open", remote.state("appstore/26.09.02"))
        }

    private fun Issues.state(key: String) =
        issues.single { "example-app-delivery:$key " in it.string("body") }.string("state")

    @Test
    fun `a plain sweep never closes an incident only an audit can check`() = temp { output ->
        val remote = Issues()
        val world = World(output)
        world.source.shas = listOf("sha", "second")
        world.deliver("sha")
        val tag = "deploy/testflight/26.09.01/234.1"
        world.tags.tags[tag] = "second" to world.tags.tags.getValue(tag).second
        assertTrue(world.sweep(remote.incidents).run("sha", audit = true).broken)
        assertEquals("open", remote.state("audit"))
        world.sweep(remote.incidents).run("sha")
        assertEquals("open", remote.state("audit"))
    }

    @Test
    fun `a receipt repair incident closes when a later sweep writes the receipt, however late`() =
        temp { output ->
            val remote = Issues()
            val world = World(output)
            world.source.shas = listOf("sha")
            world.tags.failWrites = true
            world.deliver("sha")
            val key = "receipt/26.09.01/234.1"
            world.sweep(remote.incidents).run("sha")
            assertEquals("open", remote.state(key))
            // The receipt token stays broken for a month.
            world.apple.builds.replaceAll {
                it.copy(uploadedAt = now.minusDays(30).toOffsetDateTime().toString())
            }
            world.sweep(remote.incidents).run("sha")
            assertEquals("open", remote.state(key))
            world.tags.failWrites = false
            world.sweep(remote.incidents).run("sha")
            assertEquals("closed", remote.state(key))
            assertTrue("deploy/testflight/26.09.01/234.1" in world.tags.tags)
        }
}
