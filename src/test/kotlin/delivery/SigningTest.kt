package delivery

import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class SigningTest {
    @Test
    fun `release binaries have no caller-specific SDK requirements by default`() {
        validateReleaseBinary("an app without embedded SDK keys".toByteArray())
    }

    @Test
    fun `release binary gate detects keys at any byte alignment`() {
        val checks = BinaryChecks(listOf("fixture-production-key"), listOf("fixture-test-key"))
        listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).forEach { encoding ->
            val production = "fixture-production-key".toByteArray(encoding)
            val testKey = "fixture-test-key".toByteArray(encoding)
            validateReleaseBinary(byteArrayOf(1) + production + byteArrayOf(2), checks)
            assertFails { validateReleaseBinary(production + byteArrayOf(1) + testKey, checks) }
        }
        assertFails { validateReleaseBinary("no SDK key".toByteArray(), checks) }
    }

    @Test
    fun `every configured required string must be present`() {
        val checks = BinaryChecks(requiredStrings = listOf("first-marker", "second-marker"))
        assertFails { validateReleaseBinary("first-marker".toByteArray(), checks) }
        validateReleaseBinary("first-marker second-marker".toByteArray(), checks)
    }

    @Test
    fun `IPA verification applies the app binary policy before unpacking`() = temp { root ->
        val ipa = root.resolve("app.ipa")
        val info =
            """<plist><dict><key>CFBundleIdentifier</key><string>${config.bundleId}</string><key>CFBundleShortVersionString</key><string>26.09.01</string><key>CFBundleVersion</key><string>234.1</string><key>CFBundleExecutable</key><string>App</string></dict></plist>"""
        ZipOutputStream(ipa.outputStream()).use { zip ->
            for ((name, bytes) in
                mapOf(
                    "Info.plist" to info.toByteArray(),
                    "App" to "fixture-test-key".toByteArray(),
                )) {
                zip.putNextEntry(ZipEntry("Payload/App.app/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val commands = RecordingCommands().apply { response(info) }
        val policy =
            config.copy(binaryChecks = BinaryChecks(forbiddenStrings = listOf("fixture-test-key")))
        val failure = assertFails {
            Signing(policy, commands, emptyMap())
                .verifyIpa(
                    ipa,
                    Build(
                        "source-sha",
                        MarketingVersion("26.09.01"),
                        BuildNumber("234.1"),
                        "build-url",
                    ),
                )
        }
        assertContains(failure.message!!, "Forbidden release binary string")
        assertFalse(failure.message!!.contains("fixture-test-key"))
        assertEquals(listOf("plutil"), commands.calls.map { it.args.first() })
    }

    private fun project() =
        json
            .parseToJsonElement(
                """{"objects":{
        "app":{"isa":"PBXNativeTarget","name":"iosApp","productType":"com.apple.product-type.application","buildConfigurationList":"configs"},
        "configs":{"buildConfigurations":["debug","release"]},
        "debug":{"name":"Debug","buildSettings":{"CODE_SIGN_STYLE":"Automatic"}},
        "release":{"name":"Release","buildSettings":{"CODE_SIGN_STYLE":"Automatic"}},
        "resource":{"name":"Release","buildSettings":{}}
    }}"""
            )
            .jsonObject

    @Test
    fun `only app Release receives signing profile and versions`() {
        val original = project()
        val result =
            appSigningProject(
                original,
                config,
                Build(
                    "source-sha",
                    MarketingVersion("26.09.01"),
                    BuildNumber("234.1"),
                    "build-url",
                ),
                SigningIdentity("profile", "certificate"),
            )
        val objects = result.getValue("objects").jsonObject
        assertEquals(
            "profile",
            objects
                .getValue("release")
                .jsonObject
                .getValue("buildSettings")
                .jsonObject
                .string("PROVISIONING_PROFILE_SPECIFIER"),
        )
        listOf("app", "configs", "debug", "resource").forEach {
            assertEquals(original.getValue("objects").jsonObject.getValue(it), objects.getValue(it))
        }
    }

    @Test
    fun `signing overlay always restores original project on failure`() = temp { root ->
        val project = root.resolve("iosApp/iosApp.xcodeproj/project.pbxproj")
        project.parent.createDirectories()
        project.writeText("original bytes")
        val commands = RecordingCommands()
        commands.response(project().toString())
        assertFails {
            Signing(config, commands, emptyMap()).withProject(
                root,
                Build(
                    "source-sha",
                    MarketingVersion("26.09.01"),
                    BuildNumber("234.1"),
                    "build-url",
                ),
                SigningIdentity("profile", "certificate"),
            ) {
                assertContains(project.readText(), "PROVISIONING_PROFILE_SPECIFIER")
                throw CommandError("archive failure")
            }
        }
        assertEquals("original bytes", project.readText())
    }

    @Test
    fun `compiler diagnostics distinguish deterministic source errors from infrastructure`() {
        listOf(
                "/source/App.swift:12:4: error: cannot find value",
                "e: file:///source/App.kt:23:5 Unresolved reference",
                "e: /source/App.kt: (23, 5): Type mismatch",
                "/source/foo.c:9:3: fatal error: unknown type",
            )
            .forEach { assertTrue(sourceCompilationFailed(it), it) }
        listOf(
                "iosApp.xcodeproj: error: Provisioning profile is Xcode managed",
                "Package@swift-5.9.swift: error: resource bundle does not support provisioning profiles",
                "error: exportArchive signing certificate is unavailable",
                "e: java.lang.OutOfMemoryError: Java heap space",
                "error: No space left on device",
            )
            .forEach { assertFalse(sourceCompilationFailed(it), it) }
    }

    private fun profile() =
        PlistDict(
            mapOf(
                "TeamIdentifier" to PlistArray(listOf(PlistString(config.teamId))),
                "Entitlements" to
                    PlistDict(
                        mapOf(
                            "application-identifier" to
                                PlistString("${config.teamId}.${config.bundleId}")
                        )
                    ),
                "ExpirationDate" to PlistDate(Instant.parse("2030-01-01T00:00:00Z")),
                "UUID" to PlistString("profile"),
                "DeveloperCertificates" to PlistArray(listOf(PlistData(byteArrayOf(1, 2, 3)))),
            )
        )

    @Test
    fun `profile requires app store distribution app identity and unexpired validity`() {
        assertEquals("profile", validateProfile(profile(), config, clock.instant()).uuid)
        val invalid =
            listOf(
                "TeamIdentifier" to PlistArray(listOf(PlistString("other"))),
                "Entitlements" to
                    PlistDict(mapOf("application-identifier" to PlistString("wrong"))),
                "Entitlements" to
                    PlistDict(
                        mapOf(
                            "application-identifier" to
                                PlistString("${config.teamId}.${config.bundleId}"),
                            "get-task-allow" to PlistBoolean(true),
                        )
                    ),
                "ProvisionedDevices" to PlistArray(emptyList()),
                "ProvisionsAllDevices" to PlistBoolean(true),
                "IsXcodeManaged" to PlistBoolean(true),
                "ExpirationDate" to PlistDate(Instant.parse("2020-01-01T00:00:00Z")),
            )
        invalid.forEach {
            assertFails {
                validateProfile(PlistDict(profile().values + it), config, clock.instant())
            }
        }
    }

    @Test
    fun `plist parser handles Apple doctype without fetching external entities`() {
        val plist =
            parsePlist(
                """<?xml version="1.0"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>application-identifier</key><string>identity</string><key>get-task-allow</key><false/></dict></plist>"""
            )
        assertEquals("identity", plist.string("application-identifier"))
        assertFalse(plist.bool("get-task-allow"))
    }

    private val profileXml =
        """<plist><dict>
        <key>TeamIdentifier</key><array><string>${config.teamId}</string></array>
        <key>Entitlements</key><dict><key>application-identifier</key><string>${config.teamId}.${config.bundleId}</string></dict>
        <key>ExpirationDate</key><date>2099-01-01T00:00:00Z</date>
        <key>UUID</key><string>test-profile</string>
        <key>DeveloperCertificates</key><array><data>AQID</data></array>
        </dict></plist>"""

    private fun signingEnv(home: java.nio.file.Path) =
        mapOf(
            "HOME" to home.toString(),
            "IOS_DISTRIBUTION_P12_BASE64" to "BAUG",
            "IOS_DISTRIBUTION_P12_PASSWORD" to "fixture-password",
            "IOS_PROFILE_BASE64" to "AQID",
        )

    private fun operation(call: RecordingCommands.Call) =
        if (call.args == listOf("security", "-i"))
            call.input!!.substringAfter('"').substringBefore('"')
        else call.args[1]

    private fun signingCommands(xml: String = profileXml) =
        RecordingCommands().apply {
            respond = { call ->
                CommandResult(
                    0,
                    when (operation(call)) {
                        "cms" -> xml
                        "list-keychains" ->
                            "\"/original/login.keychain-db\" \"/original/other.keychain-db\""
                        "find-identity" ->
                            "1) ${digest(byteArrayOf(1, 2, 3), "SHA-1").uppercase()} \"Apple Distribution: fixture\""
                        else -> ""
                    },
                    "",
                )
            }
        }

    @Test
    fun `signing security call order and profile restoration survive action failure`() =
        temp { home ->
            val installed =
                home.resolve(
                    "Library/MobileDevice/Provisioning Profiles/test-profile.mobileprovision"
                )
            installed.parent.createDirectories()
            for (previous in listOf(null, "original profile")) {
                for (failAction in listOf(false, true)) {
                    if (previous == null) installed.deleteIfExists()
                    else installed.writeText(previous)
                    val commands = signingCommands()
                    val action = { identity: SigningIdentity ->
                        assertEquals("test-profile", identity.profile)
                        assertContentEquals(byteArrayOf(1, 2, 3), installed.readBytes())
                        val cmsProfile = Path(commands.calls.first().args.last())
                        assertContentEquals(
                            byteArrayOf(4, 5, 6),
                            cmsProfile.resolveSibling("identity.p12").readBytes(),
                        )
                        check(!failAction) { "archive failure" }
                        "archived"
                    }
                    if (failAction)
                        assertEquals(
                            "archive failure",
                            assertFailsWith<IllegalStateException> {
                                    Signing(config, commands, signingEnv(home))
                                        .withIdentity(action = action)
                                }
                                .message,
                        )
                    else
                        assertEquals(
                            "archived",
                            Signing(config, commands, signingEnv(home))
                                .withIdentity(action = action),
                        )
                    assertEquals(
                        listOf(
                            "cms",
                            "list-keychains",
                            "create-keychain",
                            "set-keychain-settings",
                            "unlock-keychain",
                            "import",
                            "list-keychains",
                            "find-identity",
                            "set-key-partition-list",
                            "list-keychains",
                            "delete-keychain",
                        ),
                        commands.calls.map(::operation),
                    )
                    assertEquals(
                        listOf(
                            "security",
                            "list-keychains",
                            "-d",
                            "user",
                            "-s",
                            "/original/login.keychain-db",
                            "/original/other.keychain-db",
                        ),
                        commands.calls[commands.calls.lastIndex - 1].args,
                    )
                    if (previous == null) assertFalse(installed.exists())
                    else assertEquals(previous, installed.readText())
                    assertFalse(Path(commands.calls.first().args.last()).parent.exists())
                }
            }
        }

    @Test
    fun `failed identity import still restores search list deletes keychain and removes temporary files`() =
        temp { home ->
            val commands = signingCommands()
            val respond = requireNotNull(commands.respond)
            commands.respond = { call ->
                if (operation(call) == "import") CommandResult(1, "", "failure") else respond(call)
            }
            val failure =
                assertFailsWith<CommandError> {
                    Signing(config, commands, signingEnv(home)).withIdentity {
                        fail("must not reach archive")
                    }
                }
            assertEquals("security failed (exit 1)", failure.message)
            assertEquals(
                listOf(
                    "cms",
                    "list-keychains",
                    "create-keychain",
                    "set-keychain-settings",
                    "unlock-keychain",
                    "import",
                    "list-keychains",
                    "delete-keychain",
                ),
                commands.calls.map(::operation),
            )
            assertFalse(Path(commands.calls.first().args.last()).parent.exists())
        }

    @Test
    fun `undecodable profile retains base64 error without running security`() = temp { home ->
        val commands = signingCommands()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                Signing(config, commands, signingEnv(home) + ("IOS_PROFILE_BASE64" to "%%%"))
                    .withIdentity { fail("must not reach archive") }
            }
        assertEquals("Illegal base64 character 25", failure.message)
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `invalid profiles retain policy error without keychain commands`() = temp { home ->
        for ((xml, message) in
            listOf(
                profileXml.replace("2099-01-01", "2020-01-01") to "Provisioning profile expired",
                profileXml.replace(config.bundleId, "wrong.app") to
                    "A manually managed App Store profile must match this app and team",
            )) {
            for (diagnostic in listOf(false, true)) {
                val commands = signingCommands(xml)
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        Signing(config, commands, signingEnv(home)).withIdentity(diagnostic) {
                            fail("must not reach signing action")
                        }
                    }
                assertEquals(message, failure.message)
                assertEquals(listOf("cms"), commands.calls.map(::operation))
                assertFalse(Path(commands.calls.single().args.last()).parent.exists())
            }
        }
    }
}
