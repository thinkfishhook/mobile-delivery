package delivery

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test

private val sampleReceipt =
    Receipt(
        "source-sha",
        MarketingVersion("26.09.01"),
        BuildNumber("234.1"),
        "apple-build",
        "build-url",
        "digest",
        27,
        "2026-09-16T07:00-04:00",
        "26.6",
        "5.1.0",
    )

class ReceiptTagsTest {
    @Test
    fun `lost receipt ref response is read back without creating another ref`() {
        val commands = RecordingCommands()
        val receipt = sampleReceipt
        commands.responses.add(CommandResult(1, "", "HTTP 404"))
        commands.response("""{"sha":"tag-object"}""")
        commands.responses.add(CommandResult(1, "", "HTTP 502"))
        commands.response("""{"object":{"type":"tag","sha":"tag-object"}}""")
        commands.response(
            buildJsonObject {
                putJsonObject("object") { put("sha", receipt.sha) }
                put("message", json.encodeToString(receipt))
            }
                .toString()
        )
        GitHubReceiptTags(GitHub("owner/repo", commands, emptyMap()))
            .write("deploy/testflight/26.09.01/234.1", receipt.sha, receipt)
        assertEquals(
            1,
            commands.calls.count {
                it.args.contains("repos/owner/repo/git/refs") && it.input != null
            },
        )
    }

    @Test
    fun `existing Python and Kotlin receipt tags keep the identity collisions compare`() {
        val tags = json.parseToJsonElement(resource("receipt-tags-2026-09-25.json")).jsonArray
        assertEquals(2, tags.size)
        tags
            .map { it.jsonObject }
            .forEach { tag ->
                val receipt = json.decodeFromString<Receipt>(tag.string("message"))
                assertEquals(tag.string("target"), receipt.sha)
                assertEquals(
                    tag.string("tag"),
                    "deploy/testflight/${receipt.version}/${receipt.number}",
                )
                assertNotNull(receipt.ascBuildId)
            }
    }

    @Test
    fun `receipt calls use the receipt token and other calls keep the job token`() {
        val commands = RecordingCommands()
        commands.responses.add(CommandResult(1, "", "HTTP 404"))
        commands.response("""{"sha":"tag-id"}""")
        commands.response("{}")
        val github =
            GitHub(
                "owner/repo",
                commands,
                mapOf(
                    "GH_TOKEN" to "job-token",
                    "IOS_RECEIPT_TOKEN" to "receipt-token",
                    "ASC_PRIVATE_KEY" to "private",
                ),
            )
        val build = sampleReceipt
        GitHubReceiptTags(github)
            .write(
                "deploy/testflight/${build.version}/${build.number}",
                build.sha,
                build,
            )
        commands.calls.forEach {
            assertEquals("receipt-token", it.env["GH_TOKEN"])
            assertFalse("IOS_RECEIPT_TOKEN" in it.env)
            assertFalse("ASC_PRIVATE_KEY" in it.env)
        }
        github.api("issues", buildJsonObject {})
        assertEquals("job-token", commands.calls.last().env["GH_TOKEN"])
    }

    @Test
    fun `receipt collisions reject lightweight tags wrong commits and changed manifests`() {
        val build = sampleReceipt
        listOf("lightweight", "wrong-commit", "wrong-manifest").forEach { scenario ->
            val commands = RecordingCommands()
            commands.response(
                buildJsonObject {
                    putJsonObject("object") {
                        put("type", if (scenario == "lightweight") "commit" else "tag")
                        put("sha", "tag-id")
                    }
                }
                    .toString()
            )
            commands.response(
                buildJsonObject {
                    putJsonObject("object") {
                        put("sha", if (scenario == "wrong-commit") "other" else build.sha)
                    }
                    put(
                        "message",
                        if (scenario == "wrong-manifest") "{}" else json.encodeToString(build),
                    )
                }
                    .toString()
            )
            val github = GitHub("owner/repo", commands, emptyMap())
            assertFails { GitHubReceiptTags(github).write("receipt", build.sha, build) }
            assertTrue(commands.calls.all { "--method" !in it.args })
        }
    }

    @Test
    fun `a repaired receipt with fewer fields matches the original`() {
        val commands = RecordingCommands()
        commands.response("""{"object":{"type":"tag","sha":"tag-id"}}""")
        commands.response(
            buildJsonObject {
                putJsonObject("object") { put("sha", sampleReceipt.sha) }
                put("message", json.encodeToString(sampleReceipt))
            }
                .toString()
        )
        val repaired =
            Receipt(sampleReceipt.sha, sampleReceipt.version, sampleReceipt.number, "apple-build")
        GitHubReceiptTags(GitHub("owner/repo", commands, emptyMap()))
            .write("receipt", repaired.sha, repaired)
        assertEquals(2, commands.calls.size)
    }

    @Test
    fun `legacy receipt is idempotent despite property order and whitespace`() {
        val build = sampleReceipt
        val commands = RecordingCommands()
        commands.response("""{"object":{"type":"tag","sha":"tag-id"}}""")
        commands.response(
            buildJsonObject {
                putJsonObject("object") { put("sha", build.sha) }
                put("message", json.encodeToString(build))
            }
                .toString()
        )
        GitHubReceiptTags(GitHub("owner/repo", commands, emptyMap()))
            .write("receipt", build.sha, build)
        assertEquals(2, commands.calls.size)
    }
}
