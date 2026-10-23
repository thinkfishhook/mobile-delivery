package delivery

enum class TraceState {
    DELIVERED,
    RESUMABLE,
    UNCERTAIN,
    FAILED,
    ABANDONED,
    INTERRUPTED,
}

// One build number as receipt tags, App Store Connect and retained artifacts show it. `version`
// is null for a retained build Apple has not seen; its manifest holds the version.
data class Trace(
    val number: BuildNumber,
    val version: String?,
    val receiptTarget: String?,
    val upload: AppleUpload?,
    val build: TestFlightBuild?,
    val retained: RetainedBuild? = null,
) {
    val state: TraceState
        get() =
            when {
                receiptTarget != null -> TraceState.DELIVERED
                build?.processingState == "PROCESSING" -> TraceState.UNCERTAIN
                build != null ->
                    if (build.processingState == "VALID" && !build.expired) TraceState.RESUMABLE
                    else TraceState.FAILED
                // Apple has the file but shows no build yet.
                upload?.received == true -> TraceState.UNCERTAIN
                upload?.state == "FAILED" -> TraceState.FAILED
                // A job that ended mid-upload leaves AWAITING_UPLOAD or UPLOADING forever.
                upload != null -> TraceState.ABANDONED
                // Retained, but Apple lists no upload: never sent, or sent and not listed yet.
                else -> TraceState.INTERRUPTED
            }

    val detail: String
        get() =
            listOfNotNull(
                    "${version?.let { "$it/" }.orEmpty()}$number ${state.name.lowercase()}",
                    upload?.code?.let { "Apple error $it" },
                    upload?.reason,
                )
                .joinToString(": ")
}

// Receipt tags, App Store Connect builds and uploads, read once and shared by one decision.
// `version` is the marketing version a new build would take.
data class BetaFacts(
    val receipts: Set<String>,
    val builds: List<TestFlightBuild>,
    val uploads: List<AppleUpload>,
    val version: MarketingVersion,
)

class CommitTraces(
    val sha: String,
    val count: Int,
    val version: MarketingVersion,
    val traces: List<Trace>,
) {
    val delivered = traces.firstOrNull { it.receiptTarget == sha }
    val latest = traces.maxByOrNull { it.number.attempt }
    val expectedAttempt = latest?.number?.value ?: "none"
}
