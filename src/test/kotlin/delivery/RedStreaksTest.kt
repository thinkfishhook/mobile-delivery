package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class RedStreaksTest {
    @Test
    fun `red push opens one actionable issue and subsequent red leaves it untouched`() =
        temp { root ->
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
            h.run("finishRun", "push", extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true"))
                .getOrThrow()
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
            h.run("finishRun", "push", extraEnv = mapOf("IOS_EXECUTION_FAILED" to "true"))
                .getOrThrow()
            assertEquals(listOf(issue), h.issues.filter { ":red-streak -->" in it.string("body") })
            assertTrue(
                h.commands.calls.drop(before).none {
                    it.args.getOrNull(2)?.endsWith("issues/${issue.string("number")}") == true
                }
            )
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
