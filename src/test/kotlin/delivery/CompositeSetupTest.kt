package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class CompositeSetupTest {
    @Test
    fun `scheduled maintenance always sweeps while manual dispatch preserves its operation`() =
        temp { root ->
            for ((event, operation) in
                listOf("schedule" to "sweep", "workflow_dispatch" to "audit")) {
                val output = root.resolve(event).createFile()
                configureComposite(
                    mapOf(
                        "ACTION_PATH" to "/tool/maintenance",
                        "DELIVERY_ACTION_BRANCH" to "main",
                        "DELIVERY_ACTION_REPOSITORY" to "owner/tool",
                        "DELIVERY_ACTION_SHA" to "v1",
                        "DELIVERY_ACTION_INPUTS" to """{"operation":"audit"}""",
                        "GITHUB_EVENT_NAME" to event,
                        "GITHUB_ENV" to output.toString(),
                    )
                )
                val values =
                    Regex("(?s)([A-Z_]+)<<([^\\n]+)\\n(.*?)\\n\\2\\n")
                        .findAll(output.readText())
                        .associate { it.groupValues[1] to it.groupValues[3] }
                assertEquals(operation, values["DELIVERY_OPERATION"])
                assertEquals(operation, workflowInputs(values)?.operation)
            }
        }

    @Test
    fun `summary renders outcomes and escapes table cell boundaries`() = temp { root ->
        val summary = root.resolve("summary.md").createFile()
        val output = root.resolve("output").createFile()
        val env =
            mapOf(
                "GITHUB_STEP_SUMMARY" to summary.toString(),
                "GITHUB_OUTPUT" to output.toString(),
                "DELIVERY_PHASE_OUTCOME" to "failure",
            )
        runTask(arrayOf("compositeSummary"), root, env, RecordingCommands())
        assertContains(summary.readText(), "| Result | failed |")
        runTask(
            arrayOf("compositeSummary"),
            root,
            env +
                mapOf(
                    "DELIVERY_INPUTS" to """{"operation":"verify-signing"}""",
                    "DELIVERY_PHASE_OUTCOME" to "success",
                ),
            RecordingCommands(),
        )
        assertContains(summary.readText(), "Signing identity verified")
        root
            .resolve("build/delivery")
            .createDirectories()
            .resolve("run-summary.json")
            .writeText("""{"runHealth":"completed","deliveries":"Paused | checked\nagain"}""")
        runTask(arrayOf("compositeSummary"), root, env, RecordingCommands())
        assertContains(summary.readText(), "| Delivery | Paused \\| checked again |")
        assertContains(output.readText(), "outcome<<")
        assertContains(output.readText(), "health<<")
        assertContains(output.readText(), "Paused | checked\nagain")
    }

    @Test
    fun `composite validation does not require copying caller runner syntax into inputs`() {
        val env =
            mapOf(
                "DELIVERY_VALIDATION_MODE" to "delivery",
                "DELIVERY_ACTION_SHA" to "v1",
                "IOS_DELIVERY_ENABLED" to "false",
                "DELIVERY_INPUTS" to """{"branch":"main"}""",
            )
        assertEquals(emptyList(), workflowProblems("validate", env))
        assertEquals(
            emptyList(),
            workflowProblems("validate", env + ("DELIVERY_COMPOSITE" to "true")),
        )
    }

    @Test
    fun `composite setup exports only operational metadata and preserves typed dispatch`() =
        temp { root ->
            val output = root.resolve("environment").createFile()
            val env =
                mapOf(
                    "ACTION_PATH" to "/actions/mobile-delivery/app-store-rollout",
                    "DELIVERY_ACTION_BRANCH" to "main",
                    "DELIVERY_ACTION_INPUTS" to
                        """{"branch":"untrusted-dispatch-branch","runs-on":"ignored","operation":"pause","reason":"Investigate failure","new_attempt":true}""",
                    "DELIVERY_ACTION_REPOSITORY" to "owner/tool",
                    "DELIVERY_ACTION_SHA" to "v1",
                    "IOS_APPSTORE_ENABLED" to "false",
                    "IOS_DELIVERY_ENABLED" to "",
                    "GITHUB_ENV" to output.toString(),
                    "JAVA_HOME" to "/caller/jdk",
                    "ASC_PRIVATE_KEY" to "do-not-export",
                    "ANALYTICS_KEY" to "app-secret",
                )
            runTask(arrayOf("configureComposite"), root, env, RecordingCommands())
            val values =
                Regex("(?s)([A-Z_]+)<<([^\\n]+)\\n(.*?)\\n\\2\\n")
                    .findAll(output.readText())
                    .associate { it.groupValues[1] to it.groupValues[3] }
            assertEquals("rollout", values["DELIVERY_VALIDATION_MODE"])
            assertEquals("true", values["DELIVERY_COMPOSITE"])
            assertEquals("owner/tool", values["DELIVERY_WORKFLOW_REPOSITORY"])
            assertEquals("false", values["IOS_APPSTORE_ENABLED"])
            assertFalse(values.containsKey("JAVA_HOME"))
            assertFalse(output.readText().contains("do-not-export"))
            assertFalse(output.readText().contains("app-secret"))
            val inputs = assertNotNull(workflowInputs(values))
            assertEquals("main", inputs.branch)
            assertEquals("pause", inputs.operation)
            assertTrue(inputs.newAttempt)
            assertFalse(values.getValue("DELIVERY_INPUTS").contains("runs-on"))
            assertEquals(
                emptyList(),
                workflowProblems(
                    "validate",
                    values +
                        mapOf(
                            "DELIVERY_SECRET_PRESENCE" to
                                """{"ASC_KEY_ID":true,"ASC_ISSUER_ID":true,"ASC_PRIVATE_KEY":true}""",
                            "IOS_APPSTORE_ENABLED" to "true",
                        ),
                ),
            )
        }

    @Test
    fun `composite setup rejects malformed dispatch before publishing environment`() =
        temp { root ->
            val output = root.resolve("environment")
            val env =
                mapOf(
                    "ACTION_PATH" to "/actions/tool/app-store-rollout",
                    "DELIVERY_ACTION_BRANCH" to "main",
                    "DELIVERY_ACTION_INPUTS" to "not-json",
                    "GITHUB_ENV" to output.toString(),
                )
            val failure = assertFails {
                runTask(arrayOf("configureComposite"), root, env, RecordingCommands())
            }
            assertContains(failure.message.orEmpty(), "dispatch")
            assertFalse(output.exists())
        }
}
