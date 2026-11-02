package delivery

import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.*

internal fun redStreakDetails(
    github: GitHub,
    config: DeliveryConfig,
    env: Map<String, String>,
    sha: String,
    branch: String,
    summary: RunSummary,
    output: Path,
): RedStreakDetails {
    val run = env.getValue("GITHUB_RUN_ID")
    val attempt = env["GITHUB_RUN_ATTEMPT"] ?: "1"
    val runUrl = workflowUrl(env)
    val name = "ios-delivery-$run-$attempt"
    val artifact = attemptEffect {
        github.pages("actions/runs/$run/artifacts", "artifacts").firstOrNull {
            it.string("name") == name
        }
    }
        .getOrNull()
    val failedCommands =
        summary.failingCommand?.command?.let(::setOf)
            ?: summary.failures.mapNotNull { it.command }.toSet()
    val selected = config.verification.commands.filter { it.joinToString(" ") in failedCommands }
    val reports =
        (config.verification.testReports + summary.failures.mapNotNull { it.report })
            .distinct()
            .filter { output.resolve("$sha/verification/$it").exists() }
            .map { "delivery/$sha/verification/$it" }
    return RedStreakDetails(
        summary.failingCommand,
        selected,
        branch,
        "$runUrl/attempts/$attempt",
        name,
        artifact?.let { "$runUrl/artifacts/${it.string("id")}" } ?: "$runUrl#artifacts",
        reports,
        artifact != null,
    )
}
