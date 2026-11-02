package delivery

import java.time.Clock

data class ReleaseRequest(val scheduled: Boolean, val reason: String?, val phased: Boolean)

// App Review keeps a rejected submission open (UNRESOLVED_ISSUES), and Apple allows one open
// submission, so a retry starts in App Store Connect. Promote releases only after a first release
// has shipped, so until then the person finishes the version there.
private fun retry(facts: StoreFacts) =
    if (facts.lastRelease == null) "Fix it and resubmit it in App Store Connect. $FIRST"
    else
        "Fix it in App Store Connect. If its review submission is still open there (Unresolved Issues), cancel it. Then dispatch promote with a reason."

private const val FIRST =
    "No version has been released yet, so this tool takes over only after the first release ships."

data class ReleaseResult(
    val outcome: String,
    val action: String? = null,
    val version: String? = null,
    val build: String? = null,
)

// A blocker skips a scheduled release, warning with `action`, and stops a manual one with `why`.
private fun ReleaseRequest.stop(skipped: String, why: String, action: String? = why) =
    if (scheduled) ReleaseResult("skipped: $skipped", action) else error(why)

class AppStoreRelease(
    private val config: DeliveryConfig,
    private val apple: Apple,
    private val tags: ReceiptTags,
    private val source: Source,
    private val symbols: ReleaseSymbols,
    private val env: Map<String, String>,
    private val clock: Clock = Clock.systemUTC(),
    private val hints: WorkflowHints = WorkflowHints(env),
    private val holdIssues: HoldIssues = HoldIssues { emptyList() },
) {
    fun facts(): StoreFacts {
        val receipts = tags.names("deploy/testflight/")
        val current =
            StoreFacts(
                apple.storeVersions(),
                apple.testFlightBuilds(),
                receipts,
                submissions = apple.reviewSubmissions(),
            )
        val targets by lazy {
            val selection = Selection(current, eligibilityRules(current))
            val candidates =
                selection.candidates
                    .filter { selection.failure(it) == null }
                    .mapNotNull { it.receipt }
            // Holds also protect code already in review or actively rolling out.
            val shipping =
                (current.unresolved +
                        listOfNotNull(
                            current.lastRelease?.takeIf { it.rollout?.state == "ACTIVE" }
                        ))
                    .map { "deploy/testflight/${it.version}/${it.buildNumber}" }
                    .filter { it in receipts }
            (candidates + shipping).distinct().map(tags::commit)
        }
        val holds =
            holdIssues.all().mapNotNull { issue ->
                resolve(issue, receipts) { commit -> targets.any { source.isAncestor(commit, it) } }
            }
        return StoreFacts(
            current.versions,
            current.builds,
            receipts,
            holds,
            current.submissions,
        )
    }

    // A hold naming no receipted build resolves to no commit. A failed read is an error, never
    // a guess: it must not turn a hold into a malformed one or lift it.
    private fun resolve(
        issue: HoldIssue,
        receipts: Set<String>,
        canBlock: (String) -> Boolean,
    ): Hold? {
        val build = heldBuild(issue.body)
        val commit =
            build?.let { "deploy/testflight/$it" }?.takeIf { it in receipts }?.let(tags::commit)
        if (!issue.open && (commit == null || !canBlock(commit))) return null
        val details = if (issue.open) issue else holdIssues.closedDetails(issue)
        return Hold(
            issue.number,
            issue.open,
            build,
            commit,
            details.fix,
            // Only a person closing without a fix lifts a hold; automation never does.
            lifted = !issue.open && details.fix == null && details.closedByPerson,
            typed = heldBuildField(issue.body),
        )
    }

    fun heldBy(facts: StoreFacts, sha: String) =
        facts.holds.firstOrNull { it.blocks(sha, source::isAncestor) }

    fun rules(facts: StoreFacts): List<EligibilityRule> {
        // Finding a candidate's commit costs two GitHub reads; skip it when nothing can block.
        val blocking = facts.holds.any { !it.lifted && it.commit != null }
        return eligibilityRules(facts) { candidate ->
            blocking && candidate.receipt?.let { heldBy(facts, tags.commit(it)) } != null
        }
    }

    private fun requireEnabled() =
        require(env["IOS_APPSTORE_ENABLED"] == "true") {
            "App Store release is disabled; IOS_APPSTORE_ENABLED is not true"
        }

    fun promote(request: ReleaseRequest): ReleaseResult {
        requireEnabled()
        if (!request.scheduled)
            require(!request.reason.isNullOrBlank()) { "A manual App Store release needs a reason" }
        val facts = facts()
        // A scheduled run skips quietly while the previous release is still on its way.
        facts.unresolved.firstOrNull()?.let { previous ->
            return request.stop(
                "${previous.version} is still ${previous.state}",
                if (previous.state in StoreStates.inReview)
                    "${previous.version} is still ${previous.state}. Remove it from review in App Store Connect first."
                else
                    "${previous.version} is ${previous.state}. Resolve it in App Store Connect first.",
                action = null,
            )
        }
        // For example a submit cut off before it was sent. Only App Store Connect can finish it.
        facts.openSubmissions.firstOrNull()?.let { open ->
            return request.stop(
                "review submission ${open.id} is ${open.state}",
                stranded(open, facts),
            )
        }
        // A hold that names no receipted build could cover anything, so it blocks everything.
        facts.holds
            .firstOrNull { it.malformed }
            ?.let { hold ->
                return request.stop(
                    "hold #${hold.issue} cannot be resolved",
                    "Hold #${hold.issue} names no TestFlight build with a receipt (${hold.unresolved}); fix it or close it before any App Store release.",
                )
            }
        // Never resubmit automatically; an explicit promote is the retry path.
        facts.failedSubmissions.firstOrNull()?.let {
            if (request.scheduled)
                return ReleaseResult(
                    "skipped: earlier submission of ${it.version} failed or is uncertain",
                    "The submission of ${it.version} is ${it.state} with a build attached. ${retry(facts)}",
                )
        }
        val selection = Selection(facts, rules(facts))
        val candidate = selection.selected ?: return selectionSkip(facts, selection)
        // Each release copies its listing from the last one, so the first ships by hand.
        if (facts.lastRelease == null)
            return request.stop(
                "no App Store version has been released yet",
                "No App Store version has been released yet. Release the first one by hand in App Store Connect; later releases copy its listing.",
            )
        strayDraft(facts, candidate)?.let { draft ->
            return request.stop(
                "App Store version ${draft.version} is a draft this pipeline did not make",
                "App Store version ${draft.version} is an editable draft this pipeline did not make. Delete it or ship it by hand in App Store Connect before any App Store release.",
            )
        }
        // The dSYMs are kept before Apple is touched; a build that cannot keep them is not sent.
        // Keeping them downloads the whole retained build, so it comes before the last read.
        symbols.keep(
            requireNotNull(candidate.receipt) {
                "Selected build ${candidate.build.number} needs a TestFlight receipt before its dSYMs can be retained"
            },
            MarketingVersion(candidate.build.version),
            BuildNumber(candidate.build.number),
        )
        // Read Apple, the receipts and holds again immediately before changing Apple.
        val latest = facts()
        require(
            latest.unresolved.isEmpty() &&
                latest.openSubmissions.isEmpty() &&
                latest.holds.none { it.malformed } &&
                strayDraft(latest, candidate) == null &&
                selectBuild(latest, rules(latest)) == candidate
        ) {
            "App Store state changed while selecting ${candidate.build.version}/${candidate.build.number}; nothing was submitted"
        }
        return submit(latest, candidate, request)
    }

    private fun selectionSkip(facts: StoreFacts, selection: Selection): ReleaseResult {
        val held = selection.candidates.firstOrNull { selection.failure(it) == "is not held" }
        if (held != null) {
            val receipt =
                requireNotNull(held.receipt) {
                    "Held candidate ${held.build.number} must have a TestFlight receipt"
                }
            val hold =
                requireNotNull(heldBy(facts, tags.commit(receipt))) {
                    "Held candidate ${held.build.number} must name its blocking hold"
                }
            return ReleaseResult(
                "skipped: ${held.build.version}/${held.build.number} is held by #${hold.issue}",
                holdAction(hold),
            )
        }
        val newer =
            eligibilityRules(facts).single { it.name == "is newer than the last App Store release" }
        return if (selection.candidates.any(newer.allows))
            ReleaseResult(
                "skipped: no eligible TestFlight build",
                "Run App Store preflight to see why the newer builds are ineligible.",
            )
        else ReleaseResult("skipped: no TestFlight build is newer than the last App Store release")
    }

    fun holdAction(hold: Hold): String {
        val url =
            "${env["GITHUB_SERVER_URL"] ?: "https://github.com"}/${env["GITHUB_REPOSITORY"].orEmpty()}/issues/${hold.issue}"
        val state =
            when {
                hold.open -> "is open"
                hold.fix != null ->
                    "was closed with fix ${hold.fix}, which this build does not contain"
                else -> "was closed by a bot or unknown actor without a fix"
            }
        return "Hold #${hold.issue} ($url) $state. If it was a false alarm, ${if (hold.open) "" else "reopen it and "}close it yourself. Otherwise, close it from the commit that fixes it and deliver a build containing that fix."
    }

    private fun submit(
        facts: StoreFacts,
        candidate: Candidate,
        request: ReleaseRequest,
    ): ReleaseResult {
        val build = candidate.build
        val version = MarketingVersion(build.version)
        // Everything is read before the first Apple change, so a failed read changes nothing.
        val sha =
            tags.commit(
                requireNotNull(candidate.receipt) {
                    "Selected build ${build.number} needs a TestFlight receipt before App Store submission"
                }
            )
        val notes = source.notes(notesBase(facts), sha)
        val template =
            requireNotNull(facts.lastRelease) {
                    "No released App Store version exists to copy metadata from; ship the first release by hand"
                }
                .version
        val versionId = versionSlot(facts, version)
        apple.stage(version, build.id, template, notes)
        val rollout = apple.storeVersions().single { it.id == versionId }.rollout
        if (request.phased && rollout == null) apple.requestPhasedRelease(versionId)
        if (!request.phased && rollout != null) apple.removePhasedRelease(rollout.id)
        val submission = attemptEffect { apple.submit(versionId, build.id) }
        val after = apple.storeVersions().single { it.id == versionId }
        if (after.state in StoreStates.unsent) {
            submission.getOrThrow()
            error(
                "Apple has not accepted the submission of $version; do not resubmit automatically"
            )
        }
        val tag = "submit/appstore/$version/${build.number}"
        val receipt = storeReceipt(sha, version, build.number, build.id, versionId)
        val repair = attemptEffect { tags.write(tag, sha, receipt) }.exceptionOrNull()
        if (!request.scheduled)
            println(
                "Critical fix? Request an expedited review in App Store Connect; this tool never does."
            )
        return ReleaseResult(
            "submitted $version/${build.number} (${if (request.phased) "phased" else "all users"})",
            repair?.let { "Submission accepted, but $tag was not written: ${it.displayMessage()}" },
            version.toString(),
            build.number,
        )
    }

    // Release notes cover changes since the newest shipped build whose commit a receipt records.
    // A version shipped from a build without one (uploaded by hand) is skipped, not fatal.
    private fun notesBase(facts: StoreFacts): String =
        facts.versions
            .filter { it.state in StoreStates.released && it.buildNumber != null }
            .sortedByDescending { StoreFacts.buildCount(it.buildNumber!!) }
            .map { "deploy/testflight/${it.version}/${it.buildNumber}" }
            .firstOrNull { it in facts.receipts }
            ?.let(tags::commit) ?: config.baseline

    // A draft a release may take over: one for a marketing version the pipeline's TestFlight
    // builds carry (left by an interrupted or failed release). Any other draft is a person's
    // work and blocks release.
    fun strayDraft(facts: StoreFacts, candidate: Candidate): StoreVersion? =
        facts.versions
            .filter { it.state in StoreStates.editable }
            .firstOrNull { draft ->
                draft.version != candidate.build.version &&
                    facts.receipts.none { it.startsWith("deploy/testflight/${draft.version}/") }
            }

    // One editable draft may be reused, or renamed to the selected version; otherwise a new
    // version is made. Either way the metadata is copied from the last release.
    private fun versionSlot(facts: StoreFacts, version: MarketingVersion): String {
        val drafts = facts.versions.filter { it.state in StoreStates.editable }
        require(drafts.size <= 1) {
            "Several editable App Store versions exist; resolve them first"
        }
        val draft = drafts.singleOrNull() ?: return apple.createVersion(version)
        if (draft.version != version.value) apple.renameVersion(draft.id, version)
        return draft.id
    }

    fun control(operation: String, reason: String?): ReleaseResult {
        requireEnabled()
        require(!reason.isNullOrBlank()) { "A rollout change needs a reason" }
        val (target, state) =
            when (operation) {
                "pause" -> setOf("ACTIVE") to "PAUSED"
                "resume" -> setOf("PAUSED") to "ACTIVE"
                "release-to-all" -> setOf("ACTIVE", "PAUSED") to "COMPLETE"
                else -> error("Unsupported rollout operation: $operation")
            }
        // Apple versions only: an emergency pause must not wait on GitHub or the build list.
        val version =
            requireNotNull(StoreStates.lastRelease(apple.storeVersions())) {
                "No App Store version has been released"
            }
        val rollout =
            requireNotNull(version.rollout) {
                "${version.version} was released without a phased rollout"
            }
        // Repeating a change already in effect is safe: an anxious second pause changes nothing.
        if (rollout.state == state)
            return ReleaseResult(
                "${version.version} rollout is already $state; nothing changed: $reason"
            )
        require(rollout.state in target) {
            "The phased rollout of ${version.version} is ${rollout.state}; cannot $operation it"
        }
        apple.setRollout(rollout.id, state)
        return ReleaseResult("${version.version} rollout set to $state: $reason")
    }

    // Read-only: repairs App Store receipts and reports release and rollout state.
    fun sweep(): List<SweepFinding> {
        val facts = facts()
        val findings = mutableListOf<SweepFinding>()
        val submitted = tags.names("submit/appstore/")
        val deployed = tags.names("deploy/appstore/")
        facts.versions
            .filter { it.state !in StoreStates.editable && it.buildNumber != null }
            .forEach { version ->
                val key = IncidentSubject.AppStoreVersion(version.version)
                val testflight = "deploy/testflight/${version.version}/${version.buildNumber}"
                val wanted =
                    listOfNotNull(
                        "submit/appstore/${version.version}/${version.buildNumber}"
                            .takeIf { it !in submitted && version.state !in StoreStates.unsent },
                        "deploy/appstore/${version.version}/${version.buildNumber}"
                            .takeIf { it !in deployed && version.state in StoreStates.released },
                    )
                if (
                    wanted.isNotEmpty() &&
                        testflight in facts.receipts &&
                        MarketingVersion.accepts(version.version)
                )
                    try {
                        val sha = tags.commit(testflight)
                        val receipt =
                            storeReceipt(
                                sha,
                                MarketingVersion(version.version),
                                version.buildNumber!!,
                                requireNotNull(version.buildId) {
                                    "Cannot repair App Store receipt for ${version.version}: Apple did not report an attached build ID"
                                },
                                version.id,
                            )
                        wanted.forEach { tags.write(it, sha, receipt) }
                    } catch (error: Exception) {
                        findings +=
                            SweepFinding(
                                key,
                                "receipt repair failed",
                                repairError = error.displayMessage(),
                            )
                    }
                when {
                    version.state == "PENDING_DEVELOPER_RELEASE" ->
                        findings +=
                            SweepFinding(
                                key,
                                "approved, waiting for release",
                                "${version.version} waits for a manual release in App Store Connect",
                            )
                    version in facts.unresolved -> findings += SweepFinding(key, version.state)
                }
            }
        // One waiting for or in review already shows as its version's state above.
        facts.openSubmissions
            .filter { it.state !in setOf("WAITING_FOR_REVIEW", "IN_REVIEW") }
            .forEach {
                findings +=
                    SweepFinding(
                        IncidentSubject.ReviewSubmission(it.id),
                        "review submission ${it.state}",
                        stranded(it, facts),
                    )
            }
        facts.failedSubmissions.forEach {
            findings +=
                SweepFinding(
                    IncidentSubject.AppStoreVersion(it.version),
                    "submission ${it.state}",
                    "The submission of ${it.version} is ${it.state}. ${retry(facts)}",
                )
        }
        findings += holdFindings(facts)
        facts.lastRelease?.let { last ->
            val rollout = last.rollout
            findings +=
                SweepFinding(
                    IncidentSubject.AppStoreVersion(last.version),
                    if (rollout == null || rollout.state == "COMPLETE") "released to all users"
                    else
                        "phased rollout ${rollout.state.lowercase()}, day ${rollout.day} (${rollout.percent}%), pause days used ${rollout.pausedDays}/30",
                )
        }
        return findings
    }

    private fun holdFindings(facts: StoreFacts): List<SweepFinding> {
        val findings =
            facts.holds
                .filter { it.open }
                .map { hold ->
                    if (hold.malformed)
                        SweepFinding(
                            IncidentSubject.Hold(hold.issue),
                            "unresolvable hold",
                            "Hold #${hold.issue} names no TestFlight build with a receipt (${hold.unresolved}); every App Store release waits until it is fixed or closed.",
                        )
                    else SweepFinding(IncidentSubject.Hold(hold.issue), "holding ${hold.build}")
                }
                .toMutableList()
        // Held code already on its way to people: say what to do, never change Apple.
        val shipping =
            facts.unresolved +
                listOfNotNull(facts.lastRelease?.takeIf { it.rollout?.state == "ACTIVE" })
        shipping.forEach { version ->
            val tag = "deploy/testflight/${version.version}/${version.buildNumber}"
            if (version.buildNumber == null || tag !in facts.receipts) return@forEach
            val hold = heldBy(facts, tags.commit(tag)) ?: return@forEach
            findings +=
                SweepFinding(
                    IncidentSubject.AppStoreVersion(version.version),
                    "held",
                    if (version in facts.unresolved)
                        "Hold #${hold.issue} covers ${version.version}, which is ${version.state}. Remove it from review in App Store Connect."
                    else
                        "Hold #${hold.issue} covers ${version.version}, which is rolling out. Pause it: ${hints.command("app-store-release.yml", "-f operation=pause -f reason=\"Hold #${hold.issue}\"")}",
                )
        }
        return findings
    }

    private fun stranded(submission: ReviewSubmission, facts: StoreFacts) =
        "Review submission ${submission.id} is ${submission.state}, and Apple allows one open submission. " +
            if (facts.lastRelease == null) "Finish it in App Store Connect. $FIRST"
            else "Submit or cancel it in App Store Connect, then dispatch promote with a reason."

    private fun storeReceipt(
        sha: String,
        version: MarketingVersion,
        number: String,
        buildId: String,
        versionId: String,
    ) = Receipt(sha, version, BuildNumber(number), buildId, ascVersionId = versionId)
}
