package delivery

sealed interface StartRunSelection {
    data class Superseded(val sha: String, val tip: String) : StartRunSelection

    data class Proceed(val sha: String) : StartRunSelection
}

// Select before reading delivery records or Apple. Operation/retry interpretation stays in
// the delivery handler so selection errors retain their distinct failure-summary boundary.
class StartRun(
    private val config: DeliveryConfig,
    private val source: GitSource,
    private val github: GitHub,
    private val env: Map<String, String>,
    private val branch: String,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    fun cancelSuperseded(
        selection: StartRunSelection.Superseded,
        summarize: (RunSummary) -> Unit,
    ): Nothing {
        val summary =
            RunSummary(
                "cancelled",
                "internal/${selection.sha}: superseded by ${selection.tip}, not tested",
                emptyList(),
                emptyList(),
            )
        summarize(summary)
        val started = elapsedMillis()
        val fix =
            "Grant the caller job permissions: actions: write. Retain its other permissions.\npermissions:\n  actions: write"
        fun fail(reason: String): Nothing {
            val message = "$reason\n$fix"
            summarize(summary.copy(runHealth = "failed", actionNeeded = listOf(message)))
            throw CommandError(message)
        }
        try {
            github.cancelRun(env.getValue("GITHUB_RUN_ID"))
        } catch (error: Exception) {
            fail("Superseded run cancellation refused or failed: ${error.message}.")
        }
        // Acceptance is asynchronous. Never return to delivery work, even after HTTP 202.
        while (true) {
            val remaining = 180_000 - (elapsedMillis() - started)
            if (remaining <= 0) fail("Superseded run was not cancelled within 180 seconds.")
            pause(minOf(2000, remaining))
        }
    }

    fun select(event: WorkflowEvent): StartRunSelection {
        val selected = event.inputs?.sha?.takeIf { it.isNotBlank() } ?: env.getValue("GITHUB_SHA")
        require(Regex("[a-f0-9]{40}").matches(selected)) { "Select an exact full commit SHA" }
        val resolved = source.git("rev-parse", "$selected^{commit}")
        require(resolved == selected) {
            "Selected SHA $selected must resolve to that exact commit; git resolved $resolved"
        }
        source.git("merge-base", "--is-ancestor", config.baseline, selected)
        source.git("merge-base", "--is-ancestor", selected, "origin/$branch")
        val push = env["GITHUB_EVENT_NAME"] == "push"
        // checkout can reset origin/branch to a queued event's commit after the branch moves.
        val tip =
            if (push) github.currentBranchTip(branch) else source.git("rev-parse", "origin/$branch")
        // Explicit dispatches may still select older SHAs.
        return if (push && selected != tip) StartRunSelection.Superseded(selected, tip)
        else StartRunSelection.Proceed(selected)
    }
}
