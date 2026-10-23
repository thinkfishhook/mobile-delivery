package delivery

import java.time.ZoneId
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

// Walk the serialization schema so the validator and the model cannot disagree on keys.
internal fun configurationProblems(text: String): List<String> {
    val document =
        try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return listOf("invalid JSON")
        }
    val problems = mutableListOf<String>()
    validateShape(document, DeliveryConfig.serializer().descriptor, "", problems)
    val root = document as? JsonObject ?: return problems
    fun value(path: String): JsonElement? =
        path.split('.').fold(root as JsonElement?) { node, key ->
            (node as? JsonObject)?.get(key)
        }
    fun string(path: String) = (value(path) as? JsonPrimitive)?.takeIf { it.isString }?.content
    string("timezone")?.let {
        if (runCatching { ZoneId.of(it) }.isFailure) problems += "timezone is invalid"
    }
    string("incident_marker")?.let {
        if (!Regex("[a-z0-9-]+").matches(it))
            problems += "incident_marker may use only lowercase letters, digits and dashes"
    }
    (value("verification.commands") as? JsonArray)?.let { commands ->
        if (commands.isEmpty() || commands.any { (it as? JsonArray)?.isEmpty() == true })
            problems += "verification.commands needs at least one non-empty command"
    }
    (value("verification.test_reports") as? JsonArray)?.let {
        if (it.isEmpty())
            problems +=
                "verification.test_reports needs at least one report: delivery requires passing tests"
    }
    for (path in
        listOf("verification.test_reports", "verification.evidence", "release_notes.directory")) {
        val node = value(path)
        val paths = if (node is JsonArray) node.toList() else listOfNotNull(node)
        paths.forEachIndexed { index, entry ->
            val name =
                (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEachIndexed
            if (name.isBlank() || name.startsWith('/') || ".." in name.split('/'))
                problems +=
                    "$path${if (node is JsonArray) "[$index]" else ""} must be a path inside the repository"
        }
    }
    return problems
}

private fun validateShape(
    value: JsonElement,
    schema: SerialDescriptor,
    path: String,
    problems: MutableList<String>,
) {
    if (value == JsonNull && schema.isNullable) return
    when (schema.kind) {
        StructureKind.CLASS -> {
            if (value !is JsonObject) {
                problems += "${path.ifEmpty { "configuration" }} must be an object"
                return
            }
            val names = (0 until schema.elementsCount).map(schema::getElementName)
            (value.keys - names.toSet()).forEach {
                problems +=
                    if (path.isEmpty() && it == "branch")
                        "branch is no longer supported; delete it and set branch in the caller's with: inputs"
                    else "${joinKey(path, it)} is unknown"
            }
            names.forEachIndexed { index, name ->
                val key = joinKey(path, name)
                val child = value[name]
                if (child == null) {
                    if (!schema.isElementOptional(index)) {
                        val childSchema = schema.getElementDescriptor(index)
                        if (childSchema.kind == StructureKind.CLASS)
                            validateShape(JsonObject(emptyMap()), childSchema, key, problems)
                        else problems += "$key is missing"
                    }
                } else validateShape(child, schema.getElementDescriptor(index), key, problems)
            }
        }
        StructureKind.LIST -> {
            if (value !is JsonArray) problems += "$path must be an array"
            else
                value.forEachIndexed { index, child ->
                    validateShape(child, schema.getElementDescriptor(0), "$path[$index]", problems)
                }
        }
        PrimitiveKind.STRING -> {
            if (value !is JsonPrimitive || !value.isString) problems += "$path must be a string"
            else if (value.content.isBlank()) problems += "$path must not be blank"
        }
        else -> Unit
    }
}

private fun joinKey(parent: String, key: String) = if (parent.isEmpty()) key else "$parent.$key"

internal fun requireConfiguration(problems: List<String>, location: String) {
    require(problems.isEmpty()) {
        problems.joinToString("\n", "Configuration problems:\n") { "$location: $it" }
    }
}
