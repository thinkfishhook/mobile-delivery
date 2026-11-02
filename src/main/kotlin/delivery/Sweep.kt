package delivery

import kotlinx.serialization.Serializable

@Serializable
data class SweepFinding(
    val operationKey: IncidentSubject,
    val outcome: String,
    val operatorAction: String? = null,
    val repairError: String? = null,
)

@Serializable
data class SweepReport(val findings: List<SweepFinding>) {
    val needsAction: Boolean
        get() = findings.any { it.operatorAction != null || it.repairError != null }

    // Pending operator action is a known, reported state. Only errors in this run are red.
    val broken: Boolean
        get() = findings.any { it.repairError != null }
}

// The read-only pass that checks Apple, repairs receipts and updates incidents. It never
// changes Apple.
class Sweep(
    private val config: DeliveryConfig,
    private val beta: BetaDelivery,
    private val appStore: AppStoreRelease,
    private val apple: Apple,
    private val tags: ReceiptTags,
    private val source: Source,
    private val incidents: Incidents,
    private val queuedPush: (String) -> Boolean,
    private val hints: WorkflowHints = WorkflowHints(emptyMap()),
) {
    fun run(tip: String, audit: Boolean = false): SweepReport {
        val findings = mutableListOf<SweepFinding>()
        val facts =
            try {
                beta.facts()
            } catch (error: InvalidReceiptName) {
                // A hand-made tag blocks all receipt reads. Report it without resolving any
                // incidents whose subjects this interrupted sweep could not check.
                val finding =
                    SweepFinding(
                        IncidentSubject.Audit,
                        "invalid receipt tag",
                        repairError = error.displayMessage(),
                    )
                incidents.sync(listOf(finding), tip) { false }
                return SweepReport(listOf(finding))
            }
        val repaired = mutableSetOf<String>()
        findings += repairReceipts(facts, repaired)
        if (audit) findings += auditReceipts(facts.receipts)
        findings += tipStatus(tip, facts.copy(receipts = facts.receipts + repaired))
        findings +=
            try {
                appStore.sweep()
            } catch (error: Exception) {
                listOf(
                    SweepFinding(
                        IncidentSubject.AppStore,
                        "unread",
                        repairError = error.displayMessage(),
                    )
                )
            }
        val appStoreRead = findings.none {
            it.operationKey == IncidentSubject.AppStore && it.repairError != null
        }
        val receipts = facts.receipts + repaired
        try {
            incidents.sync(findings, tip) { key ->
                checked(key, audit, appStoreRead, facts.builds, receipts)
            }
        } catch (error: Exception) {
            findings +=
                SweepFinding(
                    IncidentSubject.Incidents,
                    "sync failed",
                    repairError = error.displayMessage(),
                )
        }
        return SweepReport(findings)
    }

    // Whether this sweep looked closely enough at an incident's subject to close it. Absence from
    // the findings means nothing for what the sweep did not read.
    private fun checked(
        key: IncidentSubject,
        audit: Boolean,
        appStoreRead: Boolean,
        builds: List<TestFlightBuild>,
        receipts: Set<String>,
    ) =
        when (key) {
            IncidentSubject.Audit -> audit
            is IncidentSubject.Credential -> false
            // Settled once the receipt exists or Apple no longer has a build that needs one.
            is IncidentSubject.Receipt -> {
                "deploy/testflight/${key.version}/${key.number}" in receipts ||
                    builds.none {
                        it.version == key.version &&
                            it.number == key.number &&
                            it.processingState == "VALID" &&
                            !it.expired
                    }
            }
            is IncidentSubject.AppStoreVersion,
            is IncidentSubject.ReviewSubmission,
            is IncidentSubject.Hold -> appStoreRead
            is IncidentSubject.BetaCommit,
            IncidentSubject.AppStore,
            IncidentSubject.Incidents,
            is IncidentSubject.Unknown -> true
        }

    private fun tipStatus(tip: String, facts: BetaFacts): SweepFinding {
        val key = IncidentSubject.BetaCommit(tip)
        val traces = beta.traces(tip, facts)
        if (traces.latest?.receiptTarget == tip) return SweepFinding(key, "delivered")
        val latest = traces.latest
        return when {
            latest == null && queuedPush(tip) -> SweepFinding(key, "queued")
            latest == null ->
                SweepFinding(
                    key,
                    "not delivered",
                    "Deliver it: ${hints.command("testflight-delivery.yml", "-f operation=deliver -f sha=$tip")}",
                )
            latest.state == TraceState.UNCERTAIN -> SweepFinding(key, latest.detail)
            else ->
                SweepFinding(
                    key,
                    latest.detail,
                    "${latest.detail}. Retry explicitly: ${hints.retry(tip, latest.number.value)}",
                )
        }
    }

    // Writes missing receipts for pipeline builds testers can install, whatever their age. Only
    // live builds without a receipt cost a call, and TestFlight expires those within 90 days.
    private fun repairReceipts(facts: BetaFacts, repaired: MutableSet<String>): List<SweepFinding> {
        val baseline = source.count(config.baseline)
        return facts.builds.mapNotNull { build ->
            val version = runCatching { MarketingVersion(build.version) }.getOrNull()
            val number = runCatching { BuildNumber(build.number) }.getOrNull()
            val tag = "deploy/testflight/${build.version}/${build.number}"
            if (
                version == null ||
                    number == null ||
                    number.count < baseline ||
                    tag in facts.receipts ||
                    build.processingState != "VALID" ||
                    build.expired
            )
                return@mapNotNull null
            try {
                // Not testable, or in a terminal TestFlight state such as expired: no receipt.
                // Any other failure to read it is a repair error.
                val testable =
                    try {
                        apple.testable(build.id)
                    } catch (_: TerminalTestFlightState) {
                        false
                    }
                if (!testable) return@mapNotNull null
                val sha =
                    requireNotNull(
                        commitInNotes(apple.notes(build.id)) ?: source.commitWithCount(number.count)
                    ) {
                        "No delivery-branch commit has count ${number.count}"
                    }
                require(source.count(sha) == number.count) {
                    "Commit $sha does not match build $number"
                }
                tags.write(tag, sha, Receipt(sha, version, number, build.id))
                repaired += tag
                SweepFinding(
                    IncidentSubject.BetaCommit(sha),
                    "receipt repaired for $version/$number",
                )
            } catch (error: Exception) {
                SweepFinding(
                    IncidentSubject.Receipt(version.toString(), number.toString()),
                    "receipt repair failed",
                    repairError = error.displayMessage(),
                )
            }
        }
    }

    // A receipt must stay an annotated tag on the commit whose count matches its build number.
    private fun auditReceipts(receipts: Set<String>) = receipts.mapNotNull { tag ->
        try {
            val count = BuildNumber(tag.substringAfterLast('/')).count
            val sha = tags.commit(tag)
            require(source.count(sha) == count) { "$tag points at $sha, whose count is not $count" }
            null
        } catch (error: Exception) {
            SweepFinding(
                IncidentSubject.Audit,
                "receipt collision",
                repairError = "$tag: ${error.displayMessage()}",
            )
        }
    }

    fun summary(result: SweepReport, executionFailed: Boolean = false) =
        RunSummary(
            if (executionFailed) "failed" else "completed",
            result.findings.joinToString("; ") { "${it.operationKey.key}: ${it.outcome}" },
            result.findings.mapNotNull { it.operatorAction },
            result.findings.mapNotNull { it.repairError },
        )
}
