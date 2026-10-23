package delivery

import java.util.Base64

internal fun workflowContents(action: String): String {
    val text = "jobs:\n  ship:\n    steps:\n      - uses: $action\n"
    val content = Base64.getEncoder().encodeToString(text.toByteArray())
    return """{"content":"$content","encoding":"base64"}"""
}
