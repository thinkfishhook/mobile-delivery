package delivery

import kotlin.test.*
import org.junit.Test

class StartRunTest {
    private val a = DeliveryToolHarness.sha('a')
    private val b = DeliveryToolHarness.sha('b')

    @Test
    fun `selection exposes superseded push without reading delivery records`() = temp { root ->
        val h = DeliveryToolHarness(root)
        h.tip = a
        h.remoteTip = b
        val start =
            StartRun(
                config,
                GitSource(root, config, h.commands, "trunk"),
                GitHub("owner/repo", h.commands, emptyMap()),
                mapOf("GITHUB_SHA" to a, "GITHUB_EVENT_NAME" to "push"),
                "trunk",
            )
        assertEquals(StartRunSelection.Superseded(a, b), start.select(WorkflowEvent()))
        assertEquals(1, h.remoteCalls)
        assertTrue(h.commands.calls.none { it.args.first() == "asc" })
    }

    @Test
    fun `selection exposes explicit older commit as proceed without live tip lookup`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            h.failBranchRead = true
            val start =
                StartRun(
                    config,
                    GitSource(root, config, h.commands, "trunk"),
                    GitHub("owner/repo", h.commands, emptyMap()),
                    mapOf("GITHUB_SHA" to b, "GITHUB_EVENT_NAME" to "workflow_dispatch"),
                    "trunk",
                )
            assertEquals(
                StartRunSelection.Proceed(a),
                start.select(WorkflowEvent(inputs = WorkflowInputs(sha = a))),
            )
            assertEquals(0, h.remoteCalls)
        }

    private class RunnerStopped : InterruptedException()

    private fun cancel(status: Int, stopRunner: Boolean) = temp { root ->
        val h = DeliveryToolHarness(root)
        h.tip = a
        h.remoteTip = b
        val original = requireNotNull(h.commands.respond)
        var summary: RunSummary? = null
        var now = 0L
        h.commands.respond = { call ->
            if (call.args.getOrNull(2) == "repos/owner/repo/actions/runs/10/cancel") {
                assertContains(requireNotNull(summary).deliveries, "superseded by $b, not tested")
                now += 1000 // request duration counts against the same wall-clock deadline
                CommandResult(if (status in 200..299) 0 else 1, "HTTP/2.0 $status\n\n", "")
            } else original(call)
        }
        val start =
            StartRun(
                config,
                GitSource(root, config, h.commands, "trunk"),
                GitHub(
                    "owner/repo",
                    h.commands,
                    mapOf("GH_TOKEN" to "job-token", "IOS_RECEIPT_TOKEN" to "receipt-token"),
                ),
                mapOf("GITHUB_SHA" to a, "GITHUB_EVENT_NAME" to "push", "GITHUB_RUN_ID" to "10"),
                "trunk",
                pause = { delay ->
                    assertTrue(delay in 1L..2000L)
                    if (stopRunner) throw RunnerStopped()
                    now += delay + 500 // wall time, not a fixed iteration count
                },
                elapsedMillis = { now },
            )
        val selection = start.select(WorkflowEvent()) as StartRunSelection.Superseded
        val failure = assertFails { start.cancelSuperseded(selection) { summary = it } }
        if (stopRunner) assertIs<RunnerStopped>(failure)
        else {
            assertContains(
                failure.message.orEmpty(),
                if (status == 202) "180 seconds" else "HTTP $status",
            )
            assertContains(failure.message.orEmpty(), "permissions:\n  actions: write")
            if (status == 202) assertTrue(now in 180_000L..182_499L)
            assertEquals("failed", requireNotNull(summary).runHealth)
            assertContains(requireNotNull(summary).actionNeeded.single(), "actions: write")
        }
        val calls = h.commands.calls.filter { it.args.first() == "gh" }
        assertEquals(2, calls.size) // live tip and one cancel; no delivery reads or writes
        val request = calls.last()
        assertContains(request.args, "POST")
        assertEquals("job-token", request.env["GH_TOKEN"])
        assertEquals(30L, request.timeoutSeconds)
        assertTrue(h.commands.calls.none { it.args.first() == "asc" })
        assertNotNull(summary)
    }

    @Test
    fun `superseded push requests cancellation once and waits for runner interruption`() =
        cancel(202, true)

    @Test fun `refused cancellation fails with caller permission fix`() = cancel(403, false)

    @Test
    fun `accepted cancellation times out loudly instead of returning success`() = cancel(202, false)

    @Test fun `HTTP success without acceptance fails closed`() = cancel(200, false)

    @Test fun `transient cancel rejection is never retried`() = cancel(503, false)
}
