package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class RedStreakDataTest {
    private val commits =
        listOf(
            RedStreakCommit(
                "a".repeat(40),
                "Add settings persistence",
                "https://github.com/owner/app/actions/runs/10",
            ),
            RedStreakCommit(
                "b".repeat(40),
                "Handle empty preferences",
                "https://github.com/owner/app/actions/runs/11",
            ),
            RedStreakCommit(
                "c".repeat(40),
                "Refresh settings view",
                "https://github.com/owner/app/actions/runs/12",
            ),
        )
    private val before =
        listOf(TestFailure("app.SettingsTest.saves", "expected saved", "SettingsTest.kt", 42))
    private val failures =
        listOf(
            TestFailure("app.SettingsTest.saves", "expected saved", "SettingsTest.kt", 42),
            TestFailure("app.LaunchTest.opens", "missing screen", "LaunchTest.kt", 19),
        )
    private val suspects =
        SuspectRange(
            "d".repeat(40),
            commits.first().sha,
            listOf(
                SuspectCommit("e".repeat(40), "Refactor store", true),
                SuspectCommit(commits.first().sha, commits.first().subject),
            ),
        )
    private val details =
        RedStreakDetails(
            FailingCommand("./gradlew :app:jvmTest", 1, "2 tests failed\nSee test results"),
            listOf(listOf("./gradlew", ":app:jvmTest")),
            "trunk",
            commits.last().runUrl + "/attempts/1",
            "ios-delivery-12-1",
            commits.last().runUrl + "/artifacts/99",
            listOf("delivery/${commits.last().sha}/verification/app/build/test-results/jvmTest"),
        )

    private fun body() =
        renderRedStreak(
            commits,
            failures,
            before,
            suspects,
            "owner/app",
            details,
            app = "com.example.app",
        )

    @Test
    fun `previous hidden state migrates to one redacted block without losing history or statuses`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            val owner = config.incidentMarker
            val oldCommits = json.encodeToString(listOf(commits.first()))
            val oldEvidence = buildJsonObject {
                put("failures", json.encodeToJsonElement(before))
                put("suspects", json.encodeToJsonElement(suspects))
            }
            val oldBody =
                "Human note\n<!-- $owner:red-streak -->\n<!-- $owner-status:start -->\nState: OPEN\n<!-- $owner-red-commits: $oldCommits -->\n<!-- $owner-red-evidence: $oldEvidence -->\n<!-- $owner-status:end -->\nHuman tail"
            h.issues += buildJsonObject {
                put("number", 1)
                put("state", "open")
                put("body", oldBody)
                putJsonArray("labels") { add(INCIDENT_LABEL) }
            }
            RedStreaks(GitHub("owner/repo", h.commands, emptyMap()), owner)
                .sync(commits[1], failures, details)
            val updated = h.issues.single().string("body")
            assertTrue(updated.startsWith("Human note\n"))
            assertTrue(updated.endsWith("\nHuman tail"))
            assertFalse("red-evidence:" in updated)
            assertFalse("red-commits:" in updated)
            val data = redStreakData(updated)!!
            assertEquals(commits.first().sha, data.firstRed)
            assertEquals(2, data.testedCount)
            assertEquals(listOf("still failing", "new"), data.failures.map { it.status })
        }

    @Test
    fun `schema reader tolerates added fields and refuses unsupported versions`() {
        val body = body()
        assertNotNull(
            redStreakData(
                body.replace(
                    "\"schemaVersion\": 1,",
                    "\"schemaVersion\": 1, \"futureField\": true,",
                )
            )
        )
        assertFailsWith<IllegalArgumentException> {
            redStreakData(body.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        }
    }

    @Test
    fun `private key JWT and exact multiline values are removed before JSON serialization`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nsynthetic-key\n-----END PRIVATE KEY-----"
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.syntheticSignature"
        val value = "private \"quoted\"\nvalue"
        val body =
            renderRedStreak(
                commits,
                listOf(TestFailure("Tests.fails", "$pem\n$jwt\n$value")),
                secrets = listOf(value),
            )
        for (secret in listOf(pem, jwt, value)) assertFalse(secret in body)
        assertEquals("***\n***\n***", redStreakData(body)!!.failures.single().failure.message)
    }

    @Test
    fun `posted state redacts credential and build environment values including quoted commands`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            val token = "synthetic-job-token"
            val appValue = "owner's private value"
            val github = GitHub("owner/repo", h.commands, mapOf("GH_TOKEN" to token))
            val streaks =
                RedStreaks(
                    github,
                    config.incidentMarker,
                    app = config.bundleId,
                    secretValues = listOf(appValue),
                )
            val detail =
                details.copy(
                    commands = listOf(listOf("bash", "check.sh", appValue)),
                    failingCommand =
                        FailingCommand("bash check.sh $appValue", 1, "$token\n$appValue"),
                )
            streaks.sync(
                commits.first().copy(subject = token),
                listOf(TestFailure("Test.fails", "$token $appValue")),
                detail,
            )
            val body = h.issues.single().string("body")
            for (secret in listOf(token, appValue)) assertFalse(secret in body)
            assertFalse("red-evidence:" in body)
            assertFalse("red-commits:" in body)
            val data = redStreakData(body)!!
            assertEquals(config.bundleId, data.app)
            assertEquals("*** ***", data.failures.single().failure.message)
            assertFalse("owner" in data.reproduce.single())
            assertEquals("***\n***", data.failingCommand!!.tail)
        }

    @Test
    fun `full posted body and its closure stay bounded with notes and valid persisted data`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            val streaks =
                RedStreaks(GitHub("owner/repo", h.commands, emptyMap()), config.incidentMarker)
            val largeDetails =
                details.copy(
                    failingCommand =
                        details.failingCommand!!.copy(tail = "log line\n".repeat(30000))
                )
            streaks.sync(commits.first(), failures, largeDetails)
            val original = h.issues.single()
            val note = "Human investigation\n".repeat(500)
            h.issues[0] =
                JsonObject(original + ("body" to JsonPrimitive(note + original.string("body"))))
            streaks.sync(commits[1], failures, largeDetails)
            val updated = h.issues.single().string("body")
            assertTrue(updated.toByteArray().size <= 65536)
            assertTrue(updated.startsWith(note))
            assertEquals(2, redStreakData(updated)!!.testedCount)
            val callCount = h.commands.calls.size
            streaks.sync(commits[1], failures, largeDetails)
            assertFalse(
                h.commands.calls.drop(callCount).any {
                    it.args.getOrNull(2)?.endsWith("issues/1") == true
                }
            )
            streaks.closeDelivered(commits.last().sha, "26.11.01/503.1")
            val closed = h.issues.single().string("body")
            assertTrue(closed.toByteArray().size <= 65536)
            assertTrue(closed.startsWith(note))
            assertNotNull(redStreakData(closed))
            assertContains(closed, "Commits tested red: 2")
        }

    @Test
    fun `realistic three commit streak matches approved body`() {
        val expected = resource("red-streak/three-commit.md").trimEnd()
        assertEquals(expected, body())
    }

    @Test
    fun `data block parses back into the public model`() {
        val data = redStreakData(body())!!
        assertEquals(1, data.schemaVersion)
        assertEquals("com.example.app", data.app)
        assertEquals("trunk", data.branch)
        assertEquals(commits.first().sha, data.firstRed)
        assertEquals(commits, data.tested)
        assertEquals(listOf("still failing", "new"), data.failures.map { it.status })
        assertEquals(suspects, data.suspectRange)
        assertContains(
            data.reproduce.single(),
            "--tests app.SettingsTest.saves --tests app.LaunchTest.opens",
        )
        assertEquals(details.artifactUrl, data.links.artifact)
    }

    @Test
    fun `secret shaped failure values are redacted in prose and structured data`() {
        val secret = "ghp_" + "A".repeat(36)
        val body =
            renderRedStreak(
                commits,
                listOf(TestFailure("Test.saves", "token=$secret")),
                details =
                    details.copy(
                        failingCommand = FailingCommand("echo $secret", 1, "Bearer $secret")
                    ),
            )
        assertFalse(secret in body)
        assertContains(body, "***")
        assertFalse(
            secret in json.encodeToString(RedStreakData.serializer(), redStreakData(body())!!)
        )
    }

    @Test
    fun `oversized body stays within limit with valid complete JSON and truncation notice`() {
        val hugeCommits =
            (1..1000).map {
                RedStreakCommit(
                    "%040x".format(it),
                    "Long subject ".repeat(300),
                    "https://github.com/owner/app/actions/runs/$it",
                )
            }
        val body =
            renderRedStreak(
                hugeCommits,
                failures,
                details =
                    details.copy(
                        failingCommand =
                            details.failingCommand!!.copy(tail = "failure output\n".repeat(20000))
                    ),
            )
        assertTrue(body.toByteArray().size <= 65536, "Body was ${body.toByteArray().size} bytes")
        assertContains(body, "Truncated")
        val data = redStreakData(body)!!
        assertEquals(1000, data.testedCount)
        assertEquals(hugeCommits.first().sha, data.firstRed)
        assertTrue("output tail" in data.truncation)
        assertTrue(data.truncation.any { "commit" in it })
    }
}
