package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
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

class BoundaryTest {
    // A class without @Serializable compiles, then throws when encoded. The compiler marks each
    // such call with noCompiledSerializer, so none may reach the delivery tool.
    @Test
    fun `every JSON encoding in the delivery tool has a compiled serializer`() {
        val classes =
            Path.of(DeliveryConfig::class.java.protectionDomain.codeSource.location.toURI())
        val lookups =
            classes
                .walk()
                .filter { it.extension == "class" }
                .filter { "noCompiledSerializer" in it.readBytes().decodeToString() }
                .map { classes.relativize(it).toString() }
                .toList()
        assertEquals(emptyList(), lookups)
    }

    @Test
    fun `runner Xcode selection must still match the pinned version`() {
        val environment = mapOf("DEVELOPER_DIR" to "/Applications/Xcode.app/Contents/Developer")
        listOf(config.xcode, "0.0").forEach { version ->
            val commands = RecordingCommands()
            commands.response("arm64")
            commands.response("Xcode $version\nBuild version fixture")
            val verification = Verification(config, commands, environment)
            if (version == config.xcode) verification.checkEnvironment()
            else {
                val failure =
                    assertFailsWith<IllegalArgumentException> { verification.checkEnvironment() }
                assertContains(failure.message.orEmpty(), "Expected Xcode ${config.xcode}")
                assertContains(failure.message.orEmpty(), "found Xcode $version")
            }
            assertEquals(environment["DEVELOPER_DIR"], commands.calls.last().env["DEVELOPER_DIR"])
        }
    }

    @Test
    fun `verification cannot accept stale reports or inherit deployment secrets`() =
        temp { source ->
            config.verification.testReports.forEach {
                val report = source.resolve("$it/TEST-stale.xml")
                report.parent.createDirectories()
                report.writeText("<testsuite tests=\"99\"/>")
            }
            val commands = RecordingCommands()
            commands.respond = { call ->
                assertNull(call.env["ASC_PRIVATE_KEY"])
                assertNull(call.env["GH_TOKEN"])
                assertNull(call.env["UNDECLARED"])
                CommandResult(
                    0,
                    when (call.args.first()) {
                        "uname" -> "arm64"
                        "xcodebuild" -> "Xcode ${config.xcode}\nBuild version fixture"
                        else -> ""
                    },
                    "",
                )
            }
            val verifier =
                Verification(
                    config.copy(buildEnvironment = listOf("ANALYTICS_KEY")),
                    commands,
                    mapOf(
                        "ASC_PRIVATE_KEY" to "secret",
                        "GH_TOKEN" to "secret",
                        "ANALYTICS_KEY" to "public",
                        "UNDECLARED" to "hidden",
                    ),
                )
            assertFails { verifier.verify(source, source.resolve("evidence")) }
            val run = commands.calls.filter { it.cwd == source }
            assertEquals(config.verification.commands, run.map { it.args })
            // Only declared app values reach the app's own commands.
            assertTrue(run.all { it.env["ANALYTICS_KEY"] == "public" })
            config.verification.testReports.forEach {
                assertFalse(source.resolve("$it/TEST-stale.xml").exists())
            }
        }

    @Test
    fun `xcresult summaries pass the same test gate as JUnit reports`() {
        val bundle = Path.of("Tests.xcresult")
        fun summary(total: Int, failed: Int, skipped: Int = 0) =
            """{"title":"t","totalTestCount":$total,"passedTests":${total - failed},""" +
                """"failedTests":$failed,"skippedTests":$skipped,"expectedFailures":0}"""
        assertEquals(TestTotals(12, 0, 0, 0), XcresultReport(bundle).totals(summary(12, 0)))
        assertEquals(
            FailureKind.VALIDATION,
            assertFailsWith<VerificationFailure> { XcresultReport(bundle).totals(summary(12, 1)) }
                .kind,
        )
        assertEquals(
            FailureKind.VALIDATION,
            assertFailsWith<VerificationFailure> {
                    XcresultReport(bundle).totals(summary(12, 0, 2))
                }
                .kind,
        )
        assertEquals(
            FailureKind.INFRASTRUCTURE,
            assertFailsWith<VerificationFailure> { XcresultReport(bundle).totals(summary(0, 0)) }
                .kind,
        )
    }

    @Test
    fun `read only local commands cannot authorize writers`() {
        assertFails { requireActionsWriter(emptyMap(), "trunk") }
        assertFails {
            requireActionsWriter(
                mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REF" to "refs/heads/feature"),
                "trunk",
            )
        }
        requireActionsWriter(
            mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REF" to "refs/heads/trunk"),
            "trunk",
        )
        requireActionsWriter(
            mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REF" to "refs/heads/main"),
            "main",
        )
    }

    @Test
    fun `build subprocesses never inherit delivery or future credentials`() {
        assertEquals(
            mapOf("PATH" to "/bin", "JAVA_HOME" to "/java"),
            buildEnvironment(
                mapOf(
                    "PATH" to "/bin",
                    "JAVA_HOME" to "/java",
                    "GH_TOKEN" to "secret",
                    "IOS_RECEIPT_TOKEN" to "secret",
                    "ASC_PRIVATE_KEY" to "secret",
                    "ACTIONS_RUNTIME_TOKEN" to "secret",
                    "FUTURE_SECRET" to "secret",
                )
            ),
        )
    }

    @Test
    fun `API errors keep status and explanation without tokens or raw stderr`() {
        val commands = RecordingCommands()
        commands.responses.add(
            CommandResult(
                1,
                """{"message":"Refusing workflow update using receipt-secret"}""",
                "HTTP 403 raw receipt-secret",
            )
        )
        val error = assertFails {
            GitHub("owner/repo", commands, mapOf("IOS_RECEIPT_TOKEN" to "receipt-secret"))
                .api("git/refs", buildJsonObject {}, receiptToken = true)
        }
        assertContains(error.message!!, "HTTP 403: Refusing workflow update")
        assertFalse("receipt-secret" in error.message!!)
        assertFalse("raw" in error.message!!)
        commands.respond = { CommandResult(1, "<html>unavailable</html>", "HTTP 503") }
        assertContains(
            assertFails { GitHub("owner/repo", commands, emptyMap(), {}).api("git/refs") }
                .message!!,
            "HTTP 503",
        )
    }

    @Test
    fun `distribution milestone needs group beta state and note readback`() = temp { output ->
        val commands = RecordingCommands()
        commands.respond = { call ->
            val args = call.args
            val response =
                when {
                    "test-notes" in args && "list" in args -> """{"data":[]}"""
                    "groups" in args -> """{"groups":[{"id":"${config.internalGroup}"}]}"""
                    "build-beta-detail" in args ->
                        """{"data":{"attributes":{"internalBuildState":"IN_BETA_TESTING"}}}"""
                    "test-notes" in args && "view" in args -> buildJsonObject {
                            putJsonObject("data") {
                                putJsonObject("attributes") { put("whatsNew", "wrong notes") }
                            }
                        }
                            .toString()
                    else -> "{}"
                }
            CommandResult(0, response, "")
        }
        assertFails {
            Asc(
                    config,
                    commands,
                    mapOf("ASC_PRIVATE_KEY" to "key", "GH_TOKEN" to "secret"),
                    pause = {},
                )
                .distribute("build", testerNotes(config, "source-sha"), output)
        }
        commands.calls.forEach {
            assertNull(it.env["GH_TOKEN"])
            assertEquals("true", it.env["ASC_STRICT_AUTH"])
        }
    }

    @Test
    fun `missing failed skipped or empty reports fail and suites aggregate`() = temp { root ->
        assertFails { JUnitReport(root).totals() }
        listOf(
                "tests=\"0\"",
                "tests=\"2\" skipped=\"1\"",
                "tests=\"2\" failures=\"1\"",
                "tests=\"2\" errors=\"1\"",
            )
            .forEach {
                root.resolve("TEST-a.xml").writeText("<testsuite $it/>")
                assertFails { JUnitReport(root).totals() }
            }
        listOf("a", "b").forEach {
            root.resolve("TEST-$it.xml").writeText("<testsuite tests=\"3\"/>")
        }
        assertEquals(TestTotals(6, 0, 0, 0), JUnitReport(root).totals())
    }
}
