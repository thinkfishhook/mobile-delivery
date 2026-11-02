package delivery

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test

internal const val p12Password = "synthetic-p12-password"
internal val syntheticP12: ByteArray by lazy { generateP12() }

internal fun generateP12(startDate: String? = null): ByteArray {
    val directory = createTempDirectory("synthetic-p12-")
    try {
        val file = directory.resolve("test.p12")
        ProcessCommands()
            .run(
                listOfNotNull(
                    Path.of(System.getProperty("java.home"), "bin/keytool").toString(),
                    "-J-Duser.timezone=UTC",
                    "-genkeypair",
                    "-alias",
                    "fixture",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-dname",
                    "CN=Synthetic Test",
                    "-validity",
                    "1",
                    *if (startDate == null) emptyArray() else arrayOf("-startdate", startDate),
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    file.toString(),
                    "-storepass",
                    p12Password,
                )
            )
        return file.readBytes()
    } finally {
        directory.toFile().deleteRecursively()
    }
}

class CredentialProblemTest {
    @Test
    fun `bad P12 exports and passwords name the secret before any command`() = temp { root ->
        val encoded = Base64.getEncoder().encodeToString(syntheticP12)
        for ((value, password, expected) in
            listOf(
                Triple(
                    "%%%",
                    p12Password,
                    "IOS_DISTRIBUTION_P12_BASE64 is not a base64 PKCS#12 export",
                ),
                Triple(
                    "AQID",
                    p12Password,
                    "IOS_DISTRIBUTION_P12_BASE64 is not a base64 PKCS#12 export",
                ),
                Triple(
                    encoded + "\n",
                    p12Password,
                    "IOS_DISTRIBUTION_P12_BASE64 is not a base64 PKCS#12 export",
                ),
                Triple(
                    encoded,
                    "wrong-password",
                    "IOS_DISTRIBUTION_P12_PASSWORD doesn't open IOS_DISTRIBUTION_P12_BASE64",
                ),
            )) {
            val commands = RecordingCommands()
            val failure = assertFails {
                SigningInputs.read(
                    config,
                    commands,
                    mapOf(
                        "IOS_DISTRIBUTION_P12_BASE64" to value,
                        "IOS_DISTRIBUTION_P12_PASSWORD" to password,
                        "IOS_PROFILE_BASE64" to "AQID",
                    ),
                    root,
                )
            }
            val fix =
                if (value == encoded) {
                    "gh secret set IOS_DISTRIBUTION_P12_PASSWORD --env ios-delivery"
                } else {
                    "base64 < distribution.p12 | tr -d '\\n' | gh secret set IOS_DISTRIBUTION_P12_BASE64 --env ios-delivery"
                }
            assertEquals("$expected\n$fix", failure.message)
            assertFalse(failure.toString().contains(password))
            assertFalse(failure.toString().contains(encoded))
            assertTrue(commands.calls.isEmpty())
        }
    }

    @Test
    fun `verify signing reports safe credential failures and updates one owner incident`() {
        val encoded = Base64.getEncoder().encodeToString(syntheticP12)
        for ((value, password) in
            listOf("%%%" to p12Password, "AQID" to p12Password, encoded to "wrong-password")) {
            checkReportedFailure(value, password)
        }
    }

    @Test
    fun `certificate validity fails before commands and verify signing reports the owner fix`() {
        for ((start, state, date) in
            listOf(
                Triple("2000/01/01 00:00:00", "expired on", "2000-01-02T00:00:00Z"),
                Triple("2099/01/01 00:00:00", "is not valid until", "2099-01-01T00:00:00Z"),
            )) {
            val encoded = Base64.getEncoder().encodeToString(generateP12(start))
            val expected =
                "IOS_DISTRIBUTION_P12_BASE64: the distribution certificate \"Synthetic Test\" $state $date.\n" +
                    "Renew it in Apple Developer → Certificates, then run:\n" +
                    "base64 < distribution.p12 | tr -d '\\n' | gh secret set IOS_DISTRIBUTION_P12_BASE64 --env ios-delivery"
            temp { root ->
                val commands = RecordingCommands()
                val failure =
                    assertFailsWith<CredentialProblem> {
                        SigningInputs.read(
                            config,
                            commands,
                            mapOf(
                                "IOS_DISTRIBUTION_P12_BASE64" to encoded,
                                "IOS_DISTRIBUTION_P12_PASSWORD" to p12Password,
                                "IOS_PROFILE_BASE64" to "AQID",
                            ),
                            root,
                        )
                    }
                assertEquals(expected, failure.message)
                assertTrue(commands.calls.isEmpty())
                assertFalse(failure.toString().contains(encoded))
                assertFalse(failure.toString().contains(p12Password))
            }
            checkReportedFailure(encoded, p12Password, expected)
        }
    }

    private fun checkReportedFailure(
        value: String,
        password: String,
        expected: String =
            if (password == "wrong-password") {
                "IOS_DISTRIBUTION_P12_PASSWORD doesn't open IOS_DISTRIBUTION_P12_BASE64\n" +
                    "gh secret set IOS_DISTRIBUTION_P12_PASSWORD --env ios-delivery"
            } else {
                "IOS_DISTRIBUTION_P12_BASE64 is not a base64 PKCS#12 export\n" +
                    "base64 < distribution.p12 | tr -d '\\n' | gh secret set IOS_DISTRIBUTION_P12_BASE64 --env ios-delivery"
            },
    ) = temp { root ->
        val secret =
            if (password == "wrong-password") "IOS_DISTRIBUTION_P12_PASSWORD"
            else "IOS_DISTRIBUTION_P12_BASE64"
        root
            .resolve(".github")
            .createDirectories()
            .resolve("delivery.json")
            .writeText(json.encodeToString(config))
        val summary = root.resolve("summary.md").apply { writeText("") }
        var issue: JsonObject? = null
        var creates = 0
        var updates = 0
        val commands =
            RecordingCommands().apply {
                respond = { call ->
                    assertEquals("gh", call.args.first(), "No security commands may run")
                    val path = call.args[2].substringAfter("repos/owner/repo/")
                    val response =
                        when {
                            path.startsWith("labels/") -> "{}"
                            path.startsWith("issues?") -> JsonArray(listOfNotNull(issue)).toString()
                            path == "issues" -> {
                                creates++
                                issue =
                                    JsonObject(
                                        json.parseToJsonElement(call.input!!).jsonObject +
                                            ("number" to JsonPrimitive(1))
                                    )
                                issue.toString()
                            }
                            path == "issues/1" -> {
                                updates++
                                issue =
                                    JsonObject(
                                        issue!! + json.parseToJsonElement(call.input!!).jsonObject
                                    )
                                issue.toString()
                            }
                            else -> error("Unexpected API $path")
                        }
                    CommandResult(0, response, "")
                }
            }
        val encoded = Base64.getEncoder().encodeToString(syntheticP12)
        val env =
            mapOf(
                "HOME" to root.toString(),
                "GITHUB_REPOSITORY" to "owner/repo",
                "GITHUB_STEP_SUMMARY" to summary.toString(),
                "DELIVERY_INPUTS" to """{"branch":"delivery","operation":"verify-signing"}""",
                "IOS_DISTRIBUTION_P12_BASE64" to value,
                "IOS_DISTRIBUTION_P12_PASSWORD" to password,
                "IOS_PROFILE_BASE64" to "AQID",
            )
        val output = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(output))
            repeat(2) {
                val failure =
                    assertFailsWith<CredentialProblem> {
                        runTask(arrayOf("verifySigning"), root, env, commands)
                    }
                assertEquals(
                    expected,
                    failure.message,
                )
                // Force a changed status block so the second observation exercises PATCH.
                if (it == 0)
                    issue =
                        JsonObject(
                            issue!! +
                                ("body" to
                                    JsonPrimitive(
                                        issue!!.string("body").replace("State: OPEN", "State: OLD")
                                    ))
                        )
            }
        } finally {
            System.setOut(original)
        }
        assertEquals(1, creates)
        assertEquals(1, updates)
        assertContains(issue!!.string("body"), "credential/$secret")
        assertContains(issue!!["labels"].toString(), "needs-owner")
        val report =
            json.decodeFromString<RunSummary>(
                root.resolve("build/delivery/run-summary.json").readText()
            )
        assertEquals("failed", report.runHealth)
        assertTrue(report.repairErrors.isEmpty())
        assertEquals(expected, report.actionNeeded.single())
        assertContains(output.toString(), "::error")
        assertContains(summary.readText(), secret)
        val allOutput =
            output.toString() + summary.readText() + issue + commands.calls.joinToString()
        for (sensitive in
            listOf("wrong-password", p12Password, encoded, value, "BEGIN PRIVATE KEY")) {
            assertFalse(sensitive in allOutput, "Credential material escaped")
        }
    }
}
