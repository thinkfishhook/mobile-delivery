package delivery

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class RedStreaksTest {
    private val first = RedStreakCommit("a".repeat(40), "First red", "https://example/run/1")
    private val old = TestFailure("Old.fails", "expected true", "Tests.kt", 12)
    private val kept = TestFailure("Kept.fails", "expected saved", "Tests.kt", 20)
    private val added = TestFailure("New.fails", "expected open", "Other.kt", 30)

    private fun DeliveryToolHarness.deliverCurrent() {
        build("26.09.01", "${count(tip)}.1")
        val original = commands.respond!!
        commands.respond = { call ->
            if (call.args.take(4) == listOf("asc", "builds", "test-notes", "list")) {
                CommandResult(
                    0,
                    """{"data":[{"attributes":{"locale":"en-US","whatsNew":${JsonPrimitive(testerNotes(config, tip))}}}]}""",
                    "",
                )
            } else original(call)
        }
        run(
                "uploadBeta",
                "workflow_dispatch",
                inputs =
                    mapOf(
                        "operation" to "retry",
                        "sha" to tip,
                        "expected_attempt" to "${count(tip)}.1",
                        "reason" to "Recover",
                    ),
            )
            .getOrThrow()
    }

    @Test
    fun `a newer delivery run or rerun is not covered by stale red evidence`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.red(DeliveryToolHarness.sha('a'), "Red", "Old.fails")
        var runId = "10"
        var attempt = 1
        val original = h.commands.respond!!
        h.commands.respond = { call ->
            if (call.args.getOrNull(2)?.contains("actions/runs?head_sha=") == true)
                CommandResult(
                    0,
                    """{"workflow_runs":[{"id":$runId,"head_sha":"${h.tip}","run_attempt":$attempt,"path":".github/workflows/ship-it.yml"}]}""",
                    "",
                )
            else original(call)
        }
        val github =
            GitHub(
                "owner/repo",
                h.commands,
                mapOf("DELIVERY_WORKFLOW_REPOSITORY" to "tools/delivery"),
            )
        github.actionRoleCache["ship-it.yml@${h.tip}"] = setOf("testflight")
        val streaks = RedStreaks(github, config.incidentMarker)
        assertNotNull(streaks.covering(h.tip))
        runId = "11"
        assertNull(streaks.covering(h.tip))
        runId = "10"
        attempt = 2
        assertNull(streaks.covering(h.tip))
    }

    @Test
    fun `infrastructure rerun on same red SHA retains the undelivered finding`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.red(DeliveryToolHarness.sha('a'), "Red", "Old.fails")
        h.verificationFailure =
            VerificationFailure(
                "application-checks",
                FailureKind.INFRASTRUCTURE,
                "Runner disconnected",
            )
        assertTrue(h.run("buildBeta", "push").isFailure)
        h.run("finishRun", "push", extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true")).getOrThrow()
        val summary =
            json.decodeFromString<RunSummary>(
                root.resolve("build/delivery/run-summary.json").readText()
            )
        assertTrue(summary.actionNeeded.any { "Deliver it:" in it })
        assertEquals("open", h.streak().string("state"))
    }

    @Test
    fun `red tip has one streak and no per commit deliver incident on repeated sweeps`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            h.red(DeliveryToolHarness.sha('a'), "Red", "Old.fails")
            h.run("sweep", "schedule").getOrThrow()
            val summary =
                json.decodeFromString<RunSummary>(
                    root.resolve("build/delivery/run-summary.json").readText()
                )
            assertContains(summary.deliveries, "red (see streak)")
            assertTrue(summary.actionNeeded.isEmpty())
            assertEquals(1, h.issues.size)
            assertContains(h.issues.single().string("body"), ":red-streak -->")
        }

    @Test
    fun `legacy verification incident closes completed with streak pointer and preserves notes`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            h.tip = DeliveryToolHarness.sha('a')
            val github = GitHub("owner/repo", h.commands, emptyMap())
            GitHubIncidents(github, config.incidentMarker, "trunk").sync(
                listOf(
                    SweepFinding(
                        IncidentSubject.BetaCommit(h.tip),
                        "not delivered",
                        "Deliver it: old advice",
                    )
                ),
                h.tip,
            ) {
                true
            }
            val legacy = h.issues.single()
            h.issues[0] =
                JsonObject(
                    legacy + ("body" to JsonPrimitive("Human note\n" + legacy.string("body")))
                )
            h.red(h.tip, "Red", "Old.fails")
            val closed = h.issues.single { it.string("number") == legacy.string("number") }
            assertEquals("closed", closed.string("state"))
            assertEquals("completed", closed.string("state_reason"))
            assertContains(
                closed.string("body"),
                "https://github.com/owner/repo/issues/${h.streak().string("number")}",
            )
            assertTrue(closed.string("body").startsWith("Human note\n"))
        }

    @Test
    fun `never attempted harness tip uses the current caller delivery hint`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.run("finishRun", "push").getOrThrow()
        val issue = h.issues.single()
        assertContains(
            issue.string("body"),
            "gh workflow run ship-it.yml -f operation=deliver -f sha=${h.tip}",
        )
    }

    @Test
    fun `resolution records elapsed time distinct red commits and preserves human notes on replay`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            h.red(DeliveryToolHarness.sha('a'), "First red", "Old.fails")
            h.red(DeliveryToolHarness.sha('b'), "Second red", "Old.fails", "11")
            h.red(DeliveryToolHarness.sha('b'), "Second red", "Old.fails", "12")
            val original = h.streak()
            h.issues[h.issues.indexOf(original)] =
                JsonObject(
                    original +
                        mapOf(
                            "created_at" to JsonPrimitive("2026-11-01T10:00:00Z"),
                            "body" to
                                JsonPrimitive(
                                    "Human note before\n" +
                                        original.string("body") +
                                        "\nHuman note after"
                                ),
                        )
                )
            val streaks =
                RedStreaks(
                    GitHub("owner/repo", h.commands, emptyMap()),
                    config.incidentMarker,
                    Clock.fixed(Instant.parse("2026-11-02T12:30:00Z"), ZoneOffset.UTC),
                )
            streaks.closeDelivered(DeliveryToolHarness.sha('c'), "26.11.01/503.1")
            val closed = h.streak()
            val body = closed.string("body")
            assertContains(body, "Duration: PT26H30M")
            assertContains(body, "Commits tested red: 2")
            assertContains(body, "State: RESOLVED")
            assertTrue(body.startsWith("Human note before\n"))
            assertTrue(body.endsWith("\nHuman note after"))
            val before = h.commands.calls.size
            streaks.closeDelivered(DeliveryToolHarness.sha('c'), "26.11.01/503.1")
            assertEquals(closed, h.streak())
            assertFalse(
                h.commands.calls.drop(before).any {
                    it.args.getOrNull(2)?.endsWith("issues/${closed.string("number")}") == true
                }
            )
        }

    @Test
    fun `failed close after receipt write is recovered by the next sweep`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.red(DeliveryToolHarness.sha('a'), "First red", "Old.fails")
        val number = h.streak().string("number")
        val original = h.commands.respond!!
        h.commands.respond = { call ->
            if (
                call.args.getOrNull(2)?.endsWith("issues/$number") == true &&
                    call.input?.contains("\"closed\"") == true
            )
                throw CommandError("Job cancelled before close")
            original(call)
        }
        h.tip = DeliveryToolHarness.sha('b')
        h.counts[h.tip] = 501
        assertFails { h.deliverCurrent() }
        assertEquals(h.tip, h.tags.getValue("deploy/testflight/26.09.01/501.1").first)
        assertEquals("open", h.streak().string("state"))
        h.commands.respond = original
        h.run("sweep", "schedule").getOrThrow()
        assertEquals("closed", h.streak().string("state"))
        assertEquals("completed", h.streak().string("state_reason"))
    }

    @Test
    fun `successful Apple distribution without a written receipt keeps streak open`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            h.red(DeliveryToolHarness.sha('a'), "First red", "Old.fails")
            val originalIssue = h.streak()
            val original = h.commands.respond!!
            h.commands.respond = { call ->
                if (call.args.getOrNull(2)?.endsWith("/git/refs") == true)
                    throw CommandError("Receipt write interrupted")
                original(call)
            }
            h.tip = DeliveryToolHarness.sha('b')
            h.counts[h.tip] = 501
            h.deliverCurrent()
            assertTrue(h.tags.isEmpty())
            assertEquals(originalIssue, h.streak())
        }

    @Test
    fun `red A then delivered B closes completed with fixing commit build and streak record`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            h.red(DeliveryToolHarness.sha('a'), "First red", "Old.fails")
            val issue = h.streak()
            h.tip = DeliveryToolHarness.sha('b')
            h.counts[h.tip] = 501
            h.deliverCurrent()
            val closed = h.issues.single { it.string("number") == issue.string("number") }
            assertEquals("closed", closed.string("state"))
            assertEquals("completed", closed.string("state_reason"))
            val body = closed.string("body")
            assertContains(body, "fixed by `${h.tip}`")
            assertContains(body, "delivered `26.09.01/501.1`")
            assertContains(body, "Duration:")
            assertContains(body, "Commits tested red: 1")
        }

    @Test
    fun `scheduled sweep closes stale streak when tip receipt exists`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.red(DeliveryToolHarness.sha('a'), "First red", "Old.fails")
        h.tip = DeliveryToolHarness.sha('b')
        h.counts[h.tip] = 501
        h.tags["deploy/testflight/26.09.01/501.1"] = h.tip to "receipt"
        h.run("sweep", "schedule").getOrThrow()
        val closed = h.streak()
        assertEquals("closed", closed.string("state"))
        assertEquals("completed", closed.string("state_reason"))
        assertContains(closed.string("body"), "fixed by `${h.tip}`, delivered `26.09.01/501.1`")
    }

    @Test
    fun `red after closure opens a fresh streak`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.red(DeliveryToolHarness.sha('a'), "First red", "Old.fails")
        val firstNumber = h.streak().string("number")
        h.tip = DeliveryToolHarness.sha('b')
        h.counts[h.tip] = 501
        h.tags["deploy/testflight/26.09.01/501.1"] = h.tip to "receipt"
        h.run("sweep", "schedule").getOrThrow()
        h.red(DeliveryToolHarness.sha('c'), "New red", "New.fails", "12")
        val streaks = h.issues.filter { ":red-streak -->" in it.string("body") }
        assertEquals(2, streaks.size)
        assertEquals(
            "closed",
            streaks.single { it.string("number") == firstNumber }.string("state"),
        )
        val fresh = streaks.single { it.string("state") == "open" }
        assertContains(fresh.string("body"), "**new** New.fails")
        assertFalse("Old.fails" in fresh.string("body"))
    }

    @Test
    fun `infrastructure failure after red keeps the streak open`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.red(DeliveryToolHarness.sha('a'), "First red", "Old.fails")
        val original = h.streak()
        h.tip = DeliveryToolHarness.sha('b')
        h.counts[h.tip] = 501
        h.verificationFailure =
            VerificationFailure(
                "application-checks",
                FailureKind.INFRASTRUCTURE,
                "Runner disconnected",
            )
        assertTrue(h.run("buildBeta", "push").isFailure)
        h.run("finishRun", "push", extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true")).getOrThrow()
        assertEquals(original, h.streak())
    }

    @Test
    fun `failure diff is stable when messages and lines change`() {
        val changed = old.copy(message = "different failure", line = 99)
        val before = listOf(old, kept)
        val after = listOf(changed, added)
        assertEquals(
            listOf(
                FailureChange(changed, "still failing"),
                FailureChange(added, "new"),
                FailureChange(kept, "now passing"),
            ),
            diffFailures(before, after),
        )
        assertEquals(listOf(old, kept), before)
        assertEquals(listOf(changed, added), after)
    }

    @Test
    fun `range stays anchored and identical replay preserves the previous diff`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val a = DeliveryToolHarness.sha('a')
        h.red(a, "First", "Old.fails")
        val before =
            h.streak()
                .string("body")
                .substringAfter("## Suspect commits")
                .substringBefore("## Failing command")
                .substringBefore("## Data")
                .substringBefore("<!--")
                .trim()
        val streaks =
            RedStreaks(GitHub("owner/repo", h.commands, emptyMap()), config.incidentMarker)
        val commit =
            RedStreakCommit(DeliveryToolHarness.sha('b'), "Second", "https://example/run/11")
        val failures = listOf(TestFailure("New.fails", "expected true"))
        streaks.sync(commit, failures) { error("Must retain the original range") }
        val body = h.streak().string("body")
        assertEquals(
            before,
            body
                .substringAfter("## Suspect commits")
                .substringBefore("## Failing command")
                .substringBefore("## Data")
                .substringBefore("<!--")
                .trim(),
        )
        assertContains(body, "**now passing** Old.fails")
        streaks.sync(commit, failures) { error("Replay must retain the original range") }
        assertEquals(body, h.streak().string("body"))
    }

    @Test
    fun `first renderer update marks every failure new with evidence`() {
        val body = renderRedStreak(listOf(first), listOf(old, kept))
        assertContains(body, "## Failing tests")
        assertContains(body, "**new** Old.fails — expected true — Tests.kt:12")
        assertContains(body, "**new** Kept.fails — expected saved — Tests.kt:20")
    }

    @Test
    fun `second renderer update shows fixed retained and new failures`() {
        val body = renderRedStreak(listOf(first), listOf(kept, added), listOf(old, kept))
        assertContains(body, "**now passing** Old.fails — expected true — Tests.kt:12")
        assertContains(body, "**still failing** Kept.fails — expected saved — Tests.kt:20")
        assertContains(body, "**new** New.fails — expected open — Other.kt:30")
    }

    @Test
    fun `renderer includes first parent suspects and two untested superseded commits`() {
        val range =
            SuspectRange(
                "d".repeat(40),
                first.sha,
                listOf(
                    SuspectCommit("b".repeat(40), "Skipped one", true),
                    SuspectCommit("c".repeat(40), "Skipped two", true),
                    SuspectCommit(first.sha, first.subject),
                ),
            )
        val body =
            renderRedStreak(listOf(first), listOf(old), suspects = range, repo = "owner/repo")
        assertContains(body, "## Suspect commits")
        assertContains(body, "${range.base}..${first.sha}")
        assertContains(body, "https://github.com/owner/repo/compare/${range.base}...${first.sha}")
        assertContains(body, "bbbbbbbbbbbb Skipped one — untested")
        assertContains(body, "cccccccccccc Skipped two — untested")
        assertContains(body, "superseded and never verified")
    }

    @Test
    fun `renderer uses baseline when no delivery exists`() {
        val body =
            renderRedStreak(
                listOf(first),
                listOf(old),
                suspects = SuspectRange("baseline", first.sha, emptyList()),
                repo = "owner/repo",
            )
        assertContains(body, "baseline..${first.sha}")
        assertContains(body, "compare/baseline...${first.sha}")
    }

    @Test
    fun `red push opens one actionable issue and identical replay is quiet`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.verificationFailure =
            VerificationFailure(
                "application-checks",
                FailureKind.VALIDATION,
                "Tests failed",
                listOf(
                    TestFailure("SettingsTest.saves", "expected saved"),
                    TestFailure("LaunchTest.opens", "expected open"),
                ),
            )
        h.run("startRun", "push").getOrThrow()
        assertTrue(h.run("buildBeta", "push").isFailure)
        val failedSummary = root.resolve("build/delivery/run-summary.json").readText()
        assertContains(failedSummary, "SettingsTest.saves")
        assertContains(failedSummary, "LaunchTest.opens")
        h.run("finishRun", "push", extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true")).getOrThrow()
        val issue =
            h.issues.single {
                "<!-- ${config.incidentMarker}:red-streak -->" in it.string("body")
            }
        assertEquals(IncidentSubject.RedStreak, IncidentSubject.fromKey("red-streak"))
        assertFalse(IncidentSubject.RedStreak.ownerOnly)
        assertEquals("open", issue.string("state"))
        assertFalse(
            h.issues.any {
                "<!-- ${config.incidentMarker}:internal/${h.tip} -->" in it.string("body")
            }
        )
        assertEquals(
            listOf(INCIDENT_LABEL),
            issue.getValue("labels").jsonArray.map { it.jsonPrimitive.content },
        )
        val body = issue.string("body")
        listOf(
                h.tip.take(12),
                h.commitSubject,
                "https://github.com/owner/repo/actions/runs/10",
                "SettingsTest.saves",
                "LaunchTest.opens",
            )
            .forEach { assertContains(body, it) }
        // Replaying a red finish does not create or update the streak (or let the sweep close
        // it).
        root.resolve("build/delivery/run-summary.json").writeText(failedSummary)
        val before = h.commands.calls.size
        h.run("finishRun", "push", extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true")).getOrThrow()
        assertEquals(listOf(issue), h.issues.filter { ":red-streak -->" in it.string("body") })
        assertTrue(
            h.commands.calls.drop(before).none {
                it.args.getOrNull(2)?.endsWith("issues/${issue.string("number")}") == true
            }
        )
    }

    private fun DeliveryToolHarness.red(
        sha: String,
        subject: String,
        test: String,
        run: String = "10",
    ) {
        tip = sha
        commitSubject = subject
        counts.putIfAbsent(sha, 500 + counts.size)
        verificationFailure =
            VerificationFailure(
                "application-checks",
                FailureKind.VALIDATION,
                "Tests failed",
                listOf(TestFailure(test, "expected true")),
            )
        val env = mapOf("GITHUB_RUN_ID" to run)
        run("startRun", "push", extraEnv = env).getOrThrow()
        assertTrue(run("buildBeta", "push", extraEnv = env).isFailure)
        run("finishRun", "push", extraEnv = env + ("IOS_EXECUTION_FAILED" to "true")).getOrThrow()
    }

    private fun DeliveryToolHarness.streak() = issues.single {
        ":red-streak -->" in it.string("body")
    }

    @Test
    fun `later red commit refreshes the one issue with ordered history and current tests`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            val a = DeliveryToolHarness.sha('a')
            val b = DeliveryToolHarness.sha('b')
            h.red(a, "First change", "OldTest.fails", "10")
            val number = h.streak().string("number")
            h.red(b, "Second change", "CurrentTest.fails", "11")
            val issue = h.streak()
            assertEquals(number, issue.string("number"))
            val body = issue.string("body")
            assertTrue(body.indexOf(a.take(12)) < body.indexOf(b.take(12)))
            for (value in
                listOf(
                    a.take(12),
                    b.take(12),
                    "First change",
                    "Second change",
                    "actions/runs/10",
                    "actions/runs/11",
                    "CurrentTest.fails",
                )) assertContains(body, value)
            assertContains(body, "**now passing** OldTest.fails")
        }

    @Test
    fun `human notes before and after owned block survive later red`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.red(DeliveryToolHarness.sha('a'), "First", "OldTest.fails")
        val issue = h.streak()
        val index = h.issues.indexOf(issue)
        h.issues[index] =
            JsonObject(
                issue +
                    ("body" to
                        JsonPrimitive(
                            "Human investigation before\n" +
                                issue.string("body") +
                                "\nHuman investigation after"
                        ))
            )
        h.red(DeliveryToolHarness.sha('b'), "Second", "CurrentTest.fails", "11")
        val body = h.streak().string("body")
        assertTrue(body.startsWith("Human investigation before\n"))
        assertTrue(body.endsWith("\nHuman investigation after"))
        assertContains(body, "CurrentTest.fails")
    }

    @Test
    fun `rerun replaces commit row and failures without duplicating history`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val a = DeliveryToolHarness.sha('a')
        val b = DeliveryToolHarness.sha('b')
        h.red(a, "First", "FirstTest.fails")
        h.red(b, "Second", "SecondTest.fails", "11")
        h.red(b, "Second", "RerunTest.fails", "12")
        val body = h.streak().string("body")
        assertEquals(1, body.lineSequence().count { b.take(12) in it && "Run:" in it })
        assertContains(body, "actions/runs/10")
        assertContains(body, "actions/runs/12")
        assertFalse("actions/runs/11" in body)
        assertContains(body, "RerunTest.fails")
        assertContains(body, "**now passing** SecondTest.fails")
    }

    @Test
    fun `superseded run leaves open streak unchanged`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val a = DeliveryToolHarness.sha('a')
        h.red(a, "First", "FirstTest.fails")
        val original = h.streak()
        val before = h.commands.calls.size
        h.tip = DeliveryToolHarness.sha('b')
        assertTrue(h.run("startRun", "push", sha = a).isFailure)
        h.run("finishRun", "push", sha = a, extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true"))
            .getOrThrow()
        assertEquals(original, h.streak())
        assertTrue(
            h.commands.calls.drop(before).none {
                it.args.getOrNull(2)?.endsWith("issues/" + original.string("number")) == true
            }
        )
    }

    @Test
    fun `issue from create-only version gains owned block without losing first commit or notes`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            val a = DeliveryToolHarness.sha('a')
            h.red(a, "First change", "OldTest.fails")
            val issue = h.streak()
            val legacy =
                "Human note before\n<!-- ${config.incidentMarker}:red-streak -->\n\nRed commit: ${a.take(12)} First change\n\nRun: https://github.com/owner/repo/actions/runs/10\n\nFailing tests:\n- OldTest.fails\n\nHuman note after"
            h.issues[h.issues.indexOf(issue)] =
                JsonObject(issue + ("body" to JsonPrimitive(legacy)))
            h.red(DeliveryToolHarness.sha('b'), "Second change", "CurrentTest.fails", "11")
            val body = h.streak().string("body")
            assertContains(body, a.take(12))
            assertContains(body, "First change")
            assertContains(body, "CurrentTest.fails")
            assertFalse("OldTest.fails" in body)
            assertTrue(body.startsWith("Human note before\n"))
            assertTrue(body.endsWith("\n\nHuman note after"))
        }

    @Test
    fun `infrastructure verification opens no red streak`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.verificationFailure =
            VerificationFailure(
                "application-checks",
                FailureKind.INFRASTRUCTURE,
                "Runner disconnected",
            )
        assertTrue(h.run("buildBeta", "push").isFailure)
        h.run("finishRun", "push", extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true")).getOrThrow()
        assertTrue(h.issues.none { ":red-streak -->" in it.string("body") })
    }

    @Test
    fun `superseded push opens no red streak`() = temp { root ->
        val h = DeliveryToolHarness(root)
        assertTrue(h.run("startRun", "push", sha = DeliveryToolHarness.sha('a')).isFailure)
        assertTrue(h.issues.none { ":red-streak -->" in it.string("body") })
    }
}
