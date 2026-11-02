package delivery

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class SigningDiagnosticsTest {
    private val hash = "A".repeat(40)
    private val other = "B".repeat(40)
    private val identity = "1) $hash \"Apple Distribution: private name\""

    @Test
    fun `diagnostics distinguish mismatch missing key invalid and ambiguous identities`() {
        fun report(imported: String, all: String, valid: Set<String> = emptySet()) =
            signingDiagnostic(setOf(hash), valid, "SHA-1 hash: $imported", all)
        assertContains(report(other, identity), "no certificate selected")
        assertContains(report(hash, "0 identities found"), "P12 includes its private key")
        val invalid = report(hash, "$identity (CSSMERR_TP_NOT_TRUSTED)")
        assertContains(invalid, "macOS rejects")
        assertContains(invalid, "CSSMERR_TP_NOT_TRUSTED")
        assertFalse("private name" in invalid)
        assertContains(report(hash, identity, setOf(hash)), "Exactly one valid")
        assertContains(
            signingDiagnostic(setOf(hash, other), setOf(hash, other), "", ""),
            "no certificate selected",
        )
        assertContains(
            signingDiagnostic(
                setOf(hash, other),
                setOf(hash, other),
                "SHA-1 hash: $hash",
                identity,
            ),
            "Multiple valid",
        )
        assertEquals(
            setOf(hash),
            distributionIdentities(
                identity.lowercase().replace("apple distribution", "Apple Distribution")
            ),
        )
        assertTrue(
            distributionIdentities(identity.replace("Apple Distribution", "Apple Development"))
                .isEmpty()
        )
    }

    @Test
    fun `signing restores keychain and profile on success and rejection without exposing passwords`() =
        temp { home ->
            val fingerprint = digest(syntheticCertificate, "SHA-1").uppercase()
            val xml =
                """<plist><dict>
            <key>TeamIdentifier</key><array><string>${config.teamId}</string></array>
            <key>Entitlements</key><dict><key>application-identifier</key><string>${config.teamId}.${config.bundleId}</string></dict>
            <key>ExpirationDate</key><date>2099-01-01T00:00:00Z</date>
            <key>UUID</key><string>diagnostic-profile</string>
            <key>DeveloperCertificates</key><array><data>$profileCertificateData</data></array>
            </dict></plist>"""
            listOf(
                    Triple(true, true, false),
                    Triple(false, true, false),
                    Triple(true, false, false),
                    Triple(true, false, true),
                )
                .forEach { (valid, diagnostic, archiveFails) ->
                    val commands = RecordingCommands()
                    commands.respond = { call ->
                        // The lock spans every operation, including final search-list restoration.
                        FileChannel.open(home.resolve(".mobile-delivery/signing.lock"), WRITE).use {
                            assertFailsWith<OverlappingFileLockException> { it.tryLock()?.close() }
                        }
                        assertNull(call.env["IOS_DISTRIBUTION_P12_PASSWORD"])
                        assertNull(call.env["GH_TOKEN"])
                        val output =
                            when (call.args[1]) {
                                "cms" -> xml
                                "list-keychains" -> "\"/original/login.keychain-db\""
                                "find-certificate" -> "SHA-1 hash: $fingerprint"
                                "find-identity" ->
                                    if (!valid && "-v" in call.args) "0 valid identities found"
                                    else
                                        "1) $fingerprint \"Apple Distribution: fixture\" (CSSMERR_TP_NOT_TRUSTED)"
                                else -> ""
                            }
                        CommandResult(0, output, "")
                    }
                    val signing =
                        Signing(
                            config,
                            commands,
                            mapOf(
                                "HOME" to home.toString(),
                                "IOS_DISTRIBUTION_P12_BASE64" to
                                    java.util.Base64.getEncoder().encodeToString(syntheticP12),
                                "IOS_DISTRIBUTION_P12_PASSWORD" to p12Password,
                                "IOS_PROFILE_BASE64" to "AQID",
                                "GH_TOKEN" to "job-secret",
                            ),
                        )
                    var reached = false
                    val action = { _: SigningIdentity ->
                        reached = true
                        check(!archiveFails) { "archive failed" }
                    }
                    if (archiveFails)
                        assertFailsWith<IllegalStateException> {
                            signing.withIdentity(diagnostic = diagnostic, action = action)
                        }
                    else if (valid) signing.withIdentity(diagnostic = diagnostic, action = action)
                    else
                        assertFailsWith<IllegalArgumentException> {
                            signing.withIdentity(diagnostic = diagnostic, action = action)
                        }
                    assertEquals(valid, reached)
                    val sensitive =
                        commands.calls.filter { call ->
                            listOf(
                                    "create-keychain",
                                    "unlock-keychain",
                                    "import",
                                    "set-key-partition-list",
                                )
                                .any {
                                    it in call.args || call.input?.contains("\"$it\"") == true
                                }
                        }
                    assertEquals(if (diagnostic) 3 else 4, sensitive.size)
                    sensitive.forEach { call ->
                        assertEquals(listOf("security", "-i"), call.args)
                        assertNull(call.log)
                    }
                    assertTrue(commands.calls.none { "secret" in it.args })
                    assertEquals("delete-keychain", commands.calls.last().args[1])
                    assertEquals(
                        "/original/login.keychain-db",
                        commands.calls[commands.calls.lastIndex - 1].args.last(),
                    )
                    assertEquals(
                        !diagnostic,
                        commands.calls.any { it.input?.contains("set-key-partition-list") == true },
                    )
                    assertFalse(
                        home
                            .resolve(
                                "Library/MobileDevice/Provisioning Profiles/diagnostic-profile.mobileprovision"
                            )
                            .exists()
                    )
                    val profile = Path(commands.calls.single { "cms" in it.args }.args.last())
                    assertFalse(profile.resolveSibling("identity.p12").exists())
                }
        }
}
