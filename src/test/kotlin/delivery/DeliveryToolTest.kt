package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class DeliveryToolTest {
    @Test
    fun `disabled composite pushes and schedules verify without API calls`() = temp { root ->
        for (event in listOf("push", "schedule")) {
            val h = DeliveryToolHarness(root)
            val result =
                h.run(
                        "startRun",
                        event,
                        sha = a,
                        extraEnv =
                            mapOf(
                                "DELIVERY_COMPOSITE" to "true",
                                "IOS_DELIVERY_ENABLED" to "false",
                            ),
                    )
                    .getOrThrow()
            assertContains(result, "operation=verify")
            assertContains(result, "sha=$a")
            assertEquals(0, h.remoteCalls)
        }
    }

    private val a = DeliveryToolHarness.sha('a')
    private val b = DeliveryToolHarness.sha('b')

    @Test
    fun `run summary names ignored marketing versions`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.storeVersions =
            """{"data":[{"id":"old","attributes":{"versionString":"2026.06.02","appStoreState":"REJECTED"}},{"id":"legacy","attributes":{"versionString":"1.0","appStoreState":"READY_FOR_SALE"}}]}"""
        h.run("startRun", "push").getOrThrow()
        h.run("sweep", "schedule").getOrThrow()
        val summary =
            json.decodeFromString<RunSummary>(
                root.resolve("build/delivery/run-summary.json").readText()
            )
        assertEquals(listOf("1.0", "2026.06.02"), summary.ignoredVersions)
    }

    @Test
    fun `explicit false skips App Store changes without remote calls`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val output =
            h.run(
                    "appStoreRelease",
                    "schedule",
                    extraEnv = mapOf("IOS_APPSTORE_ENABLED" to "false"),
                )
                .getOrThrow()
        assertContains(output, "App Store release disabled")
        assertEquals(0, h.remoteCalls)
    }

    @Test
    fun `superseded push finishes green without any delivery work`() = temp { root ->
        val h = DeliveryToolHarness(root)
        // checkout can reset origin/trunk to the queued event's commit.
        h.tip = a
        h.remoteTip = b
        val output = h.run("startRun", "push", sha = a).getOrThrow()
        assertTrue("operation=none" in output, output)
        assertTrue("superseded by $b" in output, output)
        assertEquals(1, h.remoteCalls)
        assertEquals(
            listOf("gh", "api", "repos/owner/repo/git/ref/heads/trunk", "--include"),
            h.commands.calls.single { it.args.first() == "gh" }.args,
        )
    }

    @Test
    fun `unreadable current branch fails closed before delivery work`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.failBranchRead = true
        val failure = h.run("startRun", "push").exceptionOrNull() as DeliveryToolHarness.ToolFailure
        assertContains(failure.output, "GitHub API failed: git/ref/heads/trunk (HTTP 403)")
        assertFalse("operation=deliver" in failure.output)
        assertEquals(1, h.remoteCalls)
    }

    @Test
    fun `malformed current branch fails closed before delivery work`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.remoteTip = "not-a-commit"
        val failure = h.run("startRun", "push").exceptionOrNull() as DeliveryToolHarness.ToolFailure
        assertContains(failure.output, "Expected a full commit SHA for branch trunk")
        assertFalse("operation=deliver" in failure.output)
        assertEquals(1, h.remoteCalls)
    }

    @Test
    fun `current push still starts a delivery`() = temp { root ->
        val h = DeliveryToolHarness(root)
        assertContains(h.run("startRun", "push").getOrThrow(), "operation=deliver")
    }

    @Test
    fun `explicit deliver of an older SHA still starts a delivery`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.tip = b
        h.failBranchRead = true
        val output =
            h.run(
                    "startRun",
                    "workflow_dispatch",
                    inputs = mapOf("operation" to "deliver", "sha" to a),
                )
                .getOrThrow()
        assertTrue("operation=deliver" in output, output)
    }

    @Test
    fun `sweep with pending operator action stays green and annotates the action`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val output = h.run("sweep", "schedule").getOrThrow()
        val action = "Deliver it: https://github.com/owner/repo/actions"
        assertTrue("::warning::$action" in output, output)
        assertTrue("Deliver it" in root.resolve("build/delivery/run-summary.json").readText())
        assertContains(h.issues.single()["labels"].toString(), INCIDENT_LABEL)
        assertEquals(setOf(HOLD_LABEL, INCIDENT_LABEL, OWNER_LABEL), h.labels)
    }

    @Test
    fun `a scheduled sweep does nothing while delivery is switched off`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val output =
            h.run("sweep", "schedule", extraEnv = mapOf("IOS_DELIVERY_ENABLED" to "false"))
                .getOrThrow()
        assertTrue("Delivery disabled" in output, output)
        assertEquals(0, h.remoteCalls)
        assertTrue(h.issues.isEmpty())
    }

    @Test
    fun `sweep repair errors stay red`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.failIssueWrites = true
        assertTrue(h.run("sweep", "schedule").isFailure)
    }

    @Test
    fun `refused deliver prints the exact retry command`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.counts[a] = 400
        h.upload("26.09.01", "400.1", "FAILED", "90001")
        val failure =
            h.run(
                    "startRun",
                    "workflow_dispatch",
                    inputs = mapOf("operation" to "deliver", "sha" to a),
                )
                .exceptionOrNull() as DeliveryToolHarness.ToolFailure
        assertTrue(
            "gh workflow run ship-it.yml -f operation=retry -f sha=$a -f expected_attempt=400.1 -f reason=\"" in
                failure.output,
            failure.output,
        )
    }

    @Test
    fun `new attempt dispatch and its rerun start after a delivered receipt`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.counts[a] = 400
        h.tags["deploy/testflight/26.09.01/400.1"] = a to "{}"
        val inputs =
            mapOf(
                "operation" to "retry",
                "sha" to a,
                "expected_attempt" to "400.1",
                "reason" to "Rebuild",
                "new_attempt" to "true",
            )
        for (attempt in listOf("1", "2")) {
            val output =
                h.run(
                        "startRun",
                        "workflow_dispatch",
                        inputs = inputs,
                        extraEnv = mapOf("GITHUB_RUN_ATTEMPT" to attempt),
                    )
                    .getOrThrow()
            assertContains(output, "operation=deliver")
        }
        val recovery =
            h.run("startRun", "workflow_dispatch", inputs = inputs - "new_attempt").getOrThrow()
        assertContains(recovery, "operation=sweep")
    }

    @Test
    fun `stale new attempt summary points to the real caller and latest number`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.counts[a] = 400
        h.tags["deploy/testflight/26.09.01/400.2"] = a to "{}"
        val failure =
            h.run(
                    "startRun",
                    "workflow_dispatch",
                    inputs =
                        mapOf(
                            "operation" to "retry",
                            "sha" to a,
                            "expected_attempt" to "400.1",
                            "reason" to "Rebuild",
                            "new_attempt" to "true",
                        ),
                )
                .exceptionOrNull() as DeliveryToolHarness.ToolFailure
        assertContains(failure.output, "Stale retry: the latest build")
        assertContains(
            failure.output,
            "gh workflow run ship-it.yml -f operation=retry -f sha=$a -f expected_attempt=400.2",
        )
    }

    @Test
    fun `a hand-made receipt tag stops deliveries and sweeps with an error naming it`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            h.tags["deploy/testflight/26.09.03"] = b to "{}"
            listOf(h.run("startRun", "push", sha = b), h.run("sweep", "schedule")).forEach {
                val failure = it.exceptionOrNull() as DeliveryToolHarness.ToolFailure
                assertContains(
                    failure.output,
                    "::error::deploy/testflight/26.09.03 is not a receipt the delivery tool writes",
                )
                assertContains(
                    failure.output,
                    "git push origin :refs/tags/deploy/testflight/26.09.03",
                )
            }
        }

    @Test
    fun `sweep calls do not grow with settled history`() = temp { root ->
        fun calls(builds: Int): Int {
            val h = DeliveryToolHarness(root.resolve("builds-$builds").createDirectories())
            h.tip = DeliveryToolHarness.settled(h, builds)
            h.run("sweep", "schedule").getOrThrow()
            return h.remoteCalls
        }
        val small = calls(5)
        val large = calls(SETTLED)
        println("GitHub and Apple calls per sweep: 5 settled builds=$small, $SETTLED=$large")
        assertEquals(small, large)
    }

    @Test
    fun `a sweep restores a missing receipt and only an audit checks existing ones`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            h.counts[config.baseline] = 0
            h.tip = DeliveryToolHarness.settled(h, 3)
            val deleted = "deploy/testflight/26.09.01/1.1"
            val original = h.tags.remove(deleted)!!
            h.run("sweep", "schedule").getOrThrow()
            assertEquals(original.first, h.tags.getValue(deleted).first)
            // A receipt moved onto another commit is a collision that only an audit reads.
            val moved = "deploy/testflight/26.09.01/2.1"
            h.tags[moved] = original.first to h.tags.getValue(moved).second
            h.run("sweep", "schedule").getOrThrow()
            assertTrue(
                h.run("audit", "workflow_dispatch", inputs = mapOf("operation" to "audit"))
                    .isFailure
            )
        }

    @Test
    fun `App Store dispatches are refused while the switch is off`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val promote = mapOf("operation" to "promote", "reason" to "Crash fix")
        assertTrue(h.run("appStoreRelease", "workflow_dispatch", inputs = promote).isFailure)
        assertTrue(
            h.run("appStoreRelease", "workflow_dispatch", inputs = mapOf("operation" to "pause"))
                .isFailure
        )
        assertTrue(h.commands.calls.none { it.args.first() == "asc" })
    }

    @Test
    fun `a pause runs in its own job and changes only the rollout`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.storeVersions =
            """{"data":[{"type":"appStoreVersions","id":"v1","attributes":{"versionString":"26.09.01","appStoreState":"READY_FOR_SALE"},"relationships":{"build":{"data":{"type":"builds","id":"b1"}},"appStoreVersionPhasedRelease":{"data":{"type":"appStoreVersionPhasedReleases","id":"p1"}}}}],"included":[{"type":"builds","id":"b1","attributes":{"version":"400.1"}},{"type":"appStoreVersionPhasedReleases","id":"p1","attributes":{"phasedReleaseState":"ACTIVE","currentDayNumber":2,"totalPauseDuration":0}}]}"""
        val pause = mapOf("operation" to "pause", "reason" to "Crash spike")
        val enabled = mapOf("IOS_APPSTORE_ENABLED" to "true")
        // The release job waits behind deliveries, so it never carries a rollout change.
        assertTrue(
            h.run("appStoreRelease", "workflow_dispatch", inputs = pause, extraEnv = enabled)
                .isFailure
        )
        assertTrue(h.commands.calls.none { it.args.first() == "asc" })
        h.run("rolloutControl", "workflow_dispatch", inputs = pause, extraEnv = enabled)
            .getOrThrow()
        assertEquals(
            listOf(listOf("versions", "list"), listOf("versions", "phased-release", "update")),
            h.commands.calls
                .filter { it.args.first() == "asc" }
                .map { it.args.drop(1).take(if (it.args[2] == "list") 2 else 3) },
        )
        assertTrue(h.commands.calls.none { it.args.first() == "gh" })
    }

    @Test
    fun `a manual promote with nothing newer finishes green after reads only`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val output =
            h.run(
                    "appStoreRelease",
                    "workflow_dispatch",
                    inputs =
                        mapOf(
                            "operation" to "promote",
                            "reason" to "Crash fix",
                            "rollout" to "all",
                        ),
                    extraEnv = mapOf("IOS_APPSTORE_ENABLED" to "true"),
                )
                .getOrThrow()
        assertTrue("no TestFlight build is newer" in output, output)
        assertContains(output, "outcome<<")
        assertTrue(Regex("version<<[^\\n]+\\n\\n").containsMatchIn(output))
        assertTrue(Regex("build<<[^\\n]+\\n\\n").containsMatchIn(output))
        assertTrue(
            h.commands.calls
                .filter { it.args.first() == "asc" }
                .all { it.args[2] in setOf("list", "submissions-list") }
        )
    }

    private companion object {
        const val SETTLED = 500
    }
}
