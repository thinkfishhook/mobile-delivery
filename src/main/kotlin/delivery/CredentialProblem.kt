package delivery

import java.nio.file.Path

// Messages contain only secret names and owner repair commands, never decoder/provider output.
internal class CredentialProblem(val secret: String, description: String, fix: String) :
    IllegalArgumentException("$description\n$fix")

internal fun reportCredentialProblem(
    problem: CredentialProblem,
    root: Path,
    config: DeliveryConfig,
    commands: Commands,
    env: Map<String, String>,
) {
    val message = problem.message!!
    annotate(Annotation("error", message))
    val repairErrors = mutableListOf<String>()
    try {
        val github = GitHub(env.getValue("GITHUB_REPOSITORY"), commands, env)
        github.ensureDeliveryLabels()
        GitHubIncidents(github, config.incidentMarker, workflowInputs(env)?.branch ?: "delivery")
            .sync(
                listOf(
                    SweepFinding(IncidentSubject.Credential(problem.secret), "blocked", message)
                ),
                env["GITHUB_SHA"].orEmpty(),
            ) {
                false
            }
    } catch (_: Exception) {
        repairErrors +=
            "Could not update the credential incident; repair the named secret and rerun."
    }
    writeRunSummary(
        root,
        env,
        RunSummary("failed", "Signing credential rejected", listOf(message), repairErrors),
    )
}
