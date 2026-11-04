package delivery

import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class CredentialRecoveryTest {
    @Test
    fun `recovery closes only the proven credential and preserves unrelated incidents and notes`() {
        val secrets =
            listOf(
                "IOS_DISTRIBUTION_P12_BASE64",
                "IOS_DISTRIBUTION_P12_PASSWORD",
                "IOS_PROFILE_BASE64",
                "ASC_PRIVATE_KEY",
                "IOS_RECEIPT_TOKEN",
            )
        val rows = secrets.mapIndexed { index, secret ->
            buildJsonObject {
                put("number", index + 1)
                put(
                    "body",
                    "Human notes\n<!-- ${config.incidentMarker}:credential/$secret -->\n<!-- ${config.incidentMarker}-status:start -->\nState: OPEN\n<!-- ${config.incidentMarker}-status:end -->",
                )
            }
        }
        val patches = mutableListOf<Pair<String, JsonObject>>()
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    val path = call.args[2].substringAfter("repos/owner/repo/")
                    val output =
                        if (path.startsWith("issues?")) JsonArray(rows).toString()
                        else {
                            patches += path to json.parseToJsonElement(call.input!!).jsonObject
                            "{}"
                        }
                    CommandResult(0, output, "")
                }
            }
        resolveCredentialIncidents(
            setOf("ASC_PRIVATE_KEY"),
            config,
            commands,
            mapOf(
                "GH_TOKEN" to "job-token",
                "GITHUB_REPOSITORY" to "owner/repo",
                "GITHUB_SHA" to "abc",
            ),
        )
        assertEquals(listOf("issues/4"), patches.map { it.first })
        val patch = patches.single().second
        assertEquals("closed", patch.string("state"))
        assertEquals("completed", patch.string("state_reason"))
        assertContains(patch.string("body"), "Human notes")
        assertContains(
            patch.string("body"),
            "successful credential use verified ASC_PRIVATE_KEY at abc",
        )
    }

    @Test
    fun `recovery API failure warns without failing successful credential use`() {
        val commands = RecordingCommands().apply { respond = { error("network unavailable") } }
        resolveCredentialIncidents(
            setOf("ASC_PRIVATE_KEY"),
            config,
            commands,
            mapOf("GH_TOKEN" to "job", "GITHUB_REPOSITORY" to "owner/repo"),
        )
    }
}
