package delivery

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test

class LocalArchiveTest {
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val certificate
        get() = digest(syntheticCertificate, "SHA-1").uppercase()

    private val project =
        """{"objects":{
        "app":{"isa":"PBXNativeTarget","name":"iosApp","productType":"com.apple.product-type.application","buildConfigurationList":"configs"},
        "configs":{"buildConfigurations":["release"]},
        "release":{"name":"Release","buildSettings":{"CODE_SIGN_STYLE":"Automatic"}}
    }}"""
    private val info =
        """<plist><dict>
        <key>CFBundleIdentifier</key><string>${config.bundleId}</string>
        <key>CFBundleShortVersionString</key><string>26.11.01</string>
        <key>CFBundleVersion</key><string>1.1</string>
        <key>CFBundleExecutable</key><string>App</string></dict></plist>"""
    private val entitlements =
        """<plist><dict>
        <key>application-identifier</key><string>${config.teamId}.${config.bundleId}</string>
        <key>com.apple.developer.team-identifier</key><string>${config.teamId}</string>
        <key>get-task-allow</key><false/></dict></plist>"""
    private val profile
        get() =
            """<plist><dict>
        <key>UUID</key><string>$uuid</string>
        <key>TeamIdentifier</key><array><string>${config.teamId}</string></array>
        <key>Entitlements</key>${entitlements.substringAfter("<plist>").substringBefore("</plist>")}
        <key>ExpirationDate</key><date>2099-01-01T00:00:00Z</date>
        <key>DeveloperCertificates</key><array><data>$profileCertificateData</data></array>
        </dict></plist>"""

    private inner class Fixture(val directory: Path) {
        val root = directory.resolve("repo").createDirectories()
        val home = directory.resolve("home").createDirectories()
        val output = directory.resolve("result")
        val profileFile =
            directory.resolve("app.mobileprovision").apply { writeText("CMS fixture") }
        val installed =
            home.resolve("Library/MobileDevice/Provisioning Profiles/$uuid.mobileprovision")
        val environment =
            buildEnvironment() - "GITHUB_ACTIONS" +
                mapOf(
                    "HOME" to home.toString(),
                    "ASC_PRIVATE_KEY" to "not-for-builds",
                    "GH_TOKEN" to "not-for-builds",
                )
        val native = RecordingCommands()
        var profileXml = profile
        var failAt: String? = null
        var identities = "1) $certificate \"Apple Distribution: fixture\""
        var ascVersion = config.asc
        var ciSigning = false
        val sha: String
        private val real = ProcessCommands()

        fun git(vararg args: String) = real.run(listOf("git", *args), cwd = root).trim()

        init {
            root.resolve(".github").createDirectories()
            root
                .resolve(DeliveryConfig.DEFAULT_PATH)
                .writeText(json.encodeToString(config.copy(appId = "", internalGroup = "")) + "\n")
            root
                .resolve(config.ios.project)
                .createDirectories()
                .resolve("project.pbxproj")
                .writeText(project)
            git("init", "-b", "trunk")
            git("config", "user.name", "Fixture")
            git("config", "user.email", "fixture@example.test")
            git("add", ".")
            git("commit", "-m", "fixture")
            sha = git("rev-parse", "HEAD")
            native.respond = { call -> respond(call) }
        }

        val args
            get() =
                arrayOf(
                    "archive-local",
                    "--sha",
                    sha,
                    "--profile",
                    profileFile.toString(),
                    "--identity",
                    certificate,
                    "--version",
                    "26.11.01",
                    "--build",
                    "1.1",
                    "--output",
                    output.toString(),
                )

        fun run(arguments: Array<String> = args, env: Map<String, String> = environment) =
            runTask(arguments, root, env, native)

        private fun respond(call: RecordingCommands.Call): CommandResult {
            val a = call.args
            if (a.first() == "git")
                return real.execute(
                    a,
                    call.cwd,
                    call.env,
                    call.input,
                    call.log,
                    call.timeoutSeconds,
                )
            assertFalse("ASC_PRIVATE_KEY" in call.env || "GH_TOKEN" in call.env)
            if (failAt != null && failAt in a) {
                call.log?.writeText("fixture failure")
                return CommandResult(1, "", "fixture failure")
            }
            val stdout =
                when {
                    a == listOf("uname", "-s") -> "Darwin"
                    a == listOf("asc", "--version") -> "$ascVersion (fixture)"
                    a == listOf("xcodebuild", "-version") ->
                        "Xcode ${config.xcode}\nBuild version fixture"
                    a.take(3) == listOf("security", "cms", "-D") -> profileXml
                    a == listOf("security", "find-identity", "-v", "-p", "codesigning") ->
                        identities
                    ciSigning && a.first() == "security" ->
                        when {
                            a.getOrNull(1) == "find-identity" -> identities
                            a.getOrNull(1) == "list-keychains" ->
                                "\"${home.resolve("login.keychain-db")}\""
                            else -> ""
                        }
                    a.take(3) == listOf("asc", "xcode", "archive") -> {
                        assertContentEquals(profileFile.readBytes(), installed.readBytes())
                        assertContains(
                            call.cwd!!
                                .resolve(config.ios.project)
                                .resolve("project.pbxproj")
                                .readText(),
                            certificate,
                        )
                        Path.of(a[a.indexOf("--archive-path") + 1])
                            .resolve("dSYMs")
                            .createDirectories()
                            .resolve("App.dSYM")
                            .writeText("symbols")
                        ""
                    }
                    a.take(3) == listOf("asc", "xcode", "export") -> {
                        ZipOutputStream(Path.of(a[a.indexOf("--ipa-path") + 1]).outputStream())
                            .use { zip ->
                                for ((name, bytes) in
                                    mapOf(
                                        "Info.plist" to info.toByteArray(),
                                        "App" to "release binary".toByteArray(),
                                    )) {
                                    zip.putNextEntry(ZipEntry("Payload/ExampleApp.app/$name"))
                                    zip.write(bytes)
                                    zip.closeEntry()
                                }
                            }
                        ""
                    }
                    a.take(3) == listOf("plutil", "-convert", "json") -> project
                    a.first() == "plutil" && a.last().endsWith("Info.plist") -> info
                    a.first() == "plutil" -> ""
                    a.take(3) == listOf("ditto", "-x", "-k") -> {
                        Path.of(a.last()).resolve("Payload/ExampleApp.app").createDirectories()
                        ""
                    }
                    a.take(3) == listOf("ditto", "-c", "-k") -> {
                        Path.of(a.last()).writeText("symbol archive")
                        ""
                    }
                    a.take(2) == listOf("codesign", "--verify") -> ""
                    a.take(2) == listOf("codesign", "-d") -> entitlements
                    else -> error("Unexpected side effect: $a")
                }
            return CommandResult(0, stdout, "")
        }
    }

    @Test
    fun `local archive uses existing identity and produces truthful non-delivery evidence without app ids`() =
        temp { directory ->
            val f = Fixture(directory)
            f.run()
            val evidence =
                json
                    .parseToJsonElement(f.output.resolve("local-archive.json").readText())
                    .jsonObject
            assertEquals("local-archive", evidence.string("kind"))
            assertEquals(f.sha, evidence.string("sha"))
            assertEquals(
                digest(f.root.resolve(DeliveryConfig.DEFAULT_PATH).readBytes()),
                evidence.string("configurationSha256"),
            )
            assertEquals(JsonPrimitive(false), evidence["deliveryEligible"])
            assertEquals("not-run", evidence.string("verification"))
            assertEquals(certificate, evidence.string("certificate"))
            assertTrue(evidence.getValue("ipa").jsonObject.string("sha256").length == 64)
            assertFails { json.decodeFromString<ArtifactManifest>(evidence.toString()) }
            assertFalse(f.output.resolve("manifest.json").exists())
            assertFalse(f.output.resolve("artifact.json").exists())
            assertFalse(f.installed.exists())
            assertEquals("", f.git("status", "--porcelain"))
            assertEquals(
                1,
                f.git("worktree", "list", "--porcelain").lineSequence().count {
                    it.startsWith("worktree ")
                },
            )
            assertEquals(
                listOf("cms", "find-identity"),
                f.native.calls.filter { it.args.first() == "security" }.map { it.args[1] },
            )
        }

    @Test
    fun `archive export and signature failures restore existing profiles and leave no success evidence`() {
        for (stage in listOf("archive", "export", "codesign")) for (previous in
            listOf(null, "existing profile bytes")) temp { directory ->
            val f = Fixture(directory)
            if (previous != null) {
                f.installed.parent.createDirectories()
                f.installed.writeText(previous)
            }
            f.failAt = stage
            assertFailsWith<CommandError> { f.run() }
            if (previous == null) assertFalse(f.installed.exists())
            else assertEquals(previous, f.installed.readText())
            assertFalse(f.output.resolve("local-archive.json").exists())
            assertEquals("", f.git("status", "--porcelain"))
            assertEquals(
                1,
                f.git("worktree", "list", "--porcelain").lineSequence().count {
                    it.startsWith("worktree ")
                },
            )
            assertFails { f.run() } // A retry cannot overwrite partial bytes from a failed run.
        }
    }

    @Test
    fun `invalid profile or unavailable selected identity stops before signing`() {
        for (failure in
            listOf(
                "team",
                "expiry",
                "development",
                "certificate",
                "uuid",
                "identity",
                "installed-link",
            )) temp { directory ->
            val f = Fixture(directory)
            when (failure) {
                "team" -> f.profileXml = profile.replace(config.teamId, "OTHERTEAM1")
                "expiry" -> f.profileXml = profile.replace("2099-01-01", "2000-01-01")
                "development" -> f.profileXml = profile.replace("<false/>", "<true/>")
                "certificate" -> f.profileXml = profile.replace(profileCertificateData, "AQID")
                "uuid" -> f.profileXml = profile.replace(uuid, "../../escape")
                "identity" -> f.identities = "0 valid identities found"
                "installed-link" -> {
                    f.installed.parent.createDirectories()
                    java.nio.file.Files.createSymbolicLink(f.installed, f.profileFile)
                }
            }
            val error = assertFails { f.run() }
            if (failure in setOf("team", "expiry", "development")) {
                assertContains(error.message!!, "--profile")
                assertFalse(error.message!!.contains("gh secret"))
                assertFalse(error.message!!.contains("IOS_PROFILE_BASE64"))
            }
            assertFalse(f.native.calls.any { it.args.take(2) == listOf("asc", "xcode") })
            assertFalse(f.output.resolve("local-archive.json").exists())
            assertEquals("CMS fixture", f.profileFile.readText())
        }
    }

    @Test
    fun `local exception for blank Apple targets cannot weaken delivery validation`() =
        temp { directory ->
            val f = Fixture(directory)
            val failure = assertFails { DeliveryConfig.load(f.root, emptyMap()) }
            assertContains(failure.message!!, "app_id must not be blank")
            assertContains(failure.message!!, "internal_group must not be blank")
            val text =
                f.root.resolve(DeliveryConfig.DEFAULT_PATH).readText().replace(config.teamId, "")
            f.root.resolve(DeliveryConfig.DEFAULT_PATH).writeText(text)
            f.git("add", ".")
            f.git("commit", "-m", "bad team")
            val args = f.args.toMutableList()
            args[2] = f.git("rev-parse", "HEAD")
            assertContains(
                assertFails { f.run(args.toTypedArray()) }.message!!,
                "team_id must not be blank",
            )
            assertFalse(f.native.calls.any { it.args.first() == "security" })
        }

    @Test
    fun `local archive refuses dirty source wrong revision reused output and incompatible tools`() {
        for (failure in
            listOf(
                "dirty",
                "sha",
                "output",
                "asc",
                "actions",
                "identity",
                "inside",
                "reasonless-option",
            )) temp { directory ->
            val f = Fixture(directory)
            var args = f.args
            var env = f.environment
            when (failure) {
                "dirty" -> f.root.resolve("untracked.swift").writeText("changed")
                "sha" -> args[2] = "a".repeat(40)
                "output" -> f.output.createDirectory()
                "asc" -> f.ascVersion = "0.0.0"
                "actions" -> env = env + ("GITHUB_ACTIONS" to "true")
                "identity" -> args[6] = "ambiguous name"
                "inside" -> args[12] = f.root.resolve("output").toString()
                "reasonless-option" -> args += arrayOf("--upload", "true")
            }
            assertFails { f.run(args, env) }
            assertFalse(
                f.native.calls.any {
                    it.args.first() == "security" || it.args.take(2) == listOf("asc", "xcode")
                }
            )
        }
    }

    @Test
    fun `CI archive still produces its original delivery manifest through shared native path`() =
        temp { directory ->
            val f = Fixture(directory)
            f.ciSigning = true
            f.output.createDirectory()
            val env =
                buildEnvironment(f.environment) +
                    mapOf(
                        "IOS_DISTRIBUTION_P12_BASE64" to
                            java.util.Base64.getEncoder().encodeToString(syntheticP12),
                        "IOS_DISTRIBUTION_P12_PASSWORD" to p12Password,
                        "IOS_PROFILE_BASE64" to
                            java.util.Base64.getEncoder().encodeToString(f.profileFile.readBytes()),
                    )
            val build =
                Build(
                    f.sha,
                    MarketingVersion("26.11.01"),
                    BuildNumber("1.1"),
                    "https://github.com/example/app/actions/runs/123",
                )
            val ipa = Signing(config, f.native, env).archive(f.root, f.output, build)
            assertTrue(ipa.exists())
            val manifest =
                json.decodeFromString<ArtifactManifest>(
                    f.output.resolve("manifest.json").readText()
                )
            assertEquals(build.workflow, manifest.workflow)
            assertEquals(build.sha, manifest.sha)
            assertEquals(build.ipaSha256, manifest.ipa.sha256)
            assertNotNull(manifest.symbols)
            assertFalse(f.output.resolve("local-archive.json").exists())
            assertFalse(f.installed.exists())
            assertEquals(
                project,
                f.root.resolve(config.ios.project).resolve("project.pbxproj").readText(),
            )
        }
}
