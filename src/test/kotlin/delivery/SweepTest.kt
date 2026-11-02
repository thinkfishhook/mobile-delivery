package delivery

import kotlin.test.*
import kotlinx.serialization.encodeToString
import org.junit.Test

class SweepTest {
    @Test
    fun `finding serialization and summary keep the exact operation key strings`() =
        temp { output ->
            val world = World(output)
            for (key in
                listOf(
                    "internal/sha",
                    "appstore/26.09.01",
                    "appstore/review-submission-id",
                    "hold/7",
                    "receipt/26.09.01/234.1",
                    "audit",
                    "appstore",
                    "incidents",
                    "legacy/unknown",
                )) {
                val finding = SweepFinding(key, "waiting")
                val encoded =
                    """
                {
                    "operationKey": "$key",
                    "outcome": "waiting"
                }
            """
                        .trimIndent()
                assertEquals(encoded, json.encodeToString(finding))
                assertEquals(finding, json.decodeFromString<SweepFinding>(encoded))
                assertEquals(
                    "$key: waiting",
                    world.sweep().summary(SweepReport(listOf(finding))).deliveries,
                )
            }
        }

    @Test
    fun `checked rules cover every incident kind and receipt settlement boundary`() {
        for (audit in listOf(false, true)) for (readable in listOf(false, true)) {
            for (receiptState in
                listOf("missing", "present", "expired", "invalid", "absent")) temp { output ->
                val world = World(output)
                world.source.shas = listOf(sha)
                world.deliver(sha)
                if (receiptState != "present") world.tags.tags.clear()
                world.tags.failWrites = true
                when (receiptState) {
                    "expired" -> world.apple.builds.replaceAll { it.copy(expired = true) }
                    "invalid" ->
                        world.apple.builds.replaceAll { it.copy(processingState = "PROCESSING") }
                    "absent" -> world.apple.builds.clear()
                }
                var checked: ((String) -> Boolean)? = null
                val incidents =
                    object : Incidents {
                        override fun sync(
                            findings: List<SweepFinding>,
                            tip: String,
                            checkedSubject: (String) -> Boolean,
                        ) {
                            checked = checkedSubject
                        }
                    }
                val store =
                    AppStoreRelease(
                        config,
                        world.apple,
                        world.tags,
                        world.source,
                        world.symbols(),
                        world.env("s"),
                        clock,
                        holdIssues =
                            HoldIssues {
                                if (!readable) throw CommandError("unreadable")
                                emptyList()
                            },
                    )
                Sweep(
                        config,
                        world.beta(),
                        store,
                        world.apple,
                        world.tags,
                        world.source,
                        incidents,
                        { false },
                    )
                    .run(sha, audit)
                val check = assertNotNull(checked)
                assertEquals(audit, check("audit"))
                for (key in
                    listOf(
                        "appstore/26.09.01",
                        "appstore/review-submission-id",
                        "hold/7",
                    )) assertEquals(readable, check(key), key)
                assertEquals(
                    receiptState != "missing",
                    check("receipt/26.09.01/234.1"),
                    receiptState,
                )
                assertTrue(check("receipt/26.09.01/999.1"))
                for (key in
                    listOf("internal/sha", "appstore", "incidents", "legacy/unknown")) assertTrue(
                    check(key),
                    key,
                )
            }
        }
    }

    private val sha = "source-sha"

    @Test
    fun `a sweep reports a delivered tip and never changes Apple`() = temp { output ->
        val world = World(output)
        world.deliver(sha)
        val effects = world.apple.calls.toList()
        val report = world.sweep().run(sha)
        assertEquals(SweepFinding("internal/$sha", "delivered"), report.findings.first())
        assertFalse(report.needsAction)
        assertEquals(effects, world.apple.calls)
    }

    @Test
    fun `a queued push is healthy and an undelivered tip asks for a delivery`() = temp { output ->
        val world = World(output)
        world.queued += sha
        assertEquals("queued", world.sweep().run(sha).findings.first().outcome)
        world.queued.clear()
        val finding = world.sweep().run(sha).findings.first()
        assertEquals(
            "Deliver it: https://github.com/owner/repo/actions",
            finding.operatorAction,
        )
        assertTrue(world.apple.calls.isEmpty())
    }

    @Test
    fun `a failed delivery asks for an explicit retry with the latest build number`() =
        temp { output ->
            val world = World(output)
            world.apple.rejection = AppleUpload("", "", "FAILED", "90001", "Bad binary")
            assertFails { world.deliver(sha) }
            val finding = world.sweep().run(sha).findings.first()
            assertContains(finding.operatorAction!!, "Apple error 90001")
            assertContains(
                finding.operatorAction!!,
                WorkflowHints(mapOf("GITHUB_REPOSITORY" to "owner/repo")).retry(sha, "234.1"),
            )
            assertEquals(
                listOf(finding),
                world.incidents.synced.single().filter { it.operatorAction != null },
            )
        }

    @Test
    fun `an upload Apple is still processing is reported without asking anyone`() = temp { output ->
        val world = World(output)
        world.apple.uploadOutcome = "INVISIBLE"
        assertFails { world.deliver(sha) }
        val report = world.sweep().run(sha)
        assertEquals("26.09.01/234.1 uncertain", report.findings.first().outcome)
        assertFalse(report.needsAction)
    }

    @Test
    fun `sweeps repair missing receipts of any age and audits check every receipt`() =
        temp { output ->
            val world = World(output)
            world.source.shas = listOf(sha, "second")
            world.tags.failWrites = true
            world.deliver(sha)
            world.deliver("second")
            world.tags.failWrites = false
            // Weeks without a working receipt token still end with every receipt written.
            world.apple.builds.replaceAll {
                if (it.number == "234.1")
                    it.copy(uploadedAt = now.minusDays(30).toOffsetDateTime().toString())
                else it
            }
            world.sweep().run("second")
            assertEquals("second", world.tags.commit("deploy/testflight/26.09.01/235.1"))
            assertEquals(sha, world.tags.commit("deploy/testflight/26.09.01/234.1"))
            // A receipt on the wrong commit is a repair error, reported only by an audit.
            world.tags.tags["deploy/testflight/26.09.01/234.1"] =
                "second" to world.tags.tags.getValue("deploy/testflight/26.09.01/234.1").second
            assertFalse(world.sweep().run("second").broken)
            assertTrue(world.sweep().run("second", audit = true).broken)
            assertEquals(listOf("upload", "distribute", "upload", "distribute"), world.apple.calls)
        }

    @Test
    fun `manual and foreign builds are never given receipts`() = temp { output ->
        val world = World(output)
        world.apple.builds +=
            TestFlightBuild(
                "manual",
                "1.0",
                "300.1",
                "VALID",
                false,
                now.toOffsetDateTime().toString(),
            )
        world.apple.builds +=
            TestFlightBuild(
                "old",
                "26.09.01",
                "12.1",
                "VALID",
                false,
                now.toOffsetDateTime().toString(),
            )
        world.apple.testers += setOf("manual", "old")
        world.sweep().run(sha, audit = true)
        assertTrue(world.tags.tags.isEmpty())
    }

    @Test
    fun `incident sync failures are repair errors but do not hide findings`() = temp { output ->
        val world = World(output)
        world.incidents.failure = true
        val report = world.sweep().run(sha)
        assertTrue(report.broken)
        assertNotNull(report.findings.first().operatorAction)
    }

    @Test
    fun `a build in a terminal TestFlight state is skipped, not a repair error`() = temp { output ->
        val world = World(output)
        world.apple.builds +=
            TestFlightBuild(
                "expired",
                "26.09.01",
                "234.1",
                "VALID",
                false,
                now.toOffsetDateTime().toString(),
            )
        val apple =
            object : Apple by world.apple {
                override fun testable(buildId: String): Boolean =
                    throw TerminalTestFlightState("EXPIRED")
            }
        val sweep =
            Sweep(
                config,
                world.beta(),
                AppStoreRelease(
                    config,
                    apple,
                    world.tags,
                    world.source,
                    world.symbols(),
                    world.env("s"),
                    clock,
                ),
                apple,
                world.tags,
                world.source,
                world.incidents,
                { false },
            )
        assertFalse(sweep.run(sha).broken)
    }

    @Test
    fun `a failed testability read is a repair error, never a silent skip`() = temp { output ->
        val world = World(output)
        world.source.shas = listOf(sha)
        world.tags.failWrites = true
        world.deliver(sha)
        world.tags.failWrites = false
        val apple =
            object : Apple by world.apple {
                override fun testable(buildId: String): Boolean =
                    throw CommandError("asc build-beta-detail view failed: HTTP 500")
            }
        val sweep =
            Sweep(
                config,
                world.beta(),
                AppStoreRelease(
                    config,
                    apple,
                    world.tags,
                    world.source,
                    world.symbols(),
                    world.env("s"),
                    clock,
                ),
                apple,
                world.tags,
                world.source,
                world.incidents,
                { false },
            )
        val report = sweep.run(sha)
        assertTrue(report.broken)
        assertEquals(
            "receipt/26.09.01/234.1",
            report.findings.first { it.repairError != null }.operationKey,
        )
    }
}
