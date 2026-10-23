package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ArtifactFile(val name: String, val sha256: String, val bytes: Long)

@Serializable
data class ArtifactManifest(
    val schema: Int = 1,
    val sha: String,
    val version: MarketingVersion,
    val number: BuildNumber,
    val workflow: String,
    val artifactName: String,
    val ipa: ArtifactFile,
    val symbols: ArtifactFile? = null,
    val symbolsAbsentReason: String? = null,
    val storageWorkflow: String? = null,
) {
    init {
        require(schema == 1) { "$artifactName manifest: expected schema 1; found $schema" }
        require((symbols == null) == (symbolsAbsentReason != null)) {
            "$artifactName manifest: provide symbols or symbolsAbsentReason, exactly one; found symbols=${symbols != null}, symbolsAbsentReason=${symbolsAbsentReason != null}"
        }
    }
}

data class RetainedPackage(val ipa: Path, val manifest: ArtifactManifest)

// Retained builds are named by build number alone. It is unique per commit, so a build is found
// whatever marketing version was open when it was made; its manifest records the version.
fun artifactName(number: BuildNumber) = "ios-build-$number"

// One retained build as Actions lists it. Its number stays taken after Actions expires it.
data class RetainedBuild(val name: String, val run: String, val expired: Boolean) {
    val number: BuildNumber
        get() = BuildNumber(name.removePrefix("ios-build-"))
}

interface BuildArtifacts {
    // Any run's retained build with this name.
    fun find(name: String): RetainedBuild?

    // The newest build this run retained. An Actions rerun shares its run's artifacts with
    // earlier attempts, so this may be an earlier attempt's build.
    fun retainedByThisRun(): RetainedBuild?

    fun download(build: RetainedBuild, output: Path): RetainedPackage
}

// The retained build with this number, from any run.
fun BuildArtifacts.retained(number: BuildNumber) = find(artifactName(number))

// Checks retained bytes against their own manifest before any upload.
fun validatePackage(manifest: ArtifactManifest, evidence: Map<String, ByteArray>) {
    require(
        json.decodeFromString<ArtifactManifest>(
            evidence.getValue("manifest.json").decodeToString()
        ) == manifest
    ) {
        "Retained manifest differs from the build"
    }
    listOfNotNull(manifest.ipa, manifest.symbols).forEach { file ->
        require(
            file.name == Path.of(file.name).fileName.toString() && file.name !in setOf(".", "..")
        ) {
            "Invalid artifact filename"
        }
        val bytes = evidence.getValue(file.name)
        require(bytes.size.toLong() == file.bytes && digest(bytes) == file.sha256) {
            "Artifact checksum mismatch: ${file.name}"
        }
    }
}

fun localPackage(build: Build, output: Path): Map<String, ByteArray> {
    val manifest =
        requireNotNull(build.artifact) {
            "Build ${build.number} at $output has no archive manifest; archive the build before retaining its package"
        }
    require(
        manifest.sha == build.sha &&
            manifest.version == build.version &&
            manifest.number == build.number &&
            manifest.ipa.sha256 == build.ipaSha256 &&
            manifest.ipa.bytes == build.ipaBytes
    ) {
        "Artifact identity differs from the build"
    }
    return (listOfNotNull(manifest.ipa.name, manifest.symbols?.name) + "manifest.json")
        .associateWith {
            require(it == Path.of(it).fileName.toString() && it !in setOf(".", "..")) {
                "Invalid artifact filename"
            }
            output.resolve(it).readBytes()
        }
        .also { validatePackage(manifest, it) }
}

class GitHubBuildArtifacts(private val github: GitHub, private val runId: String) : BuildArtifacts {
    private fun retained(row: JsonObject, run: String) =
        RetainedBuild(
            row.string("name"),
            run,
            row["expired"]?.jsonPrimitive?.booleanOrNull ?: false,
        )

    override fun find(name: String) =
        github
            .api("actions/artifacts?name=$name")!!
            .jsonObject
            .getValue("artifacts")
            .jsonArray
            .map { it.jsonObject }
            .sortedBy { it["expired"]?.jsonPrimitive?.booleanOrNull ?: false }
            .firstOrNull()
            ?.let { row ->
                retained(row, row.getValue("workflow_run").jsonObject.string("id"))
            }

    override fun retainedByThisRun() =
        github
            .pages("actions/runs/$runId/artifacts", "artifacts")
            .filter { it.string("name").startsWith("ios-build-") }
            .map { retained(it, runId) }
            .maxByOrNull { it.number.attempt }

    override fun download(build: RetainedBuild, output: Path): RetainedPackage {
        val name = build.name
        val directory = createTempDirectory("retained-package-")
        try {
            github.commands.run(
                listOf(
                    "gh",
                    "run",
                    "download",
                    build.run,
                    "--repo",
                    github.repo,
                    "--name",
                    name,
                    "--dir",
                    directory.toString(),
                ),
                env = toolEnvironment(github.env, "GH_TOKEN", "GITHUB_TOKEN"),
            )
            fun read(file: String): ByteArray {
                val matches =
                    java.nio.file.Files.walk(directory).use { paths ->
                        paths
                            .filter { it.isRegularFile() && it.fileName.toString() == file }
                            .toList()
                    }
                require(matches.size == 1) { "Missing or ambiguous retained file: $file" }
                return matches.single().readBytes()
            }
            val manifest =
                json.decodeFromString<ArtifactManifest>(read("manifest.json").decodeToString())
            require(manifest.artifactName == name) { "Retained manifest names another build" }
            val evidence =
                (listOfNotNull(manifest.ipa.name, manifest.symbols?.name) + "manifest.json")
                    .associateWith(::read)
            validatePackage(manifest, evidence)
            output.createDirectories()
            evidence.forEach { (file, bytes) -> output.resolve(file).writeBytes(bytes) }
            return RetainedPackage(output.resolve(manifest.ipa.name), manifest)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
