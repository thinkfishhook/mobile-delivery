package delivery

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

fun digest(bytes: ByteArray, algorithm: String = "SHA-256") =
    MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

// The manifest travels with the retained build; artifact.json stays with the run's evidence.
fun writeArchiveEvidence(output: Path, build: Build) {
    output.resolve("manifest.json").writeText(json.encodeToString(build.artifact) + "\n")
    output.resolve("artifact.json").writeText(json.encodeToString(build) + "\n")
}

data class SigningIdentity(val profile: String, val certificate: String)

data class ProvisioningProfile(val uuid: String, val certificates: Set<String>)

fun validateProfile(
    profile: PlistDict,
    config: DeliveryConfig,
    now: Instant = Instant.now(),
    identityCertificates: Set<String>? = null,
): ProvisioningProfile {
    fun problem(description: String): Nothing =
        throw CredentialProblem(
            "IOS_PROFILE_BASE64",
            "IOS_PROFILE_BASE64: $description",
            "Renew it in Apple Developer → Profiles, then run:\n" +
                "base64 < App.mobileprovision | tr -d '\\n' | gh secret set IOS_PROFILE_BASE64 --env ios-delivery",
        )
    fun safe(value: String) = value.replace(Regex("[\\p{Cntrl}]"), " ").replace("\"", "'")
    val expiry = (profile.values.getValue("ExpirationDate") as PlistDate).value
    if (expiry <= now)
        problem(
            "provisioning profile \"${safe((profile.values["Name"] as? PlistString)?.value ?: profile.string("UUID"))}\" expired on $expiry."
        )
    val entitlements = profile.dict("Entitlements")
    val teams = profile.array("TeamIdentifier").map { (it as PlistString).value }
    if (teams != listOf(config.teamId)) {
        problem("team: expected ${config.teamId}; actual ${safe(teams.joinToString())}.")
    }
    val application = entitlements.string("application-identifier")
    val expected = "${config.teamId}.${config.bundleId}"
    if (application != expected) {
        problem("application identifier: expected $expected; actual ${safe(application)}.")
    }
    if (
        entitlements.bool("get-task-allow") ||
            "ProvisionedDevices" in profile.values ||
            profile.bool("ProvisionsAllDevices") ||
            profile.bool("IsXcodeManaged")
    ) {
        problem("must be a manually managed App Store profile.")
    }
    val certificates =
        profile
            .array("DeveloperCertificates")
            .map { digest((it as PlistData).value, "SHA-1").uppercase() }
            .toSet()
    if (identityCertificates != null && certificates.intersect(identityCertificates).isEmpty()) {
        problem(
            "contains no certificate from IOS_DISTRIBUTION_P12_BASE64. Profile certificate SHA-1: ${certificates.sorted().joinToString().ifEmpty { "none" }}; P12 identity certificate SHA-1: ${identityCertificates.sorted().joinToString().ifEmpty { "none" }}."
        )
    }
    return ProvisioningProfile(profile.string("UUID"), certificates)
}

fun sourceCompilationFailed(log: String) =
    Regex(
            "^.*\\.(?:swift|m|mm|c|cc|cpp|h|hpp):\\d+:\\d+:\\s+(?:fatal )?error:|^e: (?:file://)?[^\\n]+\\.kts?:(?:\\d+:\\d+|\\s*\\(\\d+,\\s*\\d+\\))",
            RegexOption.MULTILINE,
        )
        .containsMatchIn(log)

fun appSigningProject(
    document: JsonObject,
    config: DeliveryConfig,
    build: Build,
    identity: SigningIdentity,
): JsonObject {
    val objects = document.getValue("objects").jsonObject
    val targets =
        objects.values
            .map { it.jsonObject }
            .filter {
                it["isa"]?.jsonPrimitive?.content == "PBXNativeTarget" &&
                    it["name"]?.jsonPrimitive?.content == config.ios.target &&
                    it["productType"]?.jsonPrimitive?.content ==
                        "com.apple.product-type.application"
            }
    if (targets.size != 1)
        throw ArtifactError("Expected one ${config.ios.target} application target")
    val configs =
        objects
            .getValue(targets.single().string("buildConfigurationList"))
            .jsonObject
            .getValue("buildConfigurations")
            .jsonArray
    val releases =
        configs
            .map { it.jsonPrimitive.content }
            .filter { objects.getValue(it).jsonObject.string("name") == "Release" }
    if (releases.size != 1) throw ArtifactError("Expected one app Release configuration")
    val key = releases.single()
    val release = objects.getValue(key).jsonObject
    val settings =
        release.getValue("buildSettings").jsonObject +
            mapOf(
                "CODE_SIGN_STYLE" to JsonPrimitive("Manual"),
                "CODE_SIGN_IDENTITY" to JsonPrimitive(identity.certificate),
                "DEVELOPMENT_TEAM" to JsonPrimitive(config.teamId),
                "PROVISIONING_PROFILE_SPECIFIER" to JsonPrimitive(identity.profile),
                "MARKETING_VERSION" to JsonPrimitive(build.version.value),
                "CURRENT_PROJECT_VERSION" to JsonPrimitive(build.number.value),
            )
    return JsonObject(
        document +
            ("objects" to
                JsonObject(
                    objects +
                        (key to JsonObject(release + ("buildSettings" to JsonObject(settings))))
                ))
    )
}

class Signing(
    private val config: DeliveryConfig,
    private val commands: Commands,
    private val env: Map<String, String>,
) {
    private val environment = buildEnvironment(env)

    private fun run(vararg args: String) = commands.run(args.toList(), env = environment)

    private fun security(vararg args: String) = commands.security(args.toList(), environment)

    fun <T> withIdentity(diagnostic: Boolean = false, action: (SigningIdentity) -> T): T =
        withSigningLock(Path.of(env.getValue("HOME"))) {
            val temp = createTempDirectory("ios-signing-")
            try {
                val inputs = SigningInputs.read(config, commands, env, temp)
                signingSession(inputs, temp, diagnostic, action)
            } finally {
                temp.toFile().deleteRecursively()
            }
        }

    private fun <T> signingSession(
        inputs: SigningInputs,
        temp: Path,
        diagnostic: Boolean,
        action: (SigningIdentity) -> T,
    ): T {
        val identity = temp.resolve("identity.p12").apply { writeBytes(inputs.p12) }
        val decoded = inputs.provisioningProfile
        val keychain = temp.resolve("signing.keychain-db").toString()
        val password = UUID.randomUUID().toString() + UUID.randomUUID()
        val search =
            Regex("\"([^\"]+)\"")
                .findAll(run("security", "list-keychains", "-d", "user"))
                .map { it.groupValues[1] }
                .toList()
        val installed =
            Path.of(
                env.getValue("HOME"),
                "Library/MobileDevice/Provisioning Profiles",
                "${decoded.uuid}.mobileprovision",
            )
        installed.parent.createDirectories()
        val previous = if (installed.exists()) installed.readBytes() else null
        try {
            security("create-keychain", "-p", password, keychain)
            run("security", "set-keychain-settings", "-lut", "21600", keychain)
            security("unlock-keychain", "-p", password, keychain)
            security(
                "import",
                identity.toString(),
                "-k",
                keychain,
                "-P",
                env.getValue("IOS_DISTRIBUTION_P12_PASSWORD"),
                "-T",
                "/usr/bin/codesign",
                "-T",
                "/usr/bin/security",
            )
            run(
                "security",
                "list-keychains",
                "-d",
                "user",
                "-s",
                keychain,
                *search.toTypedArray(),
            )
            val identities = run("security", "find-identity", "-v", "-p", "codesigning", keychain)
            val valid = distributionIdentities(identities)
            val matches = valid.intersect(decoded.certificates)
            if (diagnostic || matches.size != 1) {
                val report =
                    signingDiagnostic(
                        decoded.certificates,
                        valid,
                        run("security", "find-certificate", "-a", "-Z", keychain),
                        run("security", "find-identity", "-p", "codesigning", keychain),
                    )
                require(matches.size == 1) { report }
                println(report)
            }
            if (diagnostic) return action(SigningIdentity(decoded.uuid, matches.single()))
            security(
                "set-key-partition-list",
                "-S",
                "apple-tool:,apple:,codesign:",
                "-s",
                "-k",
                password,
                keychain,
            )
            installed.writeBytes(inputs.profile)
            return action(SigningIdentity(decoded.uuid, matches.single()))
        } finally {
            if (previous == null) installed.deleteIfExists() else installed.writeBytes(previous)
            try {
                run("security", "list-keychains", "-d", "user", "-s", *search.toTypedArray())
            } finally {
                run("security", "delete-keychain", keychain)
            }
        }
    }

    fun <T> withProject(
        source: Path,
        build: Build,
        identity: SigningIdentity,
        action: () -> T,
    ): T {
        val project = source.resolve(config.ios.project).resolve("project.pbxproj")
        val original = project.readBytes()
        val document =
            json
                .parseToJsonElement(
                    run("plutil", "-convert", "json", "-o", "-", project.toString())
                )
                .jsonObject
        val overlay = appSigningProject(document, config, build, identity)
        try {
            project.writeText(overlay.toString())
            run("plutil", "-convert", "xml1", project.toString())
            return action()
        } finally {
            project.writeBytes(original)
        }
    }

    fun archive(source: Path, output: Path, build: Build): Path = withIdentity { identity ->
        val archive = output.resolve("${config.ios.name}.xcarchive")
        val ipa = output.resolve("${config.ios.name}.ipa")
        val flags =
            listOf(
                "-destination",
                "generic/platform=iOS",
                "-derivedDataPath",
                output.resolve("DerivedData").toString(),
            )
        val command =
            listOf(
                "asc",
                "xcode",
                "archive",
                config.ios.workspace?.let { "--workspace" } ?: "--project",
                config.ios.workspace ?: config.ios.project,
                "--scheme",
                config.ios.scheme,
                "--configuration",
                "Release",
                "--archive-path",
                archive.toString(),
            ) + flags.map { "--xcodebuild-flag=$it" }
        withProject(source, build, identity) {
            try {
                commands.run(
                    command,
                    cwd = source,
                    log = output.resolve("archive.log"),
                    // The app's build values compile into the binary; nothing else is added.
                    env = appEnvironment(env, config.buildEnvironment),
                )
            } catch (error: CommandError) {
                if (sourceCompilationFailed(output.resolve("archive.log").readText()))
                    throw ArtifactError("Release compilation failed; see archive.log")
                throw error
            }
        }
        val options = output.resolve("ExportOptions.plist")
        options.writeText(
            buildJsonObject {
                put("method", "app-store-connect")
                put("destination", "export")
                put("signingStyle", "manual")
                put("teamID", config.teamId)
                put("manageAppVersionAndBuildNumber", false)
                put("signingCertificate", identity.certificate)
                putJsonObject("provisioningProfiles") { put(config.bundleId, identity.profile) }
            }
                .toString()
        )
        run("plutil", "-convert", "xml1", options.toString())
        commands.run(
            listOf(
                "asc",
                "xcode",
                "export",
                "--archive-path",
                archive.toString(),
                "--export-options",
                options.toString(),
                "--ipa-path",
                ipa.toString(),
            ),
            log = output.resolve("export.log"),
            env = environment,
        )
        try {
            verifyIpa(ipa, build)
        } catch (error: IllegalArgumentException) {
            throw ArtifactError(error.displayMessage() ?: "Artifact validation failed")
        }
        val symbolsDirectory = archive.resolve("dSYMs")
        val symbols =
            if (
                symbolsDirectory.isDirectory() &&
                    symbolsDirectory.listDirectoryEntries().isNotEmpty()
            ) {
                val packageFile = output.resolve("symbols-${build.version}-${build.number}.zip")
                run(
                    "ditto",
                    "-c",
                    "-k",
                    "--keepParent",
                    symbolsDirectory.toString(),
                    packageFile.toString(),
                )
                ArtifactFile(
                    packageFile.fileName.toString(),
                    digest(packageFile.readBytes()),
                    packageFile.fileSize(),
                )
            } else null
        build.artifact =
            ArtifactManifest(
                sha = build.sha,
                version = build.version,
                number = build.number,
                workflow =
                    requireNotNull(build.workflow) {
                        "Build ${build.number} manifest requires its originating Actions run URL; no workflow was recorded"
                    },
                artifactName = artifactName(build.number),
                ipa =
                    ArtifactFile(
                        ipa.fileName.toString(),
                        requireNotNull(build.ipaSha256) {
                            "Build ${build.number} manifest requires a SHA-256 checksum for $ipa; export and hash the IPA first"
                        },
                        requireNotNull(build.ipaBytes) {
                            "Build ${build.number} manifest requires a byte count for $ipa; export and measure the IPA first"
                        },
                    ),
                symbols = symbols,
                symbolsAbsentReason = if (symbols == null) "Archive contained no dSYMs" else null,
            )
        writeArchiveEvidence(output, build)
        ipa
    }

    fun verifyIpa(ipa: Path, build: Build) {
        val temp = createTempDirectory("ipa-verification-")
        try {
            ZipFile(ipa.toFile()).use { zip ->
                val roots =
                    zip.entries()
                        .asSequence()
                        .filter { Regex("Payload/[^/]+\\.app/Info.plist").matches(it.name) }
                        .toList()
                require(roots.size == 1) { "Expected exactly one top-level app" }
                val infoFile = temp.resolve("Info.plist")
                infoFile.writeBytes(zip.getInputStream(roots.single()).readBytes())
                val info =
                    parsePlist(run("plutil", "-convert", "xml1", "-o", "-", infoFile.toString()))
                require(
                    listOf(
                        info.string("CFBundleIdentifier"),
                        info.string("CFBundleShortVersionString"),
                        info.string("CFBundleVersion"),
                    ) == listOf(config.bundleId, build.version.value, build.number.value)
                ) {
                    "IPA identity mismatch"
                }
                val executable = info.string("CFBundleExecutable")
                require('/' !in executable && executable != "..") { "Invalid executable path" }
                val binary =
                    zip.getInputStream(
                            requireNotNull(
                                zip.getEntry(
                                    roots.single().name.removeSuffix("Info.plist") + executable
                                )
                            ) {
                                "IPA $ipa names executable '$executable' in Info.plist, but its app bundle contains no matching ZIP entry"
                            }
                        )
                        .readBytes()
                validateReleaseBinary(binary, config.binaryChecks)
                require(
                    zip.entries().asSequence().none {
                        it.name.startsWith('/') || it.name.split('/').contains("..")
                    }
                ) {
                    "Unsafe IPA archive entry"
                }
            }
            run("ditto", "-x", "-k", ipa.toString(), temp.toString())
            val app = temp.resolve("Payload").listDirectoryEntries("*.app").single()
            run("codesign", "--verify", "--deep", "--strict", app.toString())
            val entitlements =
                parsePlist(run("codesign", "-d", "--entitlements", ":-", app.toString()))
            require(
                entitlements.string("application-identifier") ==
                    "${config.teamId}.${config.bundleId}" &&
                    entitlements.string("com.apple.developer.team-identifier") == config.teamId &&
                    !entitlements.bool("get-task-allow")
            ) {
                "Exported signing entitlements do not match release policy"
            }
            build.ipaSha256 = digest(ipa.readBytes())
            build.ipaBytes = ipa.fileSize()
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
