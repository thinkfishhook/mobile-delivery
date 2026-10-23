package delivery

import java.util.Base64
import kotlinx.serialization.json.*

internal fun actionWorkflowRoles(text: String, repository: String): Set<String> {
    val compositeRoles =
        mapOf(
            "testflight" to "testflight-delivery.yml",
            "maintenance" to "maintenance.yml",
            "app-store-release" to "app-store-release.yml",
            "app-store-rollout" to "app-store-release.yml",
        )
    val composite = Regex("${Regex.escape(repository)}/([a-z-]+)@[^\\s@]+")
    val jobs = yamlMapping(workflowDocument(text)["jobs"])
    return jobs.values
        .flatMap { value ->
            workflowSteps(yamlMapping(value)).mapNotNull { step ->
                val uses = step["uses"]?.toString().orEmpty()
                composite.matchEntire(uses)?.groupValues?.get(1)?.let(compositeRoles::get)
            }
        }
        .toSet()
}

internal fun GitHub.runUsesWorkflow(run: JsonObject, workflow: String): Boolean {
    val repository = env["DELIVERY_WORKFLOW_REPOSITORY"] ?: return false
    val file = workflowFile(run["path"]?.jsonPrimitive?.content) ?: return false
    val sha = run["head_sha"]?.jsonPrimitive?.content ?: return false
    if (!Regex("[a-f0-9]{40}").matches(sha)) return false
    val key = "$file@$sha"
    val roles =
        actionRoleCache.getOrPut(key) {
            val doc = api("contents/.github/workflows/$file?ref=$sha", missing = true)?.jsonObject
            if (doc == null) emptySet()
            else {
                require(doc["encoding"]?.jsonPrimitive?.content == "base64") {
                    "Unsupported workflow content encoding"
                }
                val text = Base64.getMimeDecoder().decode(doc.string("content")).decodeToString()
                actionWorkflowRoles(text, repository)
            }
        }
    return workflow in roles
}
