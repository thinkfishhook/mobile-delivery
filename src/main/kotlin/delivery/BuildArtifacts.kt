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
    val preparation: Preparation? = null,
) {
    init {
        require(schema in 1..2) { "$artifactName manifest: expected schema 1 or 2; found $schema" }
        require((schema == 2) == (preparation != null)) {
            "Schema 2 requires preparation provenance; schema 1 must remain legacy"
        }
        require(schema != 1 || number.legacyCount != null) {
            "Reserved build numbers cannot downgrade to a provenance-free legacy manifest"
        }
        if (preparation != null) {
            require(
                sha == preparation.request.sha &&
                    version == preparation.version &&
                    number == preparation.number &&
                    (workflow == preparation.workflow || producingAttempt != null) &&
                    artifactName == artifactName(number)
            ) {
                "Manifest identity differs from its reservation"
            }
            require(storageWorkflow == null) {
                "Schema-2 package storage must retain its originating run"
            }
            listOfNotNull(ipa, symbols).forEach {
                require(it.bytes > 0 && Regex("[0-9a-f]{64}").matches(it.sha256)) {
                    "Invalid prepared package digest or size"
                }
            }
        }
        require((symbols == null) == (symbolsAbsentReason != null)) {
            "$artifactName manifest: provide symbols or symbolsAbsentReason, exactly one; found symbols=${symbols != null}, symbolsAbsentReason=${symbolsAbsentReason != null}"
        }
        val names = listOfNotNull(ipa.name, symbols?.name)
        require(
            names.distinct().size == names.size &&
                names.all {
                    it != "manifest.json" &&
                        it == Path.of(it).fileName.toString() &&
                        it !in setOf(".", "..") &&
                        '\\' !in it
                }
        ) {
            "Invalid or colliding package filenames"
        }
        require(symbolsAbsentReason == null || symbolsAbsentReason.isNotBlank()) {
            "Expected an explicit symbols absence reason"
        }
    }

    // Older schema-2 packages used the run URL. New packages name the attempt that
    // actually produced their bytes, which can follow an interrupted reservation.
    val producingAttempt: Int?
        get() = preparation?.let { reservation ->
            if (workflow == reservation.workflow) reservation.request.producer.attempt
            else
                workflow
                    .takeIf { it.startsWith("${reservation.workflow}/attempts/") }
                    ?.removePrefix("${reservation.workflow}/attempts/")
                    ?.takeIf { Regex("[1-9][0-9]*").matches(it) }
                    ?.toIntOrNull()
                    ?.takeIf { it >= reservation.request.producer.attempt }
        }
}

data class RetainedPackage(val ipa: Path, val manifest: ArtifactManifest)

// Retained builds are named by build number alone. Reservations make it unique, so it is found
// whatever marketing version was open when it was made; its manifest records the version.
fun artifactName(number: BuildNumber) = "ios-build-$number"

// One retained build as Actions lists it. Its number stays taken after Actions expires it.
data class RetainedBuild(val name: String, val run: String, val expired: Boolean) {
    val number: BuildNumber
        get() = BuildNumber(name.removePrefix("ios-build-"))
}

interface BuildArtifacts {
    fun numbers(): Set<BuildNumber> =
        error("Artifact inventory is required before reserving a build number")

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
            manifest.preparation == build.preparation &&
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

    override fun numbers() =
        github
            .pages("actions/artifacts", "artifacts")
            .filter { it.string("name").startsWith("ios-build-") }
            .map { BuildNumber(it.string("name").removePrefix("ios-build-")) }
            .toSet()

    override fun find(name: String): RetainedBuild? {
        val rows =
            github.pages("actions/artifacts?name=$name", "artifacts").filter {
                it.string("name") == name
            }
        require(rows.size <= 1) { "Ambiguous retained build: $name" }
        return rows.singleOrNull()?.let {
            retained(it, it.getValue("workflow_run").jsonObject.string("id"))
        }
    }

    override fun retainedByThisRun(): RetainedBuild? {
        val builds =
            github
                .pages("actions/runs/$runId/artifacts", "artifacts")
                .filter { it.string("name").startsWith("ios-build-") }
                .map { retained(it, runId) }
        require(builds.map { it.name }.distinct().size == builds.size) {
            "Ambiguous retained packages for this run"
        }
        return builds.maxByOrNull { appleBuildOrder(it.number.value) }
    }

    override fun download(build: RetainedBuild, output: Path): RetainedPackage {
        require(!build.expired) { "Retained build ${build.name} expired" }
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
