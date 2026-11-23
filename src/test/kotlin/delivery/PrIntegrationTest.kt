package delivery

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.*
import org.junit.Test

class PrIntegrationTest {
    private val root = Path.of(System.getProperty("delivery.root"))
    private val kinds = listOf("xcode", "kmp", "quickstart")

    @Test
    fun `PR verification refuses forks and isolates admitted code on hosted Macs`() {
        for (kind in kinds) {
            val doc =
                workflowDocument(
                    root.resolve("examples/$kind/.github/workflows/pr-checks.yml").readText()
                )
            val triggers = yamlMapping(doc["on"] ?: doc[true])
            assertEquals(setOf("pull_request"), triggers.keys)
            val events = yamlMapping(triggers["pull_request"])["types"] as List<*>
            assertTrue(
                events.containsAll(listOf("opened", "synchronize", "reopened", "ready_for_review"))
            )
            val jobs = yamlMapping(doc["jobs"])
            val source = yamlMapping(jobs["source"])
            assertEquals("ubuntu-latest", source["runs-on"])
            val script = workflowSteps(source).single()["run"].toString()
            assertEquals(
                0,
                execute(
                    script,
                    mapOf("SOURCE_REPOSITORY" to "owner/app", "TARGET_REPOSITORY" to "owner/app"),
                ),
            )
            assertNotEquals(
                0,
                execute(
                    script,
                    mapOf(
                        "SOURCE_REPOSITORY" to "outside/fork",
                        "TARGET_REPOSITORY" to "owner/app",
                    ),
                ),
            )
            val verify = yamlMapping(jobs["verify"])
            assertEquals("macos-26", verify["runs-on"])
            val queue = yamlMapping(verify["concurrency"])
            assertEquals(
                "pr-verification-${'$'}{{ github.event.pull_request.number }}",
                queue["group"],
            )
            assertEquals(true, queue["cancel-in-progress"])
            assertEquals("source", verify["needs"])
            assertNull(verify["if"])
            assertTrue(secretReferences(verify.toString()).isEmpty())
        }
    }

    @Test
    fun `required PR check cannot pass skipped cancelled or failed verification`() {
        for (kind in kinds) {
            val doc =
                workflowDocument(
                    root.resolve("examples/$kind/.github/workflows/pr-checks.yml").readText()
                )
            val gate = yamlMapping(yamlMapping(doc["jobs"])["checks"])
            assertEquals("PR checks", gate["name"])
            assertEquals("always()", gate["if"])
            assertEquals(listOf("source", "verify"), gate["needs"])
            val script = workflowSteps(gate).single()["run"].toString()
            val states = listOf("success", "failure", "cancelled", "skipped", "")
            for (source in states) for (verify in states) {
                val exit =
                    execute(script, mapOf("SOURCE_RESULT" to source, "VERIFY_RESULT" to verify))
                assertEquals(
                    source == "success" && verify == "success",
                    exit == 0,
                    "$kind source=$source verify=$verify",
                )
            }
        }
    }

    @Test
    fun `publishing composite smoke checks require PR rejection`() = temp { directory ->
        val document =
            workflowDocument(root.resolve(".github/workflows/composite-self-test.yml").readText())
        val jobs = yamlMapping(document["jobs"])
        for (name in listOf("disabled-rollout", "disabled-release", "disabled-testflight")) {
            val script = workflowSteps(yamlMapping(jobs[name])).last()["run"].toString()
            val values =
                mapOf(
                    "EVENT" to "pull_request",
                    "RESULT" to "failure",
                    "DELIVERY_COMPOSITE" to "true",
                    "PUBLISHED" to "false",
                    "OPERATION" to "",
                    "VERIFIED_SHA" to "",
                )
            assertEquals(0, execute(script, values, directory), name)
            assertNotEquals(0, execute(script, values + ("RESULT" to "success"), directory), name)
        }
    }

    private fun execute(script: String, values: Map<String, String>, directory: Path? = null): Int {
        val process =
            ProcessBuilder("bash", "-e", "-o", "pipefail", "-c", script)
                .redirectErrorStream(true)
                .directory(directory?.toFile())
                .apply { environment().putAll(values) }
                .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("PR gate did not complete")
        }
        return process.exitValue()
    }
}
