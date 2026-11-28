package delivery

import kotlinx.serialization.Serializable

@Serializable
enum class PackageOrigin {
    CI_VERIFIED,
    MANUAL,
}

@Serializable
data class PackageProducer(val workflow: String, val runId: String, val attempt: Int) {
    init {
        require(Regex("\\.github/workflows/[A-Za-z0-9_.-]+\\.ya?ml").matches(workflow)) {
            "Expected a producing workflow path"
        }
        require(Regex("[1-9][0-9]*").matches(runId) && attempt > 0) {
            "Expected a producing run ID and positive attempt"
        }
    }
}

@Serializable
data class PreparationRequest(
    val repository: String,
    val bundleId: String,
    val sha: String,
    val ref: String,
    val origin: PackageOrigin,
    val producer: PackageProducer,
    val rebuild: Int = 1,
) {
    init {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) {
            "Expected owner/repository"
        }
        require(Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+").matches(bundleId)) {
            "Expected an app bundle identifier"
        }
        require(Regex("[0-9a-f]{40}").matches(sha)) {
            "Preparation requires a full source commit SHA"
        }
        require(
            ref.startsWith("refs/heads/") &&
                ref.length > 11 &&
                ref.none { it.isWhitespace() || it.isISOControl() } &&
                ".." !in ref
        ) {
            "Expected a source branch ref"
        }
        require(rebuild > 0) { "Expected a positive rebuild generation" }
    }

    // A rerun's attempt changes; the operation and selected source must not.
    val id: String
        get() =
            digest(
                listOf(repository, bundleId, producer.workflow, producer.runId, rebuild.toString())
                    .joinToString("\n")
                    .toByteArray()
            )

    fun resumes(original: PreparationRequest) =
        copy(producer = producer.copy(attempt = original.producer.attempt)) == original
}

@Serializable
data class Preparation(
    val request: PreparationRequest,
    val version: MarketingVersion,
    val number: BuildNumber,
) {
    val id: String
        get() = request.id

    val workflow: String
        get() = "https://github.com/${request.repository}/actions/runs/${request.producer.runId}"

    fun build() = Build(request.sha, version, number, workflow, preparation = this)
}

@Serializable
data class PreparationRecord(val preparation: Preparation, val manifest: ArtifactManifest? = null) {
    init {
        if (manifest != null)
            require(manifest.preparation == preparation) {
                "Package belongs to another preparation"
            }
    }
}

data class PreparationSnapshot(val revision: String?, val records: List<PreparationRecord>) {
    init {
        require(records.map { it.preparation.id }.distinct().size == records.size) {
            "Duplicate preparation IDs"
        }
        require(records.map { it.preparation.number }.distinct().size == records.size) {
            "Duplicate reserved build numbers"
        }
    }
}

interface PreparationStore {
    fun read(): PreparationSnapshot

    // Atomic compare-and-set. False means another writer advanced the ledger.
    fun write(expected: PreparationSnapshot, records: List<PreparationRecord>): Boolean
}

// Normalized Apple order also accepts old/external forms such as 234, 234.1.0 and leading zeros.
internal fun appleBuildOrder(value: String): Long {
    val components = value.split('.')
    require(components.size in 1..3 && components.all { Regex("[0-9]+").matches(it) }) {
        "Cannot order existing Apple build number: $value"
    }
    val parts = components.map {
        requireNotNull(it.toIntOrNull()) { "Invalid build number: $value" }
    }
    require(parts[0] in 0..9999 && parts.drop(1).all { it in 0..99 }) {
        "Existing build exceeds supported Apple range: $value"
    }
    return parts[0] * 10000L + parts.getOrElse(1) { 0 } * 100L + parts.getOrElse(2) { 0 }
}

fun nextPreparationNumber(occupied: Set<String>): BuildNumber {
    var next = maxOf(10001L, (occupied.maxOfOrNull(::appleBuildOrder) ?: 10000L) + 1)
    // A nonzero third component distinguishes reservations from every legacy count.attempt.
    if (next % 100 == 0L) next++
    require(next <= 99_999_999L) { "Apple build-number range exhausted; no number was reserved" }
    return BuildNumber("${next / 10000}.${next / 100 % 100}.${next % 100}")
}

internal fun nextCommitCountBuildNumber(count: Int, occupied: Set<String>): BuildNumber {
    val first = Policy.buildNumber(count, 1)
    val highest = occupied.maxOfOrNull(::appleBuildOrder) ?: return first
    if (highest < appleBuildOrder(first.value)) return first

    require(highest / 10000 == count.toLong()) {
        "Source commit count $count is below the existing build-number floor"
    }
    val attempt = (highest % 10000 / 100).toInt() + 1
    return Policy.buildNumber(count, attempt)
}

class PreparationReservations(
    private val store: PreparationStore,
    private val occupied: () -> Set<String>,
) {
    fun reserve(
        request: PreparationRequest,
        version: MarketingVersion,
        commitCount: (() -> Int)? = null,
    ): Preparation {
        val count by lazy { commitCount?.invoke() }
        return change { snapshot ->
            val existing = snapshot.records.find { it.preparation.id == request.id }
            if (existing != null) {
                require(request.resumes(existing.preparation.request)) {
                    "Preparation source/origin changed; use a new explicit rebuild generation"
                }
                existing
            } else {
                val taken = occupied() + snapshot.records.map { it.preparation.number.value }
                val number =
                    count?.let { nextCommitCountBuildNumber(it, taken) }
                        ?: nextPreparationNumber(taken)
                PreparationRecord(Preparation(request, version, number))
            }
        }
            .preparation
    }

    // Seal before artifact publication. A competing builder cannot publish different bytes under
    // the same number. A sealed package that expired or was never retained must not be rebuilt.
    fun seal(preparation: Preparation, manifest: ArtifactManifest): PreparationRecord {
        require(manifest.schema == 2 && manifest.preparation == preparation) {
            "Expected this preparation's schema-2 manifest"
        }
        return change { snapshot ->
            val existing =
                requireNotNull(
                    snapshot.records.singleOrNull { it.preparation.id == preparation.id }
                ) {
                    "Preparation was not reserved in this ledger"
                }
            require(existing.preparation == preparation) {
                "Preparation was not reserved in this ledger"
            }
            require(existing.manifest == null || existing.manifest == manifest) {
                "Preparation already sealed different package bytes; recover the retained package or request a new build"
            }
            existing.copy(manifest = manifest)
        }
    }

    private fun change(update: (PreparationSnapshot) -> PreparationRecord): PreparationRecord {
        repeat(32) {
            val snapshot = store.read()
            val record = update(snapshot)
            if (record in snapshot.records) return record
            val records =
                snapshot.records.filterNot { it.preparation.id == record.preparation.id } + record
            try {
                if (store.write(snapshot, records)) return record
            } catch (error: Exception) {
                // Never blindly replay a write: a lost response may already have committed it.
                if (
                    store.read().records.find { it.preparation.id == record.preparation.id } ==
                        record
                )
                    return record
                throw error
            }
        }
        error(
            "Preparation ledger remained contended; retry the same operation without changing its identity"
        )
    }
}
