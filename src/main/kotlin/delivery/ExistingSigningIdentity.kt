package delivery

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*

// Local archives use an explicitly selected existing identity, never a key export/import.
// Share the delivery signing lock because both paths temporarily install a profile.
internal fun <T> withExistingSigningIdentity(
    config: DeliveryConfig,
    commands: Commands,
    env: Map<String, String>,
    profile: Path,
    certificate: String,
    action: (SigningIdentity) -> T,
): T {
    require(Regex("[0-9A-F]{40}").matches(certificate)) {
        "--identity must be the full SHA-1 fingerprint of one distribution identity"
    }
    val home = Path.of(env.getValue("HOME")).toRealPath()
    return withSigningLock(home) {
        val temp = createTempDirectory("local-archive-profile-")
        try {
            // Snapshot the input so the bytes validated are the bytes installed.
            val bytes = profile.readBytes()
            val copy = temp.resolve("app.mobileprovision").apply { writeBytes(bytes) }
            val environment = buildEnvironment(env)
            val decoded =
                try {
                    validateProfile(
                        parsePlist(
                            commands.run(
                                listOf("security", "cms", "-D", "-i", copy.toString()),
                                env = environment,
                            )
                        ),
                        config,
                    )
                } catch (problem: CredentialProblem) {
                    val reason =
                        problem.message
                            .orEmpty()
                            .lineSequence()
                            .first()
                            .removePrefix("IOS_PROFILE_BASE64: ")
                    throw IllegalArgumentException(
                        "Local provisioning profile rejected: $reason Supply a matching manual App Store profile with --profile.",
                        problem,
                    )
                }
            require(
                UUID.fromString(decoded.uuid).toString().equals(decoded.uuid, ignoreCase = true)
            ) {
                "Profile UUID must be a canonical UUID"
            }
            require(certificate in decoded.certificates) {
                "Selected identity is not in the provisioning profile"
            }
            val valid =
                distributionIdentities(
                    commands.run(
                        listOf("security", "find-identity", "-v", "-p", "codesigning"),
                        env = environment,
                    )
                )
            require(certificate in valid) {
                "Selected distribution identity is not available and valid in the current keychain search list"
            }
            val installed =
                home.resolve(
                    "Library/MobileDevice/Provisioning Profiles/${decoded.uuid}.mobileprovision"
                )
            installed.parent.createDirectories()
            require(
                !installed.isSymbolicLink() &&
                    (!installed.exists(NOFOLLOW_LINKS) ||
                        Files.isRegularFile(installed, NOFOLLOW_LINKS))
            ) {
                "Installed provisioning profile must be a regular file, not a link"
            }
            val previous = if (installed.exists()) installed.readBytes() else null
            try {
                installed.writeBytes(bytes)
                action(SigningIdentity(decoded.uuid, certificate))
            } finally {
                if (previous == null) installed.deleteIfExists() else installed.writeBytes(previous)
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
