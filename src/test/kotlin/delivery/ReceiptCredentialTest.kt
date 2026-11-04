package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class ReceiptCredentialTest {
    private val receipt =
        Receipt("abc", MarketingVersion("26.09.01"), BuildNumber("234.1"), "build")

    @Test
    fun `only a successful receipt ref write confirms token recovery`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val tags = GitHubReceiptTags(GitHub("owner/repo", h.commands, emptyMap()))
        var successes = 0
        tags.onCredentialSuccess = { successes++ }
        val name = "deploy/testflight/26.09.01/234.1"
        tags.write(name, "abc", receipt)
        assertEquals(1, successes)
        tags.write(name, "abc", receipt)
        assertEquals(
            1,
            successes,
        ) // merely reading an existing receipt cannot prove write permissions
        val original = h.commands.respond!!
        h.commands.respond = { call ->
            if (call.args.getOrNull(2)?.endsWith("/git/refs") == true)
                throw GitHubApiError("git/refs", 401, "rejected", emptyMap())
            else original(call)
        }
        assertFailsWith<CredentialProblem> {
            tags.write(
                "deploy/testflight/26.09.01/235.1",
                "abc",
                receipt.copy(number = BuildNumber("235.1")),
            )
        }
        assertEquals(1, successes)
    }

    @Test
    fun `accepted upload reports credential problem without losing delivered outcome`() =
        temp { root ->
            for (status in listOf(401, 403)) {
                val h = DeliveryToolHarness(root)
                h.build("26.09.01", "500.1")
                val original = h.commands.respond!!
                h.commands.respond = { call ->
                    when {
                        call.args.getOrNull(2)?.endsWith("/git/refs") == true ->
                            throw GitHubApiError(
                                "git/refs",
                                status,
                                if (status == 401) "Bad credentials"
                                else "refusing to create a workflow without workflows permission",
                                mapOf(
                                    "github-authentication-token-expiration" to
                                        "2026-10-22 12:00:00 UTC"
                                ),
                            )
                        call.args.take(4) == listOf("asc", "builds", "test-notes", "list") ->
                            CommandResult(
                                0,
                                buildJsonObject {
                                    putJsonArray("data") {
                                        addJsonObject {
                                            putJsonObject("attributes") {
                                                put("locale", "en-US")
                                                put("whatsNew", testerNotes(config, h.tip))
                                            }
                                        }
                                    }
                                }
                                    .toString(),
                                "",
                            )
                        else -> original(call)
                    }
                }
                val output =
                    h.run(
                            "uploadBeta",
                            "workflow_dispatch",
                            inputs =
                                mapOf(
                                    "operation" to "retry",
                                    "sha" to h.tip,
                                    "expected_attempt" to "500.1",
                                    "reason" to "Recover",
                                ),
                        )
                        .getOrThrow()
                assertContains(output, "published=true")
                assertContains(output, "::error::")
                assertContains(output, "IOS_RECEIPT_TOKEN")
                val summary =
                    json.decodeFromString<RunSummary>(
                        root.resolve("build/delivery/run-summary.json").readText()
                    )
                assertEquals("completed", summary.runHealth)
                assertContains(summary.deliveries, "Delivered to TestFlight")
                assertContains(summary.deliveries, "receipts weren't written")
                assertContains(
                    summary.actionNeeded.single(),
                    "gh secret set IOS_RECEIPT_TOKEN --env ios-delivery",
                )
                assertContains(h.issues.single().string("body"), "credential/IOS_RECEIPT_TOKEN")
                assertContains(h.issues.single()["labels"].toString(), "needs-owner")
                assertTrue(h.tags.isEmpty())
            }
        }

    @Test
    fun `expiry from earlier token response survives later rejection but job token expiry does not`() {
        for (receiptToken in listOf(true, false)) {
            val commands =
                RecordingCommands().apply {
                    responses.add(
                        CommandResult(
                            0,
                            "HTTP/2 200\nGitHub-Authentication-Token-Expiration: 2026-10-22 12:00:00 UTC\n\n{}",
                            "",
                        )
                    )
                    responses.add(CommandResult(1, "HTTP/2 401\n\n{}", ""))
                }
            val github =
                GitHub("owner/repo", commands, mapOf("IOS_RECEIPT_TOKEN" to "synthetic-token"))
            github.api("probe", receiptToken = receiptToken)
            val problem =
                assertFailsWith<CredentialProblem> {
                    GitHubReceiptTags(github).write("tag", "abc", receipt)
                }
            assertEquals(receiptToken, problem.message!!.contains("expired on 2026-10-22"))
            assertFalse(problem.message!!.contains("synthetic-token"))
        }
    }

    @Test
    fun `receipt rejection names secret expiry and repair with typed failures`() {
        for (expiry in listOf("2026-10-22 12:00:00 UTC", null)) {
            val failure =
                GitHubApiError(
                    "git/refs",
                    401,
                    "Bad credentials",
                    expiry
                        ?.let {
                            mapOf("github-authentication-token-expiration" to it)
                        }
                        .orEmpty(),
                )
            val commands = RecordingCommands().apply { respond = { throw failure } }
            val tags = GitHubReceiptTags(GitHub("owner/repo", commands, emptyMap()))
            val problem = assertFailsWith<CredentialProblem> { tags.write("tag", "abc", receipt) }
            assertEquals("IOS_RECEIPT_TOKEN", problem.secret)
            assertContains(problem.message!!, "IOS_RECEIPT_TOKEN was rejected (expired or revoked)")
            if (expiry != null) assertContains(problem.message!!, "expired on $expiry")
            else assertFalse(problem.message!!.contains("expired on"))
            assertContains(problem.message!!, "gh secret set IOS_RECEIPT_TOKEN --env ios-delivery")
            assertContains(problem.message!!, "Contents and Workflows read/write")
        }
    }

    @Test
    fun `only workflow permission rejection is classified as credential problem`() {
        for (message in
            listOf("Resource not accessible by personal access token", "API rate limit exceeded")) {
            val failure = GitHubApiError("git/refs", 403, message, emptyMap())
            val commands = RecordingCommands().apply { respond = { throw failure } }
            val tags = GitHubReceiptTags(GitHub("owner/repo", commands, emptyMap()))
            assertSame(failure, assertFails { tags.write("tag", "abc", receipt) })
        }
        val failure =
            GitHubApiError(
                "git/refs",
                403,
                "refusing to allow a Personal Access Token to create or update workflow `.github/workflows/ci.yml` without `workflows` permission",
                emptyMap(),
            )
        val commands = RecordingCommands().apply { respond = { throw failure } }
        val tags = GitHubReceiptTags(GitHub("owner/repo", commands, emptyMap()))
        val problem = assertFailsWith<CredentialProblem> { tags.write("tag", "abc", receipt) }
        assertContains(problem.message!!, "Workflows")
        assertContains(problem.message!!, ".github/workflows/")
    }

    @Test
    fun `successful receipt write stays successful`() {
        val commands =
            RecordingCommands().apply {
                responses.add(CommandResult(1, "HTTP/2 404\n\n{}", ""))
                response("""{"sha":"annotated"}""")
                response("{}")
            }
        GitHubReceiptTags(GitHub("owner/repo", commands, emptyMap())).write("tag", "abc", receipt)
        assertEquals(3, commands.calls.size)
    }
}
