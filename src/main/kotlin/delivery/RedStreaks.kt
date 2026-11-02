package delivery

import kotlinx.serialization.json.*

internal fun renderRedStreak(
    sha: String,
    subject: String,
    runUrl: String,
    failures: List<TestFailure>,
): String =
    "Red commit: ${sha.take(12)} $subject\n\nRun: $runUrl\n\nFailing tests:\n" +
        failures.joinToString("\n") { "- ${it.test}" }

// Only creates the first issue in a streak. Updates and resolution belong to later slices.
class RedStreaks(private val github: GitHub, private val owner: String) {
    private val marker = "<!-- $owner:${IncidentSubject.RedStreak.key} -->"

    private fun isOpen() =
        github.pages("issues?labels=$INCIDENT_LABEL&state=open").any {
            "pull_request" !in it && marker in it["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

    fun sync(body: String) {
        if (isOpen()) return
        github.ensureDeliveryLabels()
        val write = attemptEffect {
            github.api(
                "issues",
                buildJsonObject {
                    put("title", IncidentSubject.RedStreak.title)
                    put("body", "$marker\n\n$body")
                    putJsonArray("labels") { add(INCIDENT_LABEL) }
                },
            )
        }
        // A response lost after GitHub accepts the issue must not cause a duplicate write.
        if (write.isSuccess || isOpen()) return
        write.getOrThrow()
    }
}
