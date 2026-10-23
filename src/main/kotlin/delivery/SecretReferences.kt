package delivery

// Resolve named secret sources before checking each step's credential boundary.
fun secretReferences(value: String): Set<String> {
    val named = Regex("""\bsecrets\s*\.\s*([A-Z0-9_]+)""", RegexOption.IGNORE_CASE)
    val context = Regex("""\bsecrets\b""", RegexOption.IGNORE_CASE)
    return buildSet {
        expressionBodies(value).forEach { body ->
            require(!context.containsMatchIn(named.replace(body, ""))) {
                "Use explicit named secret references, not indirect secrets context access"
            }
            named.findAll(body).forEach { add(it.groupValues[1].uppercase()) }
        }
    }
}

// String literals can contain braces and the word "secrets"; neither is expression syntax.
internal fun expressionBodies(value: String): Sequence<String> = sequence {
    var end = 0
    while (true) {
        val start = value.indexOf("\${{", end)
        if (start < 0) break
        var cursor = start + 3
        val body = StringBuilder()
        var quoted = false
        while (cursor < value.length) {
            if (!quoted && value.startsWith("}}", cursor)) break
            val char = value[cursor++]
            if (char == '\'') {
                if (quoted && value.getOrNull(cursor) == '\'') cursor++ else quoted = !quoted
                body.append(' ')
            } else body.append(if (quoted) ' ' else char)
        }
        require(cursor < value.length) { "Unterminated workflow expression" }
        yield(body.toString())
        end = cursor + 2
    }
}
