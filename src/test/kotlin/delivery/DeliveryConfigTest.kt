package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class DeliveryConfigTest {
    private val repository = Path.of(System.getProperty("delivery.root"))

    private fun example(name: String) =
        repository.resolve("examples/$name/.github/delivery.json").readText()

    private fun changed(change: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        json.parseToJsonElement(example("kmp")).jsonObject.forEach { (k, v) -> put(k, v) }
        change()
    }
        .toString()

    @Test
    fun `apps can declare their own release binary strings`() {
        DeliveryConfig.parse(
            changed {
                putJsonObject("binary_checks") {
                    putJsonArray("required_strings") { add("fixture-production-key") }
                    putJsonArray("forbidden_strings") { add("fixture-test-key") }
                }
            }
        )
    }

    @Test
    fun `binary checks default to none and reject misspelt blank or nonstring rules`() {
        assertEquals(BinaryChecks(), DeliveryConfig.parse(example("xcode")).binaryChecks)
        for (rules in
            listOf(
                """{"required_strings":[""]}""",
                """{"forbidden_strings":[7]}""",
                """{"required_string":["marker"]}""",
            )) {
            val failure = assertFails {
                DeliveryConfig.parse(
                    changed { put("binary_checks", json.parseToJsonElement(rules)) }
                )
            }
            assertContains(failure.message!!, "binary_checks.")
        }
    }

    @Test
    fun `stale branch setting explains the single source`() {
        val failure = assertFails { DeliveryConfig.parse(changed { put("branch", "main") }) }
        assertContains(
            failure.message.orEmpty(),
            "delete it and set branch in the caller's with: inputs",
        )
    }

    @Test
    fun `release text must be chosen explicitly even when the whole section is missing`() {
        val original = json.parseToJsonElement(example("kmp")).jsonObject
        for (broken in
            listOf(
                JsonObject(original - "release_notes"),
                JsonObject(
                    original +
                        ("release_notes" to buildJsonObject { put("directory", "release-notes.d") })
                ),
            )) {
            val failure = assertFails { DeliveryConfig.parse(broken.toString()) }
            assertContains(
                failure.message.orEmpty(),
                ".github/delivery.json: release_notes.fallback is missing",
            )
        }
    }

    @Test
    fun `invalid configuration stops before executing any command`() = temp { root ->
        root
            .resolve(".github")
            .createDirectories()
            .resolve("delivery.json")
            .writeText("""{"timezone":"Mars/Olympus","typo":true}""")
        val commands = RecordingCommands()
        val failure = assertFails { runTask(arrayOf("startRun"), root, emptyMap(), commands) }
        assertContains(failure.message.orEmpty(), "app_id is missing")
        assertContains(failure.message.orEmpty(), "timezone is invalid")
        assertContains(failure.message.orEmpty(), "typo is unknown")
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `configuration reports missing invalid and unknown keys together`() {
        val original = json.parseToJsonElement(example("kmp")).jsonObject
        val broken =
            JsonObject(
                original - "app_id" +
                    mapOf(
                        "timezone" to JsonPrimitive("Mars/Olympus"),
                        "typo" to JsonPrimitive("bad"),
                    )
            )
        val failure = assertFails { DeliveryConfig.parse(broken.toString()) }
        for (problem in listOf("app_id is missing", "timezone is invalid", "typo is unknown")) {
            assertContains(failure.message.orEmpty(), ".github/delivery.json: $problem")
        }
    }

    @Test
    fun `examples are valid configurations`() {
        val kmp = DeliveryConfig.parse(example("kmp"))
        assertEquals(listOf("platforms;android-37.0", "build-tools;36.0.0"), kmp.androidPackages)
        assertEquals("mobile-delivery", kmp.incidentMarker)
        val xcode = DeliveryConfig.parse(example("xcode"))
        assertEquals("App.xcworkspace", xcode.ios.workspace)
        assertEquals(emptyList(), xcode.androidPackages)
        assertEquals("Maintenance improvements.", xcode.releaseNotes.fallback)
        assertEquals(Toolchain.bundled.asc, xcode.asc)
    }

    @Test
    fun `configuration is loaded from the app repository`() = temp { root ->
        assertFails { DeliveryConfig.load(root, emptyMap()) }
        root
            .resolve(".github")
            .createDirectories()
            .resolve("delivery.json")
            .writeText(example("xcode"))
        assertEquals("App", DeliveryConfig.load(root, emptyMap()).ios.scheme)
    }

    @Test
    fun `misspelt or unsafe settings are refused`() {
        DeliveryConfig.parse(changed {})
        listOf<JsonObjectBuilder.() -> Unit>(
                { put("tester_note", "typo") },
                { put("incident_marker", "Example App") },
                { put("timezone", "Mars/Olympus") },
                {
                    putJsonObject("verification") {
                        putJsonArray("commands") { addJsonArray { add("true") } }
                        putJsonArray("test_reports") {}
                    }
                },
                {
                    putJsonObject("verification") {
                        putJsonArray("commands") {}
                        putJsonArray("test_reports") { add("build/reports") }
                    }
                },
                {
                    putJsonObject("verification") {
                        putJsonArray("commands") { addJsonArray { add("true") } }
                        putJsonArray("test_reports") { add("../outside") }
                    }
                },
                { putJsonObject("release_notes") { put("directory", "/etc") } },
            )
            .forEach { change -> assertFails { DeliveryConfig.parse(changed(change)) } }
    }
}
