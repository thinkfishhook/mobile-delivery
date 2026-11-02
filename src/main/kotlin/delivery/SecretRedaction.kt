package delivery

import kotlinx.serialization.json.*

// Keep exact-value masking shared with GitHub error handling. Patterns also protect reports
// containing credentials that did not originate in this process's environment.
internal fun redactSecrets(text: String, values: Collection<String> = emptyList()): String {
    var safe = text
    values
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .forEach {
            safe = safe.replace(it, "***")
        }
    val patterns =
        listOf(
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Regex("\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"),
            Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
            Regex("(?i)(?<=Bearer )[A-Za-z0-9._~+/-]+=*"),
        )
    patterns.forEach { safe = safe.replace(it, "***") }
    return safe.replace(
        Regex("(?i)((?:password|token|secret|api[_-]?key)\\s*[=:]\\s*)([^\\s,;]+)")
    ) {
        "${it.groupValues[1]}***"
    }
}

internal fun secretEnvironmentValues(env: Map<String, String>): List<String> =
    env.filterKeys { Regex("(?i).*?(TOKEN|PASSWORD|SECRET|PRIVATE_KEY|BASE64).*?").matches(it) }
        .values
        .filter { it.isNotEmpty() }

internal fun redactJson(value: JsonElement, secrets: Collection<String>): JsonElement =
    when (value) {
        is JsonObject -> JsonObject(value.mapValues { redactJson(it.value, secrets) })
        is JsonArray -> JsonArray(value.map { redactJson(it, secrets) })
        is JsonPrimitive ->
            if (value.isString) JsonPrimitive(redactSecrets(value.content, secrets)) else value
    }
