package delivery

internal data class Annotation(
    val level: String,
    val message: String,
    val title: String? = null,
    val file: String? = null,
    val line: Int? = null,
)

internal fun annotate(annotation: Annotation) {
    val properties =
        listOfNotNull(
                annotation.title?.let { "title=${escapeProperty(it)}" },
                annotation.file?.let { "file=${escapeProperty(it)}" },
                annotation.line?.let { "line=$it" },
            )
            .joinToString(",")
    println(
        "::${annotation.level}${if (properties.isEmpty()) "" else " $properties"}::${escapeData(annotation.message)}"
    )
}

private fun escapeData(value: String) =
    value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

private fun escapeProperty(value: String) =
    escapeData(value).replace(":", "%3A").replace(",", "%2C")

internal fun annotateTestFailures(failures: List<TestFailure>) {
    // GitHub allows ten error annotations per step; the caller also emits the command error.
    val limit = 9
    val visible = if (failures.size > limit) limit - 1 else failures.size
    failures.take(visible).forEach {
        annotate(Annotation("error", it.message, it.test, it.file, it.line))
    }
    if (failures.size > limit)
        annotate(
            Annotation(
                "error",
                "${failures.size - visible} more failing tests; see the test report evidence for details.",
            )
        )
}

// The run page keeps every failure; only annotations have a per-step cap.
internal fun failureTable(failures: List<TestFailure>): String {
    if (failures.isEmpty()) return ""
    return buildString {
        append("\n### Failing tests\n\n| Report | Command | Test | Message | Location |\n")
        append("| --- | --- | --- | --- | --- |\n")
        failures
            .groupBy { it.report }
            .values
            .flatten()
            .forEach { failure ->
                val location =
                    failure.file
                        ?.let { file ->
                            file + (failure.line?.let { ":$it" } ?: "")
                        }
                        .orEmpty()
                append("| ")
                append(
                    listOf(
                            failure.report ?: "Unknown report",
                            failure.command ?: "Unknown command",
                            failure.test,
                            failure.message,
                            location,
                        )
                        .joinToString(" | ") { summaryCell(it) }
                )
                append(" |\n")
            }
    }
}

private fun summaryCell(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("|", "&#124;")
        .replace("\\", "&#92;")
        .replace("`", "&#96;")
        .replace("*", "&#42;")
        .replace("_", "&#95;")
        .replace("[", "&#91;")
        .replace("]", "&#93;")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "<br>")
