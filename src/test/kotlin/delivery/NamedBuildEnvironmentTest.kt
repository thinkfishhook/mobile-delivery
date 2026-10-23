package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class NamedBuildEnvironmentTest {
    @Test
    fun `validation reports every missing named build value without exposing values`() =
        temp { root ->
            val document = json.parseToJsonElement(resource("delivery.json")).jsonObject
            val path = root.resolve(".github/delivery.json")
            path.parent.createDirectories()
            path.writeText(
                JsonObject(
                        document +
                            ("build_environment" to
                                JsonArray(
                                    listOf(
                                        JsonPrimitive("ANALYTICS_KEY"),
                                        JsonPrimitive("ANALYTICS_HOST"),
                                    )
                                ))
                    )
                    .toString()
            )
            val env =
                mapOf(
                    "DELIVERY_ACTION_SHA" to "a".repeat(40),
                    "DELIVERY_VALIDATION_MODE" to "delivery",
                    "IOS_DELIVERY_ENABLED" to "false",
                    "DELIVERY_INPUTS" to """{"branch":"trunk"}""",
                )
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    DeliveryConfig.load(root, env, "validate")
                }
            assertContains(failure.message.orEmpty(), "ANALYTICS_KEY")
            assertContains(failure.message.orEmpty(), "ANALYTICS_HOST")
            DeliveryConfig.load(
                root,
                env + mapOf("ANALYTICS_KEY" to "key", "ANALYTICS_HOST" to "host"),
                "validate",
            )
        }

    @Test
    fun `named values reach app commands without importing other secrets`() {
        val env =
            mapOf(
                "PATH" to "/bin",
                "ANALYTICS_KEY" to "key",
                "ANALYTICS_HOST" to "host",
                "ASC_PRIVATE_KEY" to "private",
                "GH_TOKEN" to "token",
                "UNDECLARED" to "hidden",
            )
        assertEquals(
            mapOf("PATH" to "/bin", "ANALYTICS_KEY" to "key", "ANALYTICS_HOST" to "host"),
            appEnvironment(env, listOf("ANALYTICS_KEY", "ANALYTICS_HOST")),
        )
        assertEquals(mapOf("PATH" to "/bin"), toolEnvironment(env))
    }

    @Test
    fun `missing named values fail before building and never report values`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                appEnvironment(
                    mapOf("ANALYTICS_KEY" to "do-not-print"),
                    listOf("ANALYTICS_KEY", "ANALYTICS_HOST"),
                )
            }
        assertContains(error.message.orEmpty(), "ANALYTICS_HOST")
        assertFalse(error.message.orEmpty().contains("do-not-print"))
    }

    @Test
    fun `declarations cannot expose platform or deployment credentials`() {
        listOf(
                "PATH",
                "HOME",
                "ASC_PRIVATE_KEY",
                "IOS_RECEIPT_TOKEN",
                "GH_TOKEN",
                "GITHUB_TOKEN",
                "RUNNER_TEMP",
                "ACTIONS_RUNTIME_TOKEN",
                "DELIVERY_INPUTS",
                "bad-key",
            )
            .forEach { key ->
                val failure =
                    assertFailsWith<IllegalArgumentException>(key) {
                        appEnvironment(mapOf(key to "value"), listOf(key))
                    }
                assertContains(failure.message.orEmpty(), "build_environment")
                assertContains(failure.message.orEmpty(), key)
            }
        assertFailsWith<IllegalArgumentException> {
            appEnvironment(mapOf("KEY" to "value"), listOf("KEY", "KEY"))
        }
    }

    @Test
    fun `named values preserve whitespace newlines and equals signs`() {
        val value = " leading\nvalue=with=equals \n"
        assertEquals(value, appEnvironment(mapOf("KEY" to value), listOf("KEY"))["KEY"])
    }
}
