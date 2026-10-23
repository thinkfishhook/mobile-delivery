package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.json.*

// GitHub Releases on receipt tags, as far as keeping symbols needs them.
interface ReleaseAssets {
    // Each asset's SHA-256 by name, or null when the tag has no Release.
    fun assets(tag: String): Map<String, String>?

    fun create(tag: String, title: String, notes: String, files: List<Path>)

    fun upload(tag: String, files: List<Path>)
}

// Keeps the dSYMs and manifest of every build submitted to the App Store, in a Release on the
// build's TestFlight receipt tag. Actions deletes a retained build after 90 days, but a version
// stays on devices far longer and matching dSYMs cannot be rebuilt. The IPA stays with Apple.
class ReleaseSymbols(
    private val artifacts: BuildArtifacts,
    private val releases: ReleaseAssets,
    private val workspace: Path,
) {
    // Why this build's symbols cannot be kept, or null when they can.
    fun problem(number: BuildNumber): String? =
        problem(artifactName(number), artifacts.retained(number))

    private fun problem(name: String, retained: RetainedBuild?) =
        when {
            retained == null -> "$name was never retained, so its dSYMs cannot be kept"
            retained.expired -> "Actions has expired $name, so its dSYMs cannot be kept"
            else -> null
        }

    fun keep(receipt: String, version: MarketingVersion, number: BuildNumber) {
        val name = artifactName(number)
        val retained = artifacts.retained(number)
        problem(name, retained)?.let { error("$it; nothing was submitted") }
        // The download checks every file, the IPA included, against the manifest.
        val directory = workspace.resolve(name)
        val manifest =
            artifacts
                .download(
                    requireNotNull(retained) {
                        "Expected retained build $name before keeping release symbols; no artifact was found"
                    },
                    directory,
                )
                .manifest
        val files = listOfNotNull("manifest.json", manifest.symbols?.name).map(directory::resolve)
        val wanted = files.associate { it.name to digest(it.readBytes()) }
        val kept = releases.assets(receipt)
        val write = attemptEffect {
            val different =
                wanted.keys.filter { kept?.get(it).let { sha -> sha != null && sha != wanted[it] } }
            require(different.isEmpty()) {
                "The Release on $receipt holds a different ${different.joinToString()}"
            }
            val notes =
                "dSYMs and build manifest of $version/$number (commit ${manifest.sha}), kept " +
                    "because it was submitted to the App Store. The IPA stays with Apple."
            when {
                kept == null -> releases.create(receipt, "Symbols $version ($number)", notes, files)
                wanted.keys.any { it !in kept } ->
                    releases.upload(receipt, files.filter { it.name !in kept })
            }
        }
        // A lost response may still have written everything, so the Release itself decides.
        val after = releases.assets(receipt)
        if (after == null || wanted.any { (file, sha) -> after[file] != sha })
            throw write.exceptionOrNull()
                ?: IllegalStateException("The Release on $receipt does not hold $name's symbols")
    }
}

class GitHubReleaseAssets(private val github: GitHub) : ReleaseAssets {
    override fun assets(tag: String) =
        github.api("releases/tags/$tag", missing = true, receiptToken = true)?.let { release ->
            release.jsonObject.getValue("assets").jsonArray.associate {
                val asset = it.jsonObject
                asset.string("name") to
                    asset["digest"]?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("sha256:")
            }
        }

    // --verify-tag: the receipt tag must already exist; a Release never creates or moves one.
    override fun create(tag: String, title: String, notes: String, files: List<Path>) {
        github.cli(
            listOf("release", "create", tag) +
                files.map { it.toString() } +
                listOf("--verify-tag", "--title", title, "--notes", notes, "--latest=false"),
            receiptToken = true,
        )
    }

    override fun upload(tag: String, files: List<Path>) {
        github.cli(
            listOf("release", "upload", tag) + files.map { it.toString() },
            receiptToken = true,
        )
    }
}
