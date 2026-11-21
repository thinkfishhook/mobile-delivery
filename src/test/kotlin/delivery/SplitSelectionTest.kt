package delivery

import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class SplitSelectionTest {
    private val env =
        mapOf(
            "GITHUB_ACTIONS" to "true",
            "GITHUB_REPOSITORY" to "owner/repo",
            "GITHUB_SHA" to "d".repeat(40),
            "GITHUB_REF" to "refs/heads/experiment",
            "GITHUB_EVENT_NAME" to "workflow_dispatch",
            "GITHUB_RUN_ID" to "900",
            "GITHUB_RUN_ATTEMPT" to "1",
            "GITHUB_WORKFLOW_REF" to
                "owner/repo/.github/workflows/deploy.yml@refs/heads/experiment",
        )

    private fun run(
        sha: String = "a".repeat(40),
        branch: String = "trunk",
        event: String = "push",
        conclusion: String = "success",
        repo: String = "owner/repo",
    ) = buildJsonObject {
        put("id", "123")
        put("run_attempt", 1)
        put("head_sha", sha)
        put("head_branch", branch)
        put("event", event)
        put("status", "completed")
        put("conclusion", conclusion)
        put("path", ".github/workflows/ci.yml")
        put("workflow_id", "10")
        putJsonObject("head_repository") { put("full_name", repo) }
    }

    @Test
    fun `automatic uses exact upstream run rather than deployment workflow SHA`() {
        val upstream = run()
        val selected =
            automaticPreparationRequest(
                env,
                upstream,
                upstream,
                JsonObject(mapOf("id" to JsonPrimitive("10"))),
                "trunk",
                "ci.yml",
                config.bundleId,
            )
        assertEquals("a".repeat(40), selected.sha)
        assertEquals("123", selected.producer.runId)
        assertEquals(PackageOrigin.CI_VERIFIED, selected.origin)
        assertTrue(automaticUploadEligible(selected) { selected.sha })
        assertFalse(automaticUploadEligible(selected) { "b".repeat(40) })
    }

    @Test
    fun `failed cancelled PR foreign workflow and stale attempts cannot deploy`() {
        val valid = run()
        for (invalid in
            listOf(
                run(conclusion = "failure"),
                run(conclusion = "cancelled"),
                run(event = "pull_request"),
                run(branch = "experiment"),
                run(repo = "other/repo"),
                JsonObject(valid + ("workflow_id" to JsonPrimitive("11"))),
                JsonObject(valid + ("run_attempt" to JsonPrimitive(2))),
            )) {
            assertFails {
                automaticPreparationRequest(
                    env,
                    valid,
                    invalid,
                    JsonObject(mapOf("id" to JsonPrimitive("10"))),
                    "trunk",
                    "ci.yml",
                    config.bundleId,
                )
            }
        }
    }

    @Test
    fun `manual selected branch is frozen independently of integration head or AI status`() {
        val selected = manualPreparationRequest(env, config.bundleId)
        assertEquals(env["GITHUB_SHA"], selected.sha)
        assertEquals("refs/heads/experiment", selected.ref)
        assertEquals(PackageOrigin.MANUAL, selected.origin)
        assertFails {
            manualPreparationRequest(env + ("GITHUB_EVENT_NAME" to "pull_request"), config.bundleId)
        }
    }
}
