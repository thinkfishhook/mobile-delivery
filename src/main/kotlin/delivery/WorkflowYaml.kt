package delivery

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

internal fun yamlMapping(value: Any?): Map<*, *> =
    value as? Map<*, *> ?: error("Expected YAML mapping")

internal fun workflowDocument(text: String): Map<*, *> =
    yamlMapping(
        Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))
            .load<Any>(text)
    )

internal fun workflowSteps(job: Map<*, *>): List<Map<*, *>> =
    (job["steps"] as? List<*>).orEmpty().map(::yamlMapping)
