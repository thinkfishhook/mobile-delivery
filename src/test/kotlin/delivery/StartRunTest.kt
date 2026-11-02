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
}
