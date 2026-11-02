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
) {
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
