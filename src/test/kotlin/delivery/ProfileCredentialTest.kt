package delivery

import java.security.KeyStore
import java.util.Base64
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

internal val syntheticCertificate: ByteArray by lazy {
    KeyStore.getInstance("PKCS12")
        .apply {
            load(syntheticP12.inputStream(), p12Password.toCharArray())
        }
        .getCertificate("fixture")
        .encoded
}
internal val syntheticPrivateKeyData: String by lazy {
    val store =
        KeyStore.getInstance("PKCS12").apply {
            load(syntheticP12.inputStream(), p12Password.toCharArray())
        }
    Base64.getEncoder().encodeToString(store.getKey("fixture", p12Password.toCharArray()).encoded)
}

internal val profileCertificateData: String
    get() = Base64.getEncoder().encodeToString(syntheticCertificate)

internal val neutralProfileXml: String
    get() =
        """<plist><dict>
        <key>Name</key><string>Fixture App Store</string>
        <key>TeamIdentifier</key><array><string>${config.teamId}</string></array>
        <key>Entitlements</key><dict><key>application-identifier</key><string>${config.teamId}.${config.bundleId}</string></dict>
        <key>ExpirationDate</key><date>2099-01-01T00:00:00Z</date>
        <key>UUID</key><string>fixture-profile</string>
        <key>DeveloperCertificates</key><array><data>$profileCertificateData</data></array>
        </dict></plist>"""

internal val profileFix =
    "Renew it in Apple Developer → Profiles, then run:\n" +
        "base64 < App.mobileprovision | tr -d '\\n' | gh secret set IOS_PROFILE_BASE64 --env ios-delivery"

internal fun invalidProfileFixtures(): List<Pair<String, String>> =
    listOf(
        neutralProfileXml.replace("2099-01-01", "2000-01-01") to
            "IOS_PROFILE_BASE64: provisioning profile \"Fixture App Store\" expired on 2000-01-01T00:00:00Z.",
        neutralProfileXml.replace(profileCertificateData, "AQID") to
            "IOS_PROFILE_BASE64: contains no certificate from IOS_DISTRIBUTION_P12_BASE64. Profile certificate SHA-1: ${digest(byteArrayOf(1, 2, 3), "SHA-1").uppercase()}; P12 identity certificate SHA-1: ${digest(syntheticCertificate, "SHA-1").uppercase()}.",
        neutralProfileXml.replace(config.bundleId, "wrong.app") to
            "IOS_PROFILE_BASE64: application identifier: expected ${config.teamId}.${config.bundleId}; actual ${config.teamId}.wrong.app.",
        neutralProfileXml.replace(
            "<string>${config.teamId}</string>",
            "<string>OTHERTEAM</string>",
        ) to "IOS_PROFILE_BASE64: team: expected ${config.teamId}; actual OTHERTEAM.",
    )

class ProfileCredentialTest {
    @Test
    fun `profile problems are actionable and identical before archive and diagnostics mutate keychains`() =
        temp { root ->
            for ((xml, description) in invalidProfileFixtures()) {
                for (diagnostic in listOf(false, true)) {
                    val commands = RecordingCommands().apply { response(xml) }
                    val env =
                        mapOf(
                            "HOME" to root.toString(),
                            "IOS_DISTRIBUTION_P12_BASE64" to
                                Base64.getEncoder().encodeToString(syntheticP12),
                            "IOS_DISTRIBUTION_P12_PASSWORD" to p12Password,
                            "IOS_PROFILE_BASE64" to "AQID",
                        )
                    val error =
                        assertFailsWith<CredentialProblem> {
                            Signing(config, commands, env).withIdentity(diagnostic) {
                                fail("No signing action")
                            }
                        }
                    assertEquals("IOS_PROFILE_BASE64", error.secret)
                    assertEquals("$description\n$profileFix", error.message)
                    for (secret in
                        listOf(
                            profileCertificateData,
                            syntheticPrivateKeyData,
                            Base64.getEncoder().encodeToString(syntheticP12),
                            p12Password,
                            "BEGIN PRIVATE KEY",
                            "AQID",
                        )) {
                        assertFalse(secret in error.toString())
                    }
                    assertEquals(listOf("cms"), commands.calls.map { it.args[1] })
                    assertFalse(Path(commands.calls.single().args.last()).parent.exists())
                }
            }
        }

    @Test
    fun `valid profile accepts matching P12 without keychain mutation`() = temp { root ->
        val commands = RecordingCommands().apply { response(neutralProfileXml) }
        val inputs =
            SigningInputs.read(
                config,
                commands,
                mapOf(
                    "IOS_DISTRIBUTION_P12_BASE64" to
                        Base64.getEncoder().encodeToString(syntheticP12),
                    "IOS_DISTRIBUTION_P12_PASSWORD" to p12Password,
                    "IOS_PROFILE_BASE64" to "AQID",
                ),
                root,
            )
        assertEquals("fixture-profile", inputs.provisioningProfile.uuid)
        assertEquals(
            setOf(digest(syntheticCertificate, "SHA-1").uppercase()),
            inputs.provisioningProfile.certificates,
        )
        assertEquals(listOf("cms"), commands.calls.map { it.args[1] })
    }
}
