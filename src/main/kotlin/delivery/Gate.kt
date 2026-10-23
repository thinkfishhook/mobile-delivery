package delivery

import java.nio.file.Path
import kotlin.io.path.appendText

fun gate(env: Map<String, String>) {
    val branch =
        requireNotNull(env["DELIVERY_ACTION_BRANCH"]?.takeIf { it.isNotBlank() }) {
            "gate requires the composite's branch input"
        }
    val switch =
        when (val mode = env["DELIVERY_VALIDATION_MODE"]) {
            "delivery" -> "IOS_DELIVERY_ENABLED"
            "release",
            "rollout" -> "IOS_APPSTORE_ENABLED"
            "maintenance" -> null // Diagnostics must work before deployment is enabled.
            else ->
                error("gate requires mode delivery, maintenance, release or rollout; found $mode")
        }
    if (switch != null)
        require(env[switch] in setOf("true", "false")) {
            "$switch must be exactly true or false; found '${env[switch] ?: "unset"}'. Set the repository or ios-delivery environment variable explicitly."
        }
    val event = env["GITHUB_EVENT_NAME"]
    require(event in setOf("push", "schedule", "workflow_dispatch")) {
        "gate supports push, schedule and workflow_dispatch events; found $event"
    }
    val reasons = buildList {
        if (env["GITHUB_REF"] != "refs/heads/$branch")
            add(
                "Run this workflow from branch $branch or change its branch input; current ref is ${env["GITHUB_REF"] ?: "unset"}."
            )
        if (switch != null && env[switch] == "false")
            add(
                "Set $switch=true in repository or ios-delivery environment variables when ready to enable this operation."
            )
    }
    if (reasons.isNotEmpty()) {
        val reason = reasons.joinToString(" ")
        if (event == "workflow_dispatch") error(reason)
        annotate("notice", reason)
    }
    val output =
        requireNotNull(env["GITHUB_OUTPUT"]) {
            "gate requires GITHUB_OUTPUT to publish its run decision"
        }
    val verify =
        switch == "IOS_DELIVERY_ENABLED" &&
            env[switch] == "false" &&
            env["GITHUB_REF"] == "refs/heads/$branch" &&
            event in setOf("push", "schedule")
    Path.of(output).appendText("run=${reasons.isEmpty()}\nverify=$verify\n")
}
