package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class WorkflowValidationTest {
    @Test
    fun `composites validate named build values without asking for duplicate presence secrets`() {
        val env =
            mapOf(
                "DELIVERY_COMPOSITE" to "true",
                "DELIVERY_ACTION_SHA" to "v1",
                "DELIVERY_VALIDATION_MODE" to "delivery",
                "DELIVERY_INPUTS" to """{"branch":"main"}""",
                "IOS_DELIVERY_ENABLED" to "false",
                "ANALYTICS_KEY" to "never-print-this-key",
            )
        assertEquals(emptyList(), workflowProblems("validate", env, listOf("ANALYTICS_KEY")))
        val missing = workflowProblems("validate", env - "ANALYTICS_KEY", listOf("ANALYTICS_KEY"))
        assertContains(missing.single(), "ANALYTICS_KEY is missing")
        assertFalse(missing.joinToString().contains("never-print-this-key"))
        assertTrue(
            workflowProblems("validate", env + ("ANALYTICS_KEY" to " "), listOf("ANALYTICS_KEY"))
                .isNotEmpty()
        )
    }

    @Test
    fun `new attempt accepts native Actions booleans and event strings`() {
        for (value in listOf("true", "\"true\"")) {
            assertTrue(
                workflowInputs(mapOf("DELIVERY_INPUTS" to """{"new_attempt":$value}"""))!!
                    .newAttempt
            )
        }
        assertFalse(workflowInputs(mapOf("DELIVERY_INPUTS" to "{}"))!!.newAttempt)
    }

    @Test
    fun `new attempt cannot be attached to an ordinary deliver operation`() {
        val problems =
            workflowProblems(
                "validate",
                mapOf(
                    "DELIVERY_VALIDATION_MODE" to "delivery",
                    "DELIVERY_INPUTS" to
                        """{"branch":"trunk","operation":"deliver","new_attempt":true}""",
                ),
            )
        assertTrue(problems.any { "new_attempt requires operation=retry" in it })
    }

    @Test
    fun `each operation asks only for the credentials it uses`() {
        val asc = setOf("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_PRIVATE_KEY")
        val signing =
            setOf(
                "IOS_DISTRIBUTION_P12_BASE64",
                "IOS_DISTRIBUTION_P12_PASSWORD",
                "IOS_PROFILE_BASE64",
            )
        val cases =
            listOf(
                Triple("delivery", "deliver", asc + signing + "IOS_RECEIPT_TOKEN"),
                Triple("maintenance", "sweep", asc + "IOS_RECEIPT_TOKEN"),
                Triple("maintenance", "audit", asc + "IOS_RECEIPT_TOKEN"),
                Triple("maintenance", "verify-signing", signing),
                Triple("maintenance", "app-store-preflight", asc),
                Triple("release", "promote", asc + "IOS_RECEIPT_TOKEN"),
                Triple("rollout", "pause", asc),
            )
        for ((mode, operation, secrets) in cases) {
            val inputs = buildJsonObject {
                put("branch", "main")
                put("operation", operation)
                put("reason", "test")
            }
            val env =
                mapOf(
                    "DELIVERY_VALIDATION_MODE" to mode,
                    "DELIVERY_INPUTS" to inputs.toString(),
                    "IOS_DELIVERY_ENABLED" to "true",
                    "IOS_APPSTORE_ENABLED" to "true",
                )
            val required = secrets
            val problems = workflowProblems("validate", env)
            assertEquals(required.size, problems.size, "$mode/$operation: $problems")
            required.forEach { key -> assertTrue(problems.any { "$key is missing" in it }) }
            val present = JsonObject(required.associateWith { JsonPrimitive(true) }).toString()
            assertTrue(
                workflowProblems("validate", env + ("DELIVERY_SECRET_PRESENCE" to present))
                    .isEmpty()
            )
        }
    }

    @Test
    fun `config input secret and switch problems appear together without side effects or secret values`() =
        temp { root ->
            val document =
                kotlinx.serialization.json.JsonObject(
                    json.parseToJsonElement(resource("delivery.json")).jsonObject - "app_id"
                )
            root
                .resolve(".github")
                .createDirectories()
                .resolve("delivery.json")
                .writeText(document.toString())
            val commands = RecordingCommands()
            val env =
                mapOf(
                    "DELIVERY_VALIDATION_MODE" to "delivery",
                    "DELIVERY_INPUTS" to """{}""",
                    "ASC_PRIVATE_KEY" to "never-print-this-value",
                )
            val failure = assertFails { runTask(arrayOf("validate"), root, env, commands) }
            for (problem in
                listOf(
                    ".github/delivery.json: app_id",
                    "caller with: branch",
                    "ios-delivery environment secret ASC_KEY_ID",
                    "repository or environment variable IOS_DELIVERY_ENABLED",
                )) assertContains(failure.message.orEmpty(), problem)
            assertFalse(failure.message.orEmpty().contains("never-print-this-value"))
            assertTrue(commands.calls.isEmpty())
        }

    @Test
    fun `read only preflight requires Apple credentials but no signing or receipt credential`() {
        val env =
            mapOf(
                "DELIVERY_VALIDATION_MODE" to "maintenance",
                "DELIVERY_INPUTS" to """{"branch":"main","operation":"app-store-preflight"}""",
                "DELIVERY_SECRET_PRESENCE" to
                    """{"ASC_KEY_ID":true,"ASC_ISSUER_ID":true,"ASC_PRIVATE_KEY":true}""",
            )
        assertTrue(workflowProblems("validate", env).isEmpty())
    }

    @Test
    fun `missing branch input stops before commands`() = temp { root ->
        val commands = RecordingCommands()
        val failure = assertFails {
            DeliveryTool(root, config, commands, mapOf("IOS_DELIVERY_ENABLED" to "true"))
                .run("startRun")
        }
        assertContains(failure.message.orEmpty(), "caller with: branch is missing")
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `each switch accepts only explicit true or false`() {
        for ((mode, key) in
            listOf(
                "startRun" to "IOS_DELIVERY_ENABLED",
                "appStoreRelease" to "IOS_APPSTORE_ENABLED",
            )) {
            for (value in listOf("true", "false")) assertTrue(
                workflowProblems(
                        mode,
                        mapOf(key to value, "DELIVERY_INPUTS" to """{"branch":"trunk"}"""),
                    )
                    .isEmpty()
            )
            for (value in listOf(null, "yes", "TRUE", "")) {
                val env =
                    value?.let { mapOf(key to it, "DELIVERY_INPUTS" to """{"branch":"trunk"}""") }
                        ?: mapOf("DELIVERY_INPUTS" to """{"branch":"trunk"}""")
                assertContains(workflowProblems(mode, env).single(), "variable $key")
                assertContains(workflowProblems(mode, env).single(), "repository or environment")
            }
        }
    }

    @Test
    fun `configuration and switch errors are reported together before commands`() = temp { root ->
        root.resolve(".github").createDirectories().resolve("delivery.json").writeText("{}")
        val commands = RecordingCommands()
        val failure = assertFails { runTask(arrayOf("startRun"), root, emptyMap(), commands) }
        assertContains(failure.message.orEmpty(), "app_id")
        assertContains(failure.message.orEmpty(), "IOS_DELIVERY_ENABLED")
        assertTrue(commands.calls.isEmpty())
    }
}
