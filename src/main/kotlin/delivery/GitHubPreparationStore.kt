package delivery

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
private data class PreparationLedger(val schema: Int = 1, val records: List<PreparationRecord>) {
    init {
        require(schema == 1) { "Unsupported preparation ledger schema: $schema" }
    }
}

// A dedicated data-only branch, not a consumer source commit or a deployment receipt. Git's
// non-forced fast-forward update serializes sibling writes from independent runners.
class GitHubPreparationStore(private val github: GitHub) : PreparationStore {
    private val branch = "mobile-delivery-state"

    override fun read(): PreparationSnapshot {
        val ref =
            github.api("git/ref/heads/$branch", missing = true)?.jsonObject
                ?: return PreparationSnapshot(null, emptyList())
        val target = ref.getValue("object").jsonObject
        require(target.string("type") == "commit") { "Preparation ledger ref is not a commit" }
        val revision = target.string("sha")
        val commit = github.api("git/commits/$revision")!!.jsonObject
        val tree =
            github.api("git/trees/${commit.getValue("tree").jsonObject.string("sha")}")!!.jsonObject
        val file =
            tree
                .getValue("tree")
                .jsonArray
                .map { it.jsonObject }
                .single { it.string("path") == "reservations.json" }
        require(file.string("type") == "blob" && file.string("mode") == "100644") {
            "Expected a regular preparation ledger file"
        }
        val blob = github.api("git/blobs/${file.string("sha")}")!!.jsonObject
        require(blob.string("sha") == file.string("sha") && blob.string("encoding") == "base64") {
            "Preparation blob differs from the observed ledger revision"
        }
        val ledger =
            json.decodeFromString<PreparationLedger>(
                Base64.getMimeDecoder().decode(blob.string("content")).decodeToString()
            )
        return PreparationSnapshot(revision, ledger.records)
    }

    override fun write(expected: PreparationSnapshot, records: List<PreparationRecord>): Boolean {
        PreparationSnapshot(expected.revision, records)
        require(
            expected.records.all { old ->
                records.any {
                    it.preparation == old.preparation &&
                        (old.manifest == null || old.manifest == it.manifest)
                }
            }
        ) {
            "Preparation ledger cannot drop identities or change sealed bytes"
        }
        val tree =
            github
                .api(
                    "git/trees",
                    buildJsonObject {
                        putJsonArray("tree") {
                            addJsonObject {
                                put("path", "reservations.json")
                                put("mode", "100644")
                                put("type", "blob")
                                put(
                                    "content",
                                    json.encodeToString(PreparationLedger(records = records)),
                                )
                            }
                        }
                    },
                )!!
                .jsonObject
                .string("sha")
        val commit =
            github
                .api(
                    "git/commits",
                    buildJsonObject {
                        put("message", "Record signed-package preparation identity")
                        put("tree", tree)
                        putJsonArray("parents") { expected.revision?.let { add(it) } }
                    },
                )!!
                .jsonObject
                .string("sha")
        try {
            if (expected.revision == null) {
                github.api(
                    "git/refs",
                    buildJsonObject {
                        put("ref", "refs/heads/$branch")
                        put("sha", commit)
                    },
                )
            } else {
                github.api(
                    "git/refs/heads/$branch",
                    buildJsonObject {
                        put("sha", commit)
                        put("force", false)
                    },
                    method = "PATCH",
                )
            }
        } catch (error: Exception) {
            val after = read()
            if (after.records == records) return true // Lost successful ref response.
            if (
                error is GitHubApiError &&
                    error.status in setOf(409, 422) &&
                    after.revision != expected.revision
            )
                return false
            throw error
        }
        return true
    }
}

// Include expired retained artifacts and all receipt/build/upload numbers, not just successful
// deployments: failed or reserved work must never free a number for another source.
fun occupiedPreparationNumbers(
    apple: Apple,
    tags: ReceiptTags,
    artifacts: BuildArtifacts,
): Set<String> =
    apple.testFlightBuilds().map { it.number }.toSet() +
        apple.uploads().map { it.number } +
        apple.storeVersions().mapNotNull { it.buildNumber } +
        requireReceiptNames("deploy/testflight/", tags.names("deploy/testflight/")).map {
            it.substringAfterLast('/')
        } +
        artifacts.numbers().map { it.value }
