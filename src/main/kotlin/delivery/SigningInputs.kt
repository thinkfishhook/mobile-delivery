package delivery

import java.io.IOException
import java.nio.file.Path
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import kotlin.io.path.*

// Keep raw credentials in memory/private temporary files, without generated value logging.
// Only fully decoded and validated inputs can enter a keychain session.
internal class SigningInputs
private constructor(
    val p12: ByteArray,
    val profile: ByteArray,
    val plist: PlistDict,
    val provisioningProfile: ProvisioningProfile,
) {
    companion object {
        private fun invalidP12() =
            CredentialProblem(
                "IOS_DISTRIBUTION_P12_BASE64",
                "IOS_DISTRIBUTION_P12_BASE64 is not a base64 PKCS#12 export",
                "base64 < distribution.p12 | tr -d '\\n' | gh secret set IOS_DISTRIBUTION_P12_BASE64 --env ios-delivery",
            )

        // The caller owns this private directory and removes it under the signing lock.
        fun read(
            config: DeliveryConfig,
            commands: Commands,
            env: Map<String, String>,
            temp: Path,
        ): SigningInputs {
            val names =
                listOf(
                    "IOS_DISTRIBUTION_P12_BASE64",
                    "IOS_DISTRIBUTION_P12_PASSWORD",
                    "IOS_PROFILE_BASE64",
                )
            names.forEach { require(!env[it].isNullOrBlank()) { "Missing GitHub secret $it" } }
            val p12 =
                try {
                    Base64.getDecoder().decode(env.getValue(names[0]))
                } catch (_: IllegalArgumentException) {
                    throw invalidP12()
                }
            // The JDK PKCS12 provider also accepts JKS for compatibility. A PFX must be
            // an ASN.1 SEQUENCE; reject other keystore formats before that fallback.
            if (p12.firstOrNull() != 0x30.toByte()) throw invalidP12()
            val password = env.getValue(names[1]).toCharArray()
            try {
                KeyStore.getInstance("PKCS12").load(p12.inputStream(), password)
            } catch (error: IOException) {
                if (error.cause is UnrecoverableKeyException) {
                    throw CredentialProblem(
                        names[1],
                        "IOS_DISTRIBUTION_P12_PASSWORD doesn't open IOS_DISTRIBUTION_P12_BASE64",
                        "gh secret set IOS_DISTRIBUTION_P12_PASSWORD --env ios-delivery",
                    )
                }
                throw invalidP12()
            } finally {
                password.fill('\u0000')
            }
            val profile = Base64.getDecoder().decode(env.getValue(names[2]))
            val file = temp.resolve("app.mobileprovision").apply { writeBytes(profile) }
            val plist =
                parsePlist(
                    commands.run(
                        listOf("security", "cms", "-D", "-i", file.toString()),
                        env = buildEnvironment(env),
                    )
                )
            return SigningInputs(p12, profile, plist, validateProfile(plist, config))
        }
    }
}
