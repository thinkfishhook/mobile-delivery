package delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// Receipt tags as the App Store release and sweeps read and write them.
interface ReceiptTags {
    fun names(prefix: String): Set<String>

    fun commit(tag: String): String

    fun write(tag: String, sha: String, receipt: Receipt)
}

class InvalidReceiptName(message: String) : IllegalArgumentException(message)

// The tool writes only `<prefix><YY.MM.RR>/<count>.<attempt>`. Anything else under a receipt
// prefix was made by hand and would be misread, so the run stops until a person deletes it.
fun requireReceiptNames(prefix: String, names: Set<String>): Set<String> = names.onEach { tag ->
    val parts = tag.removePrefix(prefix).split('/')
    val valid =
        parts.size == 2 &&
            runCatching {
                MarketingVersion(parts[0])
                BuildNumber(parts[1])
            }
                .isSuccess
    if (!valid)
        throw InvalidReceiptName(
            "$tag is not a receipt the delivery tool writes (${prefix}<YY.MM.RR>/<count>.<attempt>). Delete it, then rerun: git push origin :refs/tags/$tag"
        )
}

class GitHubReceiptTags(private val github: GitHub) : ReceiptTags {
    internal var onCredentialSuccess: () -> Unit = {}
    internal var onCredentialProblem: (CredentialProblem) -> Unit = {}

    // matching-refs ignores paging parameters and always returns every match.
    override fun names(prefix: String): Set<String> =
        github
            .api("git/matching-refs/tags/$prefix")!!
            .jsonArray
            .map { it.jsonObject.string("ref").removePrefix("refs/tags/") }
            .toSet()

    override fun commit(tag: String): String {
        val target = github.api("git/ref/tags/$tag")!!.jsonObject.getValue("object").jsonObject
        require(target.string("type") == "tag") { "Receipt $tag is not an annotated tag" }
        return github
            .api("git/tags/${target.string("sha")}")!!
            .jsonObject
            .getValue("object")
            .jsonObject
            .string("sha")
    }

    override fun write(tag: String, sha: String, receipt: Receipt) {
        try {
            writeReceipt(tag, sha, receipt)
        } catch (error: GitHubApiError) {
            val problem =
                receiptCredentialProblem(error, github.receiptTokenExpiration) ?: throw error
            onCredentialProblem(problem)
            throw problem
        }
    }

    private fun writeReceipt(tag: String, sha: String, receipt: Receipt) {
        val manifest = json.encodeToJsonElement(receipt)
        // Same commit and same Apple build; other fields may differ, because a receipt repaired
        // by a sweep holds only Apple facts.
        fun validate(ref: JsonObject) {
            val target = ref.getValue("object").jsonObject
            require(target.string("type") == "tag") { "Receipt collision: $tag" }
            val existing =
                github.api("git/tags/${target.string("sha")}", receiptToken = true)!!.jsonObject
            val recorded = runCatching {
                json.parseToJsonElement(existing.string("message")).jsonObject
            }
                .getOrNull()
            fun identity(receipt: JsonObject?) =
                listOf("sha", "version", "number", "asc_build_id").map {
                    receipt?.get(it)?.jsonPrimitive?.contentOrNull
                }
            require(
                existing.getValue("object").jsonObject.string("sha") == sha &&
                    identity(recorded) == identity(manifest.jsonObject)
            ) {
                "Receipt collision: $tag"
            }
        }
        val ref = github.api("git/ref/tags/$tag", missing = true, receiptToken = true)?.jsonObject
        if (ref != null) {
            validate(ref)
            return
        }
        val annotated =
            github
                .api(
                    "git/tags",
                    buildJsonObject {
                        put("tag", tag)
                        put("object", sha)
                        put("type", "commit")
                        put("message", manifest.toString())
                    },
                    receiptToken = true,
                )!!
                .jsonObject
        try {
            github.api(
                "git/refs",
                buildJsonObject {
                    put("ref", "refs/tags/$tag")
                    put("sha", annotated.string("sha"))
                },
                receiptToken = true,
            )
            onCredentialSuccess()
        } catch (error: Exception) {
            // Authentication failures cannot be repaired by reading the same ref again.
            if (
                error is GitHubApiError &&
                    receiptCredentialProblem(error, github.receiptTokenExpiration) != null
            )
                throw error
            val written =
                github.api("git/ref/tags/$tag", missing = true, receiptToken = true)?.jsonObject
                    ?: throw error
            validate(written)
        }
    }
}

@Serializable
data class Receipt(
    val sha: String,
    val version: MarketingVersion,
    val number: BuildNumber,
    @SerialName("asc_build_id") val ascBuildId: String,
    // TestFlight receipts name the delivering run. App Store receipts hold only Apple facts, so
    // a sweep can rewrite an identical one.
    val workflow: String? = null,
    @SerialName("ipa_sha256") val ipaSha256: String? = null,
    @SerialName("ipa_bytes") val ipaBytes: Long? = null,
    @SerialName("distributed_at") val distributedAt: String? = null,
    val xcode: String? = null,
    val asc: String? = null,
    @SerialName("asc_version_id") val ascVersionId: String? = null,
)
