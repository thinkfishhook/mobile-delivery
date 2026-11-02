package delivery

import java.nio.file.Path
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
            val p12 = Base64.getDecoder().decode(env.getValue(names[0]))
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
