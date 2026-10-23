package delivery

import kotlinx.serialization.json.*

fun GitHub.ensureDeliveryLabels() {
    for (label in listOf(HOLD_LABEL, INCIDENT_LABEL)) {
        if (api("labels/$label", missing = true) != null) continue
        try {
            api(
                "labels",
                buildJsonObject {
                    put("name", label)
                    put("color", if (label == HOLD_LABEL) "B60205" else "D93F0B")
                    put(
                        "description",
                        if (label == HOLD_LABEL) "Hold a TestFlight build out of the App Store"
                        else "iOS delivery needs attention",
                    )
                },
            )
        } catch (error: GitHubFailure) {
            // A concurrent sweep may create the same label after our read. Other failures
            // still stop the sweep; never silently lose its holds or incident labels.
            if (!error.labelAlreadyExists) throw error
        }
    }
}
