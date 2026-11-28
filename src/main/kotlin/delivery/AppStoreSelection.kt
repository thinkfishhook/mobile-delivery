package delivery

internal fun testFlightTag(version: String, number: String) = "deploy/testflight/$version/$number"

object StoreStates {
    // Apple has shipped these versions; only a newer build can follow them.
    val released =
        setOf(
            "READY_FOR_SALE",
            "READY_FOR_DISTRIBUTION",
            "REPLACED_WITH_NEW_VERSION",
            "REMOVED_FROM_SALE",
            "DEVELOPER_REMOVED_FROM_SALE",
        )

    // Drafts a release may reuse. One with a build attached means an earlier submission
    // failed or its outcome is uncertain.
    val editable =
        setOf(
            "PREPARE_FOR_SUBMISSION",
            "DEVELOPER_REJECTED",
            "REJECTED",
            "METADATA_REJECTED",
            "INVALID_BINARY",
        )

    // Not yet received for review: drafts, and a version added to a review submission that was
    // never sent, which App Store Connect shows as READY_FOR_REVIEW.
    val unsent = editable + "READY_FOR_REVIEW"

    // Submissions a person can remove from review in App Store Connect.
    val inReview = setOf("WAITING_FOR_REVIEW", "IN_REVIEW", "READY_FOR_REVIEW")

    // The shipped version with the highest build; its rollout is the current one.
    fun lastRelease(versions: List<StoreVersion>): StoreVersion? {
        val shipped = versions.filter {
            MarketingVersion.accepts(it.version) && it.state in released
        }
        require(shipped.all { it.buildNumber != null }) {
            "Apple did not report the build of a released version; cannot compare builds"
        }
        return shipped.maxByOrNull { StoreFacts.buildOrder(it.buildNumber!!) }
    }
}

// Everything an App Store release decides from: Apple's versions and builds, and which builds
// have TestFlight receipts. Nothing here comes from the delivery record.
class StoreFacts(
    val versions: List<StoreVersion>,
    val builds: List<TestFlightBuild>,
    val receipts: Set<String>,
    val holds: List<Hold> = emptyList(),
    val submissions: List<ReviewSubmission> = emptyList(),
    val unqualifiedReceipts: Set<String> = emptySet(),
    val preparations: Map<String, Preparation> = emptyMap(),
) {
    val ignoredVersions =
        versions.filterNot { MarketingVersion.accepts(it.version) }.map { it.version }.distinct()
    private val comparableVersions = versions.filter { MarketingVersion.accepts(it.version) }

    // Apple keeps one review submission open per platform, and a new release cannot start
    // beside it. Anything short of COMPLETE counts, including states this tool does not know.
    val openSubmissions = submissions.filter { it.state != "COMPLETE" }

    // Anything neither shipped nor editable, including states this tool does not know.
    val unresolved = versions.filter {
        it.state !in StoreStates.released && it.state !in StoreStates.editable
    }

    val failedSubmissions = versions.filter {
        it.state in StoreStates.editable && it.buildId != null
    }

    val lastRelease = StoreStates.lastRelease(versions)

    // Marketing versions already submitted or shipped cannot carry another App Store release.
    val usedVersions =
        comparableVersions.filter { it.state !in StoreStates.editable }.map { it.version }.toSet()

    fun candidates() =
        builds
            .filter { MarketingVersion.accepts(it.version) }
            .map {
                val tag = testFlightTag(it.version, it.number)
                Candidate(
                    it,
                    tag.takeIf { it in receipts && it !in unqualifiedReceipts },
                    preparations[tag],
                )
            }

    fun newerThanLastRelease(candidate: Candidate): Boolean {
        val release = lastRelease ?: return true
        val number =
            requireNotNull(release.buildNumber) {
                "Apple did not report the build of released version ${release.version}"
            }
        val lastPreparation = preparations[testFlightTag(release.version, number)]
        val candidateCount = legacyCommitCount(candidate.build.number, candidate.preparation)
        val releasedCount = legacyCommitCount(number, lastPreparation)
        // An upload retry of the same legacy commit is not another public release.
        if (candidateCount != null && releasedCount != null) return candidateCount > releasedCount
        return buildOrder(candidate.build.number) > buildOrder(number)
    }

    companion object {
        fun buildOrder(number: String): Long = runCatching {
            appleBuildOrder(number)
        }
            .getOrDefault(0)

        private fun legacyCommitCount(number: String, preparation: Preparation?): Int? =
            number
                .takeIf { preparation == null && it.count { char -> char == '.' } < 2 }
                ?.substringBefore('.')
                ?.toIntOrNull()
    }
}

data class Candidate(
    val build: TestFlightBuild,
    val receipt: String?,
    val preparation: Preparation? = null,
)

class EligibilityRule(val name: String, val allows: (Candidate) -> Boolean)

// The per-build rules for the next App Store release, cheapest first.
fun eligibilityRules(
    facts: StoreFacts,
    held: (Candidate) -> Boolean = { false },
): List<EligibilityRule> {
    return listOf(
        EligibilityRule("has a TestFlight receipt") { it.receipt != null },
        EligibilityRule("is not expired or invalid in Apple") {
            it.build.processingState == "VALID" && !it.build.expired
        },
        EligibilityRule("is newer than the last App Store release", facts::newerThanLastRelease),
        EligibilityRule("has a marketing version not yet on the App Store") {
            it.build.version !in facts.usedVersions
        },
        EligibilityRule("is not held") { !held(it) },
    )
}

// Newest build first. Each build's rules run once, in order, and stop at the first it fails, so
// an expensive rule runs only for builds that could still be selected.
class Selection(facts: StoreFacts, private val rules: List<EligibilityRule>) {
    val candidates =
        facts.candidates().sortedByDescending { StoreFacts.buildOrder(it.build.number) }

    private val failures = mutableMapOf<Candidate, String?>()

    // The first rule this build fails, or null when it is eligible.
    fun failure(candidate: Candidate): String? =
        if (candidate in failures) failures[candidate]
        else rules.firstOrNull { !it.allows(candidate) }?.name.also { failures[candidate] = it }

    val selected: Candidate? by lazy { candidates.firstOrNull { failure(it) == null } }
}

fun selectBuild(facts: StoreFacts, rules: List<EligibilityRule> = eligibilityRules(facts)) =
    Selection(facts, rules).selected
