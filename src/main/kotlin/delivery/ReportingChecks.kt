package delivery

internal fun reportingAction(value: Any?) =
    value.toString() == "./report-failure" ||
        Regex("[\\w./-]+/report-failure@[a-f0-9]{40}").matches(value.toString())

internal fun validateReportingJob(document: Map<*, *>, name: String) {
    val jobs = yamlMapping(document["jobs"])
    val job = yamlMapping(jobs[name])
    val steps = workflowSteps(job)
    require(steps.size == 1 && reportingAction(steps.single()["uses"])) {
        "Reporter job must invoke only the pinned reporter, without checkout or engine setup"
    }
    require(job["if"]?.toString()?.removePrefix("\${{")?.removeSuffix("}}")?.trim() == "always()") {
        "Reporter must run after success and failures, including early setup failures"
    }
    val concurrency = yamlMapping(job["concurrency"])
    require(
        concurrency["group"] == "mobile-delivery-reporting" &&
            concurrency["queue"] == "max" &&
            concurrency["cancel-in-progress"] == false
    ) {
        "Serialize every reporter with mobile-delivery-reporting, queue: max, cancel-in-progress: false to prevent duplicate issue creation"
    }
    require(job["env"] == null && document["env"] == null && steps.single()["env"] == null) {
        "Reporter must not inherit app/signing credentials"
    }
    val permissions = job["permissions"] ?: document["permissions"]
    require(
        permissions ==
            mapOf(
                "contents" to "read",
                "actions" to "read",
                "checks" to "read",
                "issues" to "write",
            )
    ) {
        "Reporter permissions must be exactly Contents/Actions/Checks read and Issues write"
    }
    val inputs = yamlMapping(steps.single()["with"])
    require(
        inputs.keys.all {
            it in setOf("role", "operation-job", "integration-branch", "github-token")
        } &&
            inputs["role"] in setOf("ci", "deploy") &&
            inputs["integration-branch"] is String &&
            inputs["integration-branch"].toString().isNotBlank()
    ) {
        "Reporter requires role, operation-job and integration-branch; no app/provider credentials"
    }
    val dependencies =
        when (val needs = job["needs"]) {
            is String -> listOf(needs)
            is List<*> -> needs
            else -> emptyList<Any>()
        }
    require(dependencies.size == 1 && jobs.containsKey(dependencies.single())) {
        "Reporter must depend on its exact operation job"
    }
    val operationId = dependencies.single()
    val operation = yamlMapping(jobs[operationId])
    require(
        operation["strategy"] == null &&
            inputs["operation-job"] == (operation["name"] ?: operationId)
    ) {
        "Reporter operation-job must match the displayed non-matrix job name"
    }
}
