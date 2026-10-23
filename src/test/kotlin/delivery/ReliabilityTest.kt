package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class ReliabilityTest {
    private fun archived(output: java.nio.file.Path): Build {
        val build =
            Build(
                "source-sha",
                MarketingVersion("26.09.01"),
                BuildNumber("234.1"),
                "https://github.com/owner/repo/actions/runs/123",
            )
        FakeBuilds().archive(output, output.resolve(build.sha), build)
        return build
    }

    @Test
    fun `retained packages are read back with checksums before upload`() = temp { output ->
        val build = archived(output)
        val bytes = localPackage(build, output.resolve(build.sha))
        val commands = RecordingCommands()
        var corrupt = false
        commands.respond = { call ->
            if ("download" in call.args) {
                val directory =
                    java.nio.file.Path.of(call.args[call.args.indexOf("--dir") + 1])
                        .resolve(build.sha)
                        .createDirectories()
                bytes.forEach { (name, value) ->
                    directory
                        .resolve(name)
                        .writeBytes(
                            if (corrupt && name == "app.ipa") "bad".toByteArray() else value
                        )
                }
                CommandResult(0, "", "")
            } else
                CommandResult(
                    0,
                    """{"artifacts":[{"id":7,"name":"ios-build-234.1","expired":false,"workflow_run":{"id":99}}]}""",
                    "",
                )
        }
        val artifacts = GitHubBuildArtifacts(GitHub("owner/repo", commands, emptyMap()), "123")
        val name = "ios-build-234.1"
        assertEquals(RetainedBuild(name, "123", false), artifacts.retainedByThisRun())
        // Another run's build is found by name and downloaded from the run that kept it.
        val found = assertNotNull(artifacts.find(name))
        assertEquals(RetainedBuild(name, "99", false), found)
        assertEquals(
            build.artifact,
            artifacts.download(found, output.resolve("downloaded")).manifest,
        )
        assertEquals("99", commands.calls.last { "download" in it.args }.args[3])
        corrupt = true
        assertFails { artifacts.download(found, output.resolve("corrupt")) }
        assertFails { validatePackage(build.artifact!!, bytes - "manifest.json") }
        assertFails { localPackage(build.copy(sha = "other"), output.resolve(build.sha)) }
    }

    @Test
    fun `GitHub transient reads respect bounded backoff and writes do not replay`() {
        val commands = RecordingCommands()
        val waits = mutableListOf<Long>()
        commands.respond = {
            CommandResult(1, "HTTP/2 503\r\nRetry-After: 30\r\n\r\n{}", "HTTP 503")
        }
        val github = GitHub("owner/repo", commands, emptyMap(), waits::add)
        assertFails { github.api("issues?state=open") }
        assertEquals(listOf(30_000L, 30_000L), waits)
        assertEquals(3, commands.calls.size)
        commands.calls.clear()
        waits.clear()
        assertFails { github.api("issues", buildJsonObject { put("title", "test") }) }
        assertEquals(1, commands.calls.size)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `TestFlight notes that differ only in line breaks and spacing still count as set`() =
        temp { output ->
            val notes = testerNotes(config, "source-sha")
            // App Store Connect may hand the text back with CRLF breaks, trailing spaces and
            // collapsed blank lines.
            val returned = notes.lines().filter { it.isNotBlank() }.joinToString(" \r\n") + "\r\n"
            val commands = RecordingCommands()
            var polls = 0
            commands.respond = { call ->
                val args = call.args
                val body =
                    when {
                        "test-notes" in args && "list" in args -> buildJsonObject {
                                putJsonArray("data") {
                                    addJsonObject {
                                        putJsonObject("attributes") {
                                            put("locale", "en-US")
                                            put("whatsNew", returned)
                                        }
                                    }
                                }
                            }
                                .toString()
                        "groups" in args -> """{"groups":[{"id":"${config.internalGroup}"}]}"""
                        "build-beta-detail" in args -> {
                            polls++
                            """{"data":{"attributes":{"internalBuildState":"IN_BETA_TESTING"}}}"""
                        }
                        else -> "{}"
                    }
                CommandResult(0, body, "")
            }
            var millis = 0L
            Asc(config, commands, emptyMap(), { millis += it }, { millis * 1_000_000 })
                .distribute("build", notes, output)
            assertEquals(1, polls)
            assertEquals(0L, millis)
        }

    @Test
    fun `TestFlight distribution polls until testers can install and stops on terminal states`() =
        temp { output ->
            val notes = testerNotes(config, "source-sha")
            listOf("ready", "timeout", "terminal").forEach { scenario ->
                val commands = RecordingCommands()
                var polls = 0
                var millis = 0L
                commands.respond = { call ->
                    val args = call.args
                    val body =
                        when {
                            "test-notes" in args && "list" in args -> buildJsonObject {
                                    putJsonArray("data") {
                                        addJsonObject {
                                            putJsonObject("attributes") {
                                                put("locale", "en-US")
                                                put("whatsNew", notes)
                                            }
                                        }
                                    }
                                }
                                    .toString()
                            "groups" in args -> """{"groups":[{"id":"${config.internalGroup}"}]}"""
                            "build-beta-detail" in args -> {
                                polls++
                                val state =
                                    if (scenario == "terminal") "EXPIRED"
                                    else if (scenario == "ready" && polls >= 3) "IN_BETA_TESTING"
                                    else "PROCESSING"
                                """{"data":{"attributes":{"internalBuildState":"$state"}}}"""
                            }
                            else -> "{}"
                        }
                    CommandResult(0, body, "")
                }
                val asc =
                    Asc(
                        config,
                        commands,
                        emptyMap(),
                        pause = { millis += it },
                        nanoTime = { millis * 1_000_000 },
                    )
                if (scenario == "ready") asc.distribute("build", notes, output)
                else assertFails { asc.distribute("build", notes, output) }
                assertEquals(
                    when (scenario) {
                        "ready" -> 3
                        "timeout" -> 41
                        else -> 1
                    },
                    polls,
                )
                assertTrue(millis <= 600_000)
            }
        }

    @Test
    fun `releasing a phased rollout to everyone confirms it, as asc requires`() {
        val commands = RecordingCommands()
        val asc = Asc(config, commands, emptyMap())
        listOf("PAUSED", "ACTIVE", "COMPLETE").forEach { asc.setRollout("phased", it) }
        assertEquals(
            listOf(false, false, true),
            commands.calls.map { "--confirm" in it.args },
        )
        assertTrue(
            commands.calls.all {
                it.args.take(4) == listOf("asc", "versions", "phased-release", "update")
            }
        )
    }

    @Test
    fun `staging copies the last release's metadata through release stage`() {
        fun staged(template: String): List<List<String>> {
            val commands = RecordingCommands()
            commands.respond = { CommandResult(0, """{"data":{"id":"version-id"}}""", "") }
            val id =
                Asc(config, commands, emptyMap())
                    .stage(MarketingVersion("26.09.02"), "build-id", template, "Notes")
            assertEquals("version-id", id)
            return commands.calls.map { it.args.drop(1).take(2) }
        }
        // asc release stage needs exactly one of --copy-metadata-from or --metadata-dir.
        val copied = staged("26.09.01")
        assertEquals(listOf("release", "stage"), copied.first())
        assertFalse(listOf("versions", "attach-build") in copied)
        assertTrue(listOf("validate", "--app") in copied)
    }

    @Test
    fun `testability checks use read commands only`() {
        val commands = RecordingCommands()
        commands.respond = { call ->
            assertTrue("list" in call.args || "view" in call.args)
            val body =
                when {
                    "groups" in call.args -> """{"groups":[]}"""
                    else -> """{"data":{"attributes":{"internalBuildState":"IN_BETA_TESTING"}}}"""
                }
            CommandResult(0, body, "")
        }
        assertFalse(Asc(config, commands, emptyMap()).testable("build"))
    }

    @Test
    fun `Apple uploads keep their state error code and date`() {
        val commands = RecordingCommands()
        val row =
            """{"id":"upload-id","attributes":{"cfBundleShortVersionString":"26.09.01","cfBundleVersion":"274.1","platform":"IOS","uploadedDate":"2026-09-23T10:51:58-07:00","state":{"state":"FAILED","errors":[{"code":"90382","description":"Upload limit reached"}]}}}"""
        commands.response("""{"data":[$row]}""")
        assertEquals(
            AppleUpload(
                "26.09.01",
                "274.1",
                "FAILED",
                "90382",
                "Upload limit reached",
                "2026-09-23T10:51:58-07:00",
            ),
            Asc(config, commands, emptyMap()).uploads().single(),
        )
    }

    @Test
    fun `review submissions are read for this app on iOS with their state`() {
        val commands = RecordingCommands()
        commands.response(
            """{"data":[{"type":"reviewSubmissions","id":"sub-1","attributes":{"platform":"IOS","state":"READY_FOR_REVIEW"}}]}"""
        )
        assertEquals(
            listOf(ReviewSubmission("sub-1", "READY_FOR_REVIEW")),
            Asc(config, commands, emptyMap()).reviewSubmissions(),
        )
        val args = commands.calls.single().args
        assertEquals(
            listOf("asc", "review", "submissions-list", "--app", config.appId),
            args.take(5),
        )
        assertContains(args, "--paginate")
    }

    @Test
    fun `queued pushes are matched by the composite regardless of caller filename`() {
        val selected = "a".repeat(40)
        val env =
            mapOf("DELIVERY_WORKFLOW_REPOSITORY" to "tools/delivery", "GITHUB_RUN_ID" to "123")
        fun run(status: String = "queued", sha: String = selected, id: Int = 456) =
            """{"id":$id,"head_sha":"$sha","status":"$status","path":".github/workflows/ship-it.yml"}"""
        fun queued(row: String, action: String = "tools/delivery/testflight@v1"): Boolean {
            val commands = RecordingCommands()
            commands.respond = { call ->
                val path = call.args[2]
                val body =
                    if (path.contains("contents/")) workflowContents(action)
                    else {
                        assertContains(path, "actions/runs?event=push&head_sha=$selected")
                        """{"workflow_runs":[$row]}"""
                    }
                CommandResult(0, body, "")
            }
            return GitHub("owner/repo", commands, env).queuedPush(selected)
        }
        assertTrue(queued(run()))
        assertFalse(queued(run(status = "completed")))
        assertFalse(queued(run(sha = "b".repeat(40))))
        assertFalse(queued(run(id = 123)))
        assertFalse(queued(run(), "tools/delivery/maintenance@v1"))
        assertFalse(queued(run(), "other/delivery/testflight@v1"))
        assertFalse(queued("""{"id":456,"head_sha":"$selected","status":"queued"}"""))
    }

    @Test
    fun `release notes fall back to the configured maintenance line`() = temp { root ->
        val commands = ProcessCommands()
        fun git(vararg args: String) =
            commands.run(listOf("git", *args), cwd = root, env = buildEnvironment()).trim()
        git("init", "-b", "trunk")
        git("config", "user.email", "test@example.invalid")
        git("config", "user.name", "Test")
        git("commit", "--allow-empty", "-m", "base")
        val base = git("rev-parse", "HEAD")
        git("commit", "--allow-empty", "-m", "tip")
        assertEquals(
            config.releaseNotes.fallback,
            GitSource(root, config, commands, "trunk").notes(base, git("rev-parse", "HEAD")),
        )
    }

    @Test
    fun `tester notes carry the commit so a sweep can place a receipt`() {
        val notes = testerNotes(config, "a".repeat(40))
        assertTrue(notes.startsWith(config.testerNotes.trim()))
        assertEquals("a".repeat(40), commitInNotes(notes))
        assertNull(commitInNotes(config.testerNotes))
    }

    @Test
    fun `hold issues carry the commit or person that closed them regardless of age`() {
        val commands = RecordingCommands()
        commands.respond = { call ->
            val path = call.args[2].substringAfter("repos/owner/repo/")
            val body =
                when {
                    "state=all" in path ->
                        """[{"number":1,"state":"open","body":"### Build\n\n26.09.02/405.1"},
                            {"number":2,"state":"closed","closed_at":"2026-09-10T00:00:00Z","body":""},
                            {"number":3,"state":"closed","closed_at":"2026-09-12T00:00:00Z","body":""},
                            {"number":4,"state":"closed","closed_at":"2026-01-01T00:00:00Z","body":""},
                            {"number":5,"state":"closed","closed_at":"2026-09-13T00:00:00Z","body":""},
                            {"number":6,"state":"closed","closed_at":"2026-09-14T00:00:00Z","body":""}]"""
                    path.startsWith("issues/2/events") ->
                        """[{"event":"labeled"},{"event":"closed","commit_id":"fix-sha","actor":{"type":"User"}}]"""
                    path.startsWith("issues/3/events") ->
                        """[{"event":"closed","commit_id":null,"actor":{"type":"Bot"}}]"""
                    path.startsWith("issues/4/events") ->
                        """[{"event":"closed","commit_id":null,"actor":{"type":"User"}}]"""
                    // A close without its event, and one by a deleted (ghost) account.
                    path.startsWith("issues/5/events") -> """[{"event":"labeled"}]"""
                    path.startsWith("issues/6/events") ->
                        """[{"event":"closed","commit_id":null,"actor":null}]"""
                    else -> error("Unexpected API: $path")
                }
            CommandResult(0, body, "")
        }
        val issues = GitHubHoldIssues(GitHub("owner/repo", commands, emptyMap()))
        val listed = issues.all()
        assertFalse(commands.calls.any { "/events" in it.args[2] })
        val holds = listed.map { if (it.open) it else issues.closedDetails(it) }
        assertEquals(
            listOf(
                HoldIssue(1, true, "### Build\n\n26.09.02/405.1"),
                HoldIssue(2, false, "", "fix-sha", closedByPerson = true),
                HoldIssue(3, false, "", null, closedByPerson = false),
                HoldIssue(4, false, "", null, closedByPerson = true),
                HoldIssue(5, false, "", null, closedByPerson = false),
                HoldIssue(6, false, "", null, closedByPerson = false),
            ),
            holds,
        )
    }

    @Test
    fun `closed hold events are read only for ancestors of candidates and failures stay closed`() =
        temp { root ->
            val commands = RecordingCommands()
            var failRead = false
            commands.respond = { call ->
                val path = call.args[2]
                when {
                    "state=all" in path ->
                        CommandResult(
                            0,
                            """[
                    {"number":1,"state":"closed","body":"### Build\n\n26.09.01/234.1"},
                    {"number":2,"state":"closed","body":"### Build\n\n26.09.01/235.1"},
                    {"number":3,"state":"closed","body":"### Build\n\n26.09.01/999.1"},
                    {"number":4,"state":"closed","body":"No build named"}
                ]""",
                            "",
                        )
                    "/issues/1/events?" in path ->
                        if (failRead) CommandResult(1, "", "HTTP 403")
                        else CommandResult(0, """[{"event":"closed","actor":{"type":"Bot"}}]""", "")
                    else -> error("Read close events for an irrelevant hold: $path")
                }
            }
            val tags = FakeTags()
            for ((number, sha) in
                listOf("234.1" to "held", "235.1" to "unrelated", "236.1" to "candidate")) {
                tags.tags["deploy/testflight/26.09.01/$number"] =
                    sha to
                        Receipt(
                            sha,
                            MarketingVersion("26.09.01"),
                            BuildNumber(number),
                            "build-$number",
                        )
            }
            val apple =
                FakeApple().apply {
                    builds += TestFlightBuild("candidate", "26.09.01", "236.1", "VALID", false)
                }
            val source = FakeSource().apply { shas = listOf("held", "candidate") }
            val release =
                AppStoreRelease(
                    config,
                    apple,
                    tags,
                    source,
                    ReleaseSymbols(FakeArtifacts().forRun("run"), FakeReleases(), root),
                    emptyMap(),
                    holdIssues = GitHubHoldIssues(GitHub("owner/repo", commands, emptyMap())),
                )
            assertEquals(listOf(1), release.facts().holds.map { it.issue })
            assertEquals(1, commands.calls.count { "/events" in it.args[2] })
            failRead = true
            assertFailsWith<GitHubFailure> { release.facts() }
            // Expired builds alone are not candidates, so even an unavailable close event
            // is irrelevant. A build already in review remains protected by the same hold.
            val eventReads = commands.calls.count { "/events" in it.args[2] }
            apple.builds.replaceAll { it.copy(expired = true) }
            assertTrue(release.facts().holds.isEmpty())
            assertEquals(eventReads, commands.calls.count { "/events" in it.args[2] })
            apple.versions +=
                StoreVersion("review", "26.09.01", "WAITING_FOR_REVIEW", "candidate", "236.1", null)
            assertFailsWith<GitHubFailure> { release.facts() }
            assertTrue(apple.calls.isEmpty())
        }

    @Test
    fun `symbol Releases use the receipt token and never create a tag`() {
        val commands = RecordingCommands()
        val tag = "deploy/testflight/26.09.02/405.1"
        commands.respond = { call ->
            when {
                call.args[1] == "api" && call.args[2].endsWith("releases/tags/$tag") ->
                    CommandResult(
                        0,
                        """{"assets":[{"name":"symbols.zip","digest":"sha256:abc"}]}""",
                        "",
                    )
                call.args[1] == "api" -> CommandResult(1, "", "gh: Not Found (HTTP 404)")
                else -> CommandResult(0, "", "")
            }
        }
        val releases =
            GitHubReleaseAssets(
                GitHub(
                    "owner/repo",
                    commands,
                    mapOf("GH_TOKEN" to "actions", "IOS_RECEIPT_TOKEN" to "receipts"),
                )
            )
        assertEquals(mapOf("symbols.zip" to "abc"), releases.assets(tag))
        assertNull(releases.assets("deploy/testflight/26.09.02/406.1"))
        releases.create(tag, "Symbols", "Notes", listOf(Path.of("/out/symbols.zip")))
        val create = commands.calls.last()
        assertEquals(
            listOf("gh", "release", "create", tag, "/out/symbols.zip"),
            create.args.take(5),
        )
        assertTrue("--verify-tag" in create.args)
        assertEquals("owner/repo", create.args[create.args.indexOf("--repo") + 1])
        assertTrue(commands.calls.all { it.env["GH_TOKEN"] == "receipts" })
    }
}
