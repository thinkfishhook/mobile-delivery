package delivery

import java.nio.file.Path
import kotlin.io.path.*

class WorkflowChecks {
    fun validate(root: Path) {
        CompositeChecks().validate(root)
        root.resolve(".github/workflows").listDirectoryEntries("*.yml").forEach { path ->
            val file = path.fileName.toString()
            val doc = workflowDocument(path.readText())
            require(doc["env"] == null) { "$file: credentials belong to steps" }
            yamlMapping(doc["jobs"]).forEach { (name, value) ->
                val job = yamlMapping(value)
                require(job["env"] == null) { "$file $name: credentials belong to steps" }
                workflowSteps(job).forEach { step ->
                    step["uses"]?.toString()?.let { action ->
                        val localComposite =
                            action in
                                setOf(
                                    "./testflight",
                                    "./maintenance",
                                    "./app-store-release",
                                    "./app-store-rollout",
                                )
                        require(localComposite || Regex("[\\w./-]+@[a-f0-9]{40}").matches(action)) {
                            "$file: $action is not pinned to a full commit SHA"
                        }
                        if (action.startsWith("actions/checkout@")) {
                            require(yamlMapping(step["with"])["persist-credentials"] == false) {
                                "$file: checkout must not persist credentials"
                            }
                        }
                    }
                }
            }
        }
    }
}
