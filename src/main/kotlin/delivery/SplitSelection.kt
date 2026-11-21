package delivery

import kotlinx.serialization.json.*

private fun producingRequest(
    env: Map<String, String>,
    bundleId: String,
    origin: PackageOrigin,
): PreparationRequest {
    require(env["GITHUB_ACTIONS"] == "true") { "Split delivery requires a GitHub Actions event" }
    val file =
        requireNotNull(workflowFile(env["GITHUB_WORKFLOW_REF"])) {
            "Expected the producing workflow path"
        }
    return PreparationRequest(
        env.getValue("GITHUB_REPOSITORY"),
        bundleId,
        env.getValue("GITHUB_SHA"),
        env.getValue("GITHUB_REF"),
        origin,
        PackageProducer(
            ".github/workflows/$file",
            env.getValue("GITHUB_RUN_ID"),
            env.getValue("GITHUB_RUN_ATTEMPT").toInt(),
        ),
    )
}

fun ciPreparationRequest(
    env: Map<String, String>,
    bundleId: String,
    branch: String,
): PreparationRequest {
    require(env["GITHUB_EVENT_NAME"] == "push" && env["GITHUB_REF"] == "refs/heads/$branch") {
        "Signed CI preparation requires a push to the configured integration branch"
    }
    return producingRequest(env, bundleId, PackageOrigin.CI_VERIFIED)
}

fun manualPreparationRequest(env: Map<String, String>, bundleId: String): PreparationRequest {
    require(env["GITHUB_EVENT_NAME"] == "workflow_dispatch") {
        "Manual branch deployment requires a human workflow_dispatch"
    }
    return producingRequest(env, bundleId, PackageOrigin.MANUAL)
}

fun automaticPreparationRequest(
    env: Map<String, String>,
    eventRun: JsonObject,
    run: JsonObject,
    workflow: JsonObject,
    branch: String,
    workflowFile: String,
    bundleId: String,
): PreparationRequest {
    require(Regex("[A-Za-z0-9_.-]+\\.ya?ml").matches(workflowFile)) {
        "Expected CI workflow filename"
    }
    require(listOf("id", "run_attempt", "head_sha").all { eventRun[it] == run[it] }) {
        "Upstream CI event is stale or differs from its authoritative run"
    }
    require(
        run.string("status") == "completed" &&
            run.string("conclusion") == "success" &&
            run.string("event") == "push"
    ) {
        "Only a successful completed push CI run can deploy"
    }
    require(
        run.string("head_branch") == branch &&
            run.getValue("head_repository").jsonObject.string("full_name") ==
                env.getValue("GITHUB_REPOSITORY")
    ) {
        "Upstream CI repository or integration branch differs"
    }
    require(
        run.string("path") == ".github/workflows/$workflowFile" &&
            run["workflow_id"] == workflow["id"]
    ) {
        "Upstream run is not the configured CI workflow"
    }
    return PreparationRequest(
        env.getValue("GITHUB_REPOSITORY"),
        bundleId,
        run.string("head_sha"),
        "refs/heads/$branch",
        PackageOrigin.CI_VERIFIED,
        PackageProducer(
            ".github/workflows/$workflowFile",
            run.string("id"),
            run.string("run_attempt").toInt(),
        ),
    )
}

fun automaticUploadEligible(request: PreparationRequest, currentTip: () -> String): Boolean =
    request.origin == PackageOrigin.CI_VERIFIED && request.sha == currentTip()
