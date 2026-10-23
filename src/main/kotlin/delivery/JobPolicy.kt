package delivery

import java.nio.file.Path
import kotlin.io.path.readText

class JobPolicy(private val env: Map<String, String>) {
    fun validate(root: Path) {
        val file =
            requireNotNull(workflowFile(env["GITHUB_WORKFLOW_REF"])) {
                "jobPolicy requires GITHUB_WORKFLOW_REF naming the caller workflow file"
            }
        val jobName =
            requireNotNull(env["GITHUB_JOB"]?.takeIf { it.isNotBlank() }) {
                ".github/workflows/$file: jobPolicy requires GITHUB_JOB naming the caller job"
            }
        val caller = ".github/workflows/$file job $jobName"
        val mode = env["DELIVERY_VALIDATION_MODE"]
        require(mode in setOf("delivery", "maintenance", "release", "rollout")) {
            "$caller: jobPolicy requires mode delivery, maintenance, release or rollout; found $mode"
        }
        val document = workflowDocument(root.resolve(".github/workflows/$file").readText())
        val job =
            requireNotNull((document["jobs"] as? Map<*, *>)?.get(jobName) as? Map<*, *>) {
                "$caller: caller job is missing; add jobs: { $jobName: { ... } }"
            }
        val group = if (mode == "rollout") "ios-rollout" else "ios-delivery"
        val lock = "concurrency: { group: $group, queue: max, cancel-in-progress: false }"
        val concurrency = job["concurrency"] as? Map<*, *>
        require(
            concurrency?.get("group") == group &&
                concurrency["queue"] == "max" &&
                concurrency["cancel-in-progress"] == false
        ) {
            "$caller: the delivery run lock is missing or unsafe. Add:\n    $lock"
        }
        val minimum = linkedMapOf("contents" to "read")
        if (mode != "rollout") {
            minimum["actions"] = "read"
            minimum["issues"] = if (mode == "release") "read" else "write"
        }
        val paste =
            "permissions: { ${minimum.entries.joinToString(", ") { "${it.key}: ${it.value}" }} }"
        val effective =
            if (job.containsKey("permissions")) job["permissions"] else document["permissions"]
        val levels = mapOf("none" to 0, "read" to 1, "write" to 2)
        val permissions =
            when (effective) {
                "read-all" -> minimum.keys.associateWith { "read" }
                "write-all" -> minimum.keys.associateWith { "write" }
                is Map<*, *> -> effective
                else -> emptyMap<Any?, Any?>()
            }
        require(
            minimum.all { (scope, level) ->
                (levels[permissions[scope]] ?: 0) >= levels.getValue(level)
            }
        ) {
            "$caller: required permissions are missing. Add:\n    $paste"
        }
        if (
            effective in setOf("read-all", "write-all") ||
                permissions.any { (scope, level) ->
                    (levels[level] ?: 0) > (levels[minimum[scope]] ?: 0)
                }
        ) {
            annotate(
                "warning",
                "$caller: permissions are broader than this action needs. Use:\n    $paste",
            )
        }
    }
}
