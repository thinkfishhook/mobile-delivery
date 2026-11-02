package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class MaintenanceTipTest {
    @Test
    fun `maintenance evaluates the current branch and supersedes old incidents`() = temp { root ->
        for (mode in listOf("sweep", "audit", "finishRun")) {
            val h = DeliveryToolHarness(root.resolve(mode).createDirectories())
            val old = DeliveryToolHarness.sha('a')
            val current = DeliveryToolHarness.sha('b')
            h.tip = old
            h.run("sweep", "schedule").getOrThrow()
            assertEquals("open", h.issues.single().string("state"))
            h.remoteTip = current

            val output = h.run(mode, "schedule", sha = old).getOrThrow()

            assertContains(output, "internal/$current: not delivered")
            assertFalse("internal/$old: not delivered" in output)
            assertEquals("closed", h.issues.first().string("state"))
            assertEquals("not_planned", h.issues.first().string("state_reason"))
            assertContains(h.issues.last().string("body"), "internal/$current")
            assertEquals(current, h.tip)
        }
    }

    @Test
    fun `maintenance stops before API calls when refreshing the branch fails`() = temp { root ->
        for (mode in listOf("sweep", "audit", "finishRun")) {
            val h = DeliveryToolHarness(root.resolve(mode).createDirectories())
            h.failBranchFetch = true

            assertTrue(h.run(mode, "schedule").isFailure)
            assertEquals(0, h.remoteCalls)
            assertTrue(h.issues.isEmpty())
            assertTrue(h.tags.isEmpty())
        }
    }
}
