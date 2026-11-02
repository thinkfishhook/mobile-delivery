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
