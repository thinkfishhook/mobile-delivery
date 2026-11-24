package delivery

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

// Intentionally not ArtifactManifest: local archives cannot satisfy delivery receipts,
// verification/reservation provenance or the retained-package upload contract.
@Serializable
internal data class LocalArchiveEvidence(
    val sha: String,
    val configurationSha256: String,
    val version: MarketingVersion,
    val number: BuildNumber,
    val createdAt: String,
    val certificate: String,
    val profile: String,
    val xcode: String,
    val asc: String,
    val ipa: ArtifactFile,
    val symbols: ArtifactFile?,
    val symbolsAbsentReason: String?,
) {
    val kind: String = "local-archive"
    val verification: String = "not-run"
    val deliveryEligible: Boolean = false
}

private data class LocalArchiveOptions(
    val sha: String,
    val profile: Path,
    val certificate: String,
    val version: MarketingVersion,
    val number: BuildNumber,
    val output: Path,
) {
    companion object {
        fun parse(args: List<String>, root: Path): LocalArchiveOptions {
            val required =
                setOf("--sha", "--profile", "--identity", "--version", "--build", "--output")
            val flags = args.chunked(2).map { it.first() }
            require(
                args.size == required.size * 2 &&
                    flags.toSet() == required &&
                    flags.distinct().size == flags.size
            ) {
                "archive-local requires --sha, --profile, --identity, --version, --build and --output"
            }
            val values = args.chunked(2).associate { it[0] to it[1] }
            require(values.values.none { it.isBlank() || it.any(Char::isISOControl) }) {
                "Arguments must be nonblank single-line values"
            }
            val sha = values.getValue("--sha")
            require(Regex("[0-9a-f]{40}").matches(sha)) { "--sha must be a full commit SHA" }
            val certificate = values.getValue("--identity").uppercase()
            require(Regex("[0-9A-F]{40}").matches(certificate)) {
                "--identity must be a full certificate SHA-1 fingerprint"
            }
            val output = root.resolve(values.getValue("--output")).toAbsolutePath().normalize()
            val canonical = output.parent.toRealPath().resolve(output.fileName)
            require(!canonical.startsWith(root)) { "--output must be outside the source checkout" }
            require(!canonical.exists(java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "--output must not already exist; use a new directory"
            }
            return LocalArchiveOptions(
                sha,
                root.resolve(values.getValue("--profile")).toRealPath(),
                certificate,
                MarketingVersion(values.getValue("--version")),
                BuildNumber(values.getValue("--build")),
                canonical,
            )
        }
    }
}

internal fun localArchive(
    args: List<String>,
    root: Path,
    env: Map<String, String>,
    commands: Commands,
) {
    require(env["GITHUB_ACTIONS"] != "true") {
        "archive-local is a local-only diagnostic, not an Actions delivery phase"
    }
    val source = root.toRealPath()
    val options = LocalArchiveOptions.parse(args, source)
    val environment = buildEnvironment(env)
    fun git(vararg args: String) =
        commands.run(listOf("git", *args), cwd = source, env = environment).trim()
    require(Path.of(git("rev-parse", "--show-toplevel")).toRealPath() == source) {
        "Run archive-local at the repository root"
    }
    require(git("rev-parse", "HEAD") == options.sha) {
        "--sha must match the current checked-out commit"
    }
    require(git("status", "--porcelain", "--untracked-files=all").isEmpty()) {
        "Source checkout must be clean before archiving"
    }
    // Read committed config, not a possibly changing worktree copy. No made-up Apple IDs.
    val text =
        commands.run(
            listOf("git", "show", "${options.sha}:${DeliveryConfig.DEFAULT_PATH}"),
            cwd = source,
            env = environment,
        )
    requireConfiguration(
        configurationProblems(text, archiveOnly = true),
        DeliveryConfig.DEFAULT_PATH,
    )
    val config = json.decodeFromString<DeliveryConfig>(text)
    require(
        config.ios.name !in setOf(".", "..") && Regex("[A-Za-z0-9_. -]+").matches(config.ios.name)
    ) {
        "ios.name must be a simple artifact filename"
    }
    val platform = commands.run(listOf("uname", "-s"), env = environment).trim()
    require(platform == "Darwin") { "archive-local requires macOS" }
    val xcode =
        commands
            .run(listOf("xcodebuild", "-version"), env = environment)
            .lineSequence()
            .first()
            .trim()
    require(xcode == "Xcode ${config.xcode}") {
        "Expected Xcode ${config.xcode}; select it with DEVELOPER_DIR"
    }
    val asc =
        commands.run(listOf("asc", "--version"), env = environment).trim().substringBefore(' ')
    require(asc == config.asc) { "Expected asc ${config.asc}; use the pinned CLI on PATH" }
    Files.createDirectory(
        options.output,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    val build = Build(options.sha, options.version, options.number)
    // The branch is unused by snapshot; do not fetch, allocate versions, or contact APIs.
    var profileUuid = ""
    val files =
        GitSource(source, config, commands, "").snapshot(options.sha) { checkout ->
            for (relative in listOfNotNull(config.ios.project, config.ios.workspace)) {
                require(
                    !Path.of(relative).isAbsolute &&
                        checkout.resolve(relative).toRealPath().startsWith(checkout.toRealPath())
                ) {
                    "Archive project/workspace must be inside the committed source snapshot"
                }
            }
            withExistingSigningIdentity(
                config,
                commands,
                environment,
                options.profile,
                options.certificate,
            ) { identity ->
                profileUuid = identity.profile
                Signing(config, commands, env)
                    .archiveWithIdentity(checkout, options.output, build, identity)
            }
        }
    val evidence =
        LocalArchiveEvidence(
            options.sha,
            digest(text.toByteArray()),
            options.version,
            options.number,
            Instant.now().toString(),
            options.certificate,
            profileUuid,
            xcode,
            asc,
            files.ipa,
            files.symbols,
            if (files.symbols == null) "Archive contained no dSYMs" else null,
        )
    // Publish success only after signing-profile and source-worktree cleanup has succeeded.
    val pending = options.output.resolve("local-archive.json.tmp")
    pending.writeText(json.encodeToString(evidence) + "\n")
    Files.move(pending, options.output.resolve("local-archive.json"), ATOMIC_MOVE)
    println(
        "Local archive verified: ${options.output.resolve(files.ipa.name)}. Verification not run; not eligible for automated delivery."
    )
}
