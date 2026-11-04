package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class AscCredentialTest {
    private fun fixture(name: String): CommandResult {
        val row =
            json
                .parseToJsonElement(javaClass.getResource("/asc/$name.json")!!.readText())
                .jsonObject
        return CommandResult(
            row.getValue("exit").jsonPrimitive.int,
            row.string("stdout"),
            row.string("stderr"),
        )
    }

    @Test
    fun `only successful authenticated ASC use resolves its credential incident once`() {
        var accepted = false
        var reads = 0
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    if (call.args.first() == "asc") {
                        if (accepted) fixture("200") else fixture("401")
                    } else {
                        reads++
                        CommandResult(0, "[]", "")
                    }
                }
            }
        val asc =
            Asc(config, commands, mapOf("GH_TOKEN" to "job", "GITHUB_REPOSITORY" to "owner/repo"))
        assertFailsWith<CredentialProblem> { asc.uploads() }
        assertEquals(0, reads)
        accepted = true
        asc.uploads()
        asc.uploads()
        assertEquals(1, reads)
    }

    @Test
    fun `rejected keys name secrets and repair commands without provider text`() {
        for (name in listOf("401", "403", "malformed")) {
            val commands =
                RecordingCommands().apply {
                    responses.add(
                        fixture(name).let {
                            it.copy(
                                stderr =
                                    it.stderr + "\nsensitive-key-id sensitive-issuer sensitive-pem"
                            )
                        }
                    )
                }
            val problem =
                assertFailsWith<CredentialProblem> { Asc(config, commands, emptyMap()).uploads() }
            assertContains(
                problem.message!!,
                if (name == "malformed") "ASC_PRIVATE_KEY is not a PKCS#8 .p8 key"
                else
                    "App Store Connect rejected the API key in ASC_KEY_ID / ASC_ISSUER_ID / ASC_PRIVATE_KEY (revoked, wrong issuer, or wrong key)",
            )
            assertContains(problem.message!!, "App Store Connect → Users and Access → Integrations")
            for (secret in listOf("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_PRIVATE_KEY")) assertContains(
                problem.message!!,
                "gh secret set $secret --env ios-delivery",
            )
            assertFalse(problem.toString().contains("sensitive"))
            assertNull(problem.cause)
        }
    }

    @Test
    fun `server failures stay command failures and success returns data`() {
        val commands = RecordingCommands().apply { responses.add(fixture("500")) }
        val error = assertFailsWith<AscError> { Asc(config, commands, emptyMap()).uploads() }
        assertEquals(AscErrorKind.INFRASTRUCTURE, error.kind)
        assertEquals(500, error.status)
        commands.responses.add(fixture("200"))
        assertTrue(Asc(config, commands, emptyMap()).uploads().isEmpty())
    }

    @Test
    fun `logged upload rejection uses shared credential path too`() = temp { root ->
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    val result = fixture("403")
                    call.log!!.writeText(result.stderr + "sensitive-key-material")
                    result.copy(stderr = "")
                }
            }
        assertFailsWith<CredentialProblem> {
            Asc(config, commands, emptyMap()).upload(Path.of("fixture.ipa"), root)
        }
        assertFalse(root.resolve("upload.log").readText().contains("sensitive"))
    }

    @Test
    fun `typed boundary distinguishes numeric statuses and timeout without leaking causes`() {
        for (status in listOf(401, 403, 500, 503)) {
            val commands =
                RecordingCommands().apply {
                    responses.add(CommandResult(1, "", "HTTP $status sensitive"))
                }
            val error =
                assertFailsWith<AscError> {
                    executeAsc(commands, listOf("builds", "list"), emptyMap(), null)
                }
            assertEquals(status, error.status)
            assertEquals(
                if (status >= 500) AscErrorKind.INFRASTRUCTURE else AscErrorKind.REJECTED_KEY,
                error.kind,
            )
            assertEquals(status < 500, error.credentialProblem() != null)
            assertFalse(error.toString().contains("sensitive"))
        }
        val commands =
            RecordingCommands().apply {
                respond = { throw CommandError("asc timed out sensitive") }
            }
        val error =
            assertFailsWith<AscError> { executeAsc(commands, emptyList(), emptyMap(), null) }
        assertEquals(AscErrorKind.INFRASTRUCTURE, error.kind)
        assertNull(error.credentialProblem())
        assertNull(error.cause)
        assertFalse(error.toString().contains("sensitive"))
        assertEquals(
            AscErrorKind.INFRASTRUCTURE,
            classifyAscError("HTTP 500 invalid private key", 1).kind,
        )
    }

    @Test
    fun `rejected upload is not hidden by uncertain upload recovery`() = temp { root ->
        val world = World(root)
        val problem = classifyAscError(fixture("403").stderr, 3).credentialProblem()!!
        world.apple.interrupt = { if (it == "before-upload") throw problem }
        assertSame(problem, assertFailsWith<CredentialProblem> { world.deliver("source-sha") })
    }

    @Test
    fun `late sweep rejection reaches owner path instead of a generic repair error`() =
        temp { root ->
            val h = DeliveryToolHarness(root)
            val original = h.commands.respond!!
            h.commands.respond = { call ->
                if (call.args.take(3) == listOf("asc", "versions", "list")) fixture("403")
                else original(call)
            }
            val failure = h.run("sweep", "schedule").exceptionOrNull()!!
            assertTrue(failure.cause is CredentialProblem)
            assertContains(h.issues.single().string("body"), "credential/ASC_PRIVATE_KEY")
            val report =
                json.decodeFromString<RunSummary>(
                    root.resolve("build/delivery/run-summary.json").readText()
                )
            assertEquals("failed", report.runHealth)
            assertEquals("Delivery credential rejected", report.deliveries)
            assertTrue(report.repairErrors.isEmpty())
        }

    @Test
    fun `workflow reports owner incident for rejected keys`() = temp { root ->
        val h = DeliveryToolHarness(root)
        val original = h.commands.respond!!
        h.commands.respond = { call ->
            if (call.args.first() == "asc") fixture("401") else original(call)
        }
        val failure = h.run("startRun", "push").exceptionOrNull()!!
        assertTrue(failure.cause is CredentialProblem)
        assertContains((failure as DeliveryToolHarness.ToolFailure).output, "::error::")
        assertContains(h.issues.single().string("body"), "credential/ASC_PRIVATE_KEY")
        assertContains(h.issues.single()["labels"].toString(), "needs-owner")
    }
}
