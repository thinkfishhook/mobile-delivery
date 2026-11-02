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
    val repairErrors = publishCredentialProblem(problem, config, commands, env)
    writeRunSummary(
        root,
        env,
        RunSummary(
            "failed",
            "Delivery credential rejected",
            listOf(problem.message!!),
            repairErrors,
        ),
    )
}

// Shared annotation and owner-incident path, also used when Apple delivery already succeeded.
internal fun publishCredentialProblem(
    problem: CredentialProblem,
    config: DeliveryConfig,
    commands: Commands,
    env: Map<String, String>,
): List<String> {
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
    return repairErrors
}

internal fun safeTokenExpiration(value: String): String? = value.takeIf {
    Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[ T][0-9:]+(?:Z| UTC|[+-][0-9:]+)?)?").matches(it)
}

internal fun receiptCredentialProblem(
    error: GitHubApiError,
    expiration: String?,
): CredentialProblem? {
    val description =
        when {
            error.status == 401 ->
                "IOS_RECEIPT_TOKEN was rejected (expired or revoked)" +
                    (error.headers["github-authentication-token-expiration"]?.let(
                            ::safeTokenExpiration
                        ) ?: expiration)
                        ?.let { "; expired on $it" }
                        .orEmpty()
            error.status == 403 &&
                Regex(
                        "(?i)without.*workflows?.*(permission|scope)|without.*(permission|scope).*workflows?"
                    )
                    .containsMatchIn(error.apiMessage) ->
                "IOS_RECEIPT_TOKEN is missing the Workflows permission; it is needed to create tags on commits that change .github/workflows/"
            else -> return null
        }
    return CredentialProblem(
        "IOS_RECEIPT_TOKEN",
        "Receipts weren't written: $description",
        "gh secret set IOS_RECEIPT_TOKEN --env ios-delivery (Contents and Workflows read/write on this repository)",
    )
}
