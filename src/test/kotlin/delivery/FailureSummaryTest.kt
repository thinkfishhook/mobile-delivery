package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class FailureSummaryTest {
    @Test
    fun `JSON round trips failures from one or multiple reports with context`() = temp { root ->
        for (reports in listOf(listOf("unit"), listOf("unit", "ui.xcresult"))) {
            val failures = reports.mapIndexed { index, report ->
                TestFailure(
                    "Suite.test$index",
                    "expected value",
                    "Source.kt",
                    42,
                    report,
                    "test --report $report",
                )
            }
            writeRunSummary(
                root,
                emptyMap(),
                RunSummary("failed", "No delivery", emptyList(), emptyList(), failures = failures),
            )
            val encoded = root.resolve("build/delivery/run-summary.json").readText()
            assertEquals(failures, json.decodeFromString<RunSummary>(encoded).failures)
            assertContains(encoded, "\"failures\"")
            assertContains(encoded, "\"report\"")
            assertContains(encoded, "\"command\"")
        }
    }

    @Test
    fun `table escapes markup pipes and newlines in every cell and appends to summary`() =
        temp { root ->
            val step = root.resolve("summary.md").apply { writeText("Existing summary\n") }
            val failure =
                TestFailure(
                    "Suite|test",
                    "one|two\r\nthree\rfour\nfive<script>",
                    "Source|file.kt",
                    12,
                    "unit|report",
                    "test | check",
                )
            writeRunSummary(
                root,
                mapOf("GITHUB_STEP_SUMMARY" to step.toString()),
                RunSummary(
                    "failed",
                    "No delivery",
                    emptyList(),
                    emptyList(),
                    failures = listOf(failure),
                ),
            )
            val table = step.readText().substringAfter("### Failing tests")
            assertContains(step.readText(), "Existing summary")
            assertContains(table, "| Report | Command | Test | Message | Location |")
            assertContains(table, "one&#124;two<br>three<br>four<br>five&lt;script&gt;")
            assertContains(table, "Suite&#124;test")
            assertContains(table, "Source&#124;file.kt:12")
            assertContains(table, "unit&#124;report")
            assertContains(table, "test &#124; check")
            assertEquals(3, table.lineSequence().count { it.startsWith("|") })
        }

    @Test
    fun `no failures writes no table`() = temp { root ->
        val step = root.resolve("summary.md").createFile()
        writeRunSummary(
            root,
            mapOf("GITHUB_STEP_SUMMARY" to step.toString()),
            RunSummary("completed", "Delivered", emptyList(), emptyList()),
        )
        assertFalse(step.readText().contains("### Failing tests"))
        assertEquals(
            emptyList(),
            json
                .decodeFromString<RunSummary>(
                    root.resolve("build/delivery/run-summary.json").readText()
                )
                .failures,
        )
    }

    @Test
    fun `over annotation cap every failure remains listed grouped by report`() = temp { root ->
        val step = root.resolve("summary.md").createFile()
        val failures =
            (1..24).map {
                TestFailure(
                    "Suite.test$it",
                    "failed",
                    report = if (it % 2 == 0) "ui" else "unit",
                    command = "test",
                )
            }
        writeRunSummary(
            root,
            mapOf("GITHUB_STEP_SUMMARY" to step.toString()),
            RunSummary("failed", "No delivery", emptyList(), emptyList(), failures = failures),
        )
        val rows =
            step
                .readText()
                .substringAfter("### Failing tests")
                .lineSequence()
                .filter { it.startsWith("| unit |") || it.startsWith("| ui |") }
                .toList()
        assertEquals(24, rows.size)
        assertTrue(rows.take(12).all { it.startsWith("| unit |") })
        assertTrue(rows.drop(12).all { it.startsWith("| ui |") })
        failures.forEach { assertEquals(1, rows.count { row -> "| ${it.test} |" in row }) }
        assertEquals(
            failures,
            json
                .decodeFromString<RunSummary>(
                    root.resolve("build/delivery/run-summary.json").readText()
                )
                .failures,
        )
    }
}
