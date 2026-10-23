package delivery

import kotlinx.serialization.json.*

// Paths come from GitHub metadata, never from a guessed caller filename.
internal fun workflowFile(path: String?): String? =
    path?.substringBefore('@')?.substringAfter(".github/workflows/", "")?.takeIf {
        Regex("[A-Za-z0-9_.-]+\\.ya?ml").matches(it)
    }

class WorkflowHints(
    private val env: Map<String, String>,
    private val findWorkflow: ((String) -> String?)? = null,
) {
    fun command(workflow: String, arguments: String): String {
        val file =
            if (findWorkflow != null) findWorkflow.invoke(workflow)
            else workflowFile(env["GITHUB_WORKFLOW_REF"])
        return if (file != null) "gh workflow run $file $arguments"
        else
            "${env["GITHUB_SERVER_URL"] ?: "https://github.com"}/${env["GITHUB_REPOSITORY"] ?: ""}/actions"
    }

    fun retry(sha: String, attempt: String) =
        command(
            "testflight-delivery.yml",
            "-f operation=retry -f sha=$sha -f expected_attempt=$attempt -f reason=\"<why this retry is safe>\"",
        )
}

internal fun GitHub.workflowHints(currentWorkflow: String? = null): WorkflowHints {
    val cached = mutableMapOf<String, String?>()
    return WorkflowHints(env) { workflow ->
        if (workflow == currentWorkflow) workflowFile(env["GITHUB_WORKFLOW_REF"])
        else {
            if (workflow !in cached)
                cached[workflow] = runCatching { findCaller(workflow) }.getOrNull()
            cached[workflow]
        }
    }
}

// Stop at the first matching caller instead of loading the repository's entire run history.
private fun GitHub.findCaller(workflow: String): String? {
    var page = 1
    while (true) {
        val rows =
            api("actions/runs?per_page=100&page=$page")?.jsonObject?.get("workflow_runs")?.jsonArray
                ?: return null
        for (element in rows) {
            val run = element.jsonObject
            if (runUsesWorkflow(run, workflow)) {
                workflowFile(run["path"]?.jsonPrimitive?.content)?.let {
                    return it
                }
            }
        }
        if (rows.size < 100) return null
        page++
    }
}
