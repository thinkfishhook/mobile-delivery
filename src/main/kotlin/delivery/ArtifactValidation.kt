package delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BinaryChecks(
    @SerialName("required_strings") val requiredStrings: List<String> = emptyList(),
    @SerialName("forbidden_strings") val forbiddenStrings: List<String> = emptyList(),
) {
    init {
        require((requiredStrings + forbiddenStrings).none { it.isBlank() }) {
            "Release binary check strings must not be blank"
        }
    }
}

private fun ByteArray.containsBytes(pattern: ByteArray): Boolean {
    for (start in 0..size - pattern.size) {
        if (pattern.indices.all { this[start + it] == pattern[it] }) return true
    }
    return false
}

fun validateReleaseBinary(binary: ByteArray, checks: BinaryChecks = BinaryChecks()) {
    // Search bytes, not decoded text: UTF-16 strings may start at odd Mach-O offsets.
    val encodings = listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)
    fun contains(value: String) = encodings.any { binary.containsBytes(value.toByteArray(it)) }
    checks.forbiddenStrings.forEachIndexed { index, value ->
        require(!contains(value)) { "Forbidden release binary string found (rule ${index + 1})" }
    }
    checks.requiredStrings.forEachIndexed { index, value ->
        require(contains(value)) { "Required release binary string missing (rule ${index + 1})" }
    }
}
