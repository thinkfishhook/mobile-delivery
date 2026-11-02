package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class RedStreaksTest {
    private val first = RedStreakCommit("a".repeat(40), "First red", "https://example/run/1")
    private val old = TestFailure("Old.fails", "expected true", "Tests.kt", 12)
    private val kept = TestFailure("Kept.fails", "expected saved", "Tests.kt", 20)
    private val added = TestFailure("New.fails", "expected open", "Other.kt", 30)

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
            h.streak().string("body").substringAfter("## Suspect commits").substringBefore("<!--")
        val streaks =
            RedStreaks(GitHub("owner/repo", h.commands, emptyMap()), config.incidentMarker)
        val commit =
            RedStreakCommit(DeliveryToolHarness.sha('b'), "Second", "https://example/run/11")
        val failures = listOf(TestFailure("New.fails", "expected true"))
        streaks.sync(commit, failures) { error("Must retain the original range") }
        val body = h.streak().string("body")
        assertEquals(before, body.substringAfter("## Suspect commits").substringBefore("<!--"))
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
        assertTrue(
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
