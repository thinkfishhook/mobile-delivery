package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class RedStreaksTest {
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
            assertFalse("OldTest.fails" in body)
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
        assertEquals(1, body.lineSequence().count { b.take(12) in it && !it.startsWith("<!--") })
        assertContains(body, "actions/runs/10")
        assertContains(body, "actions/runs/12")
        assertFalse("actions/runs/11" in body)
        assertContains(body, "RerunTest.fails")
        assertFalse("SecondTest.fails" in body)
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
