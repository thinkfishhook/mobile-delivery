package delivery

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class GateTest {
    @Test
    fun `deployment gate crosses events branches and exact switch values`() = temp { root ->
        val output = root.resolve("output")
        for (mode in listOf("delivery", "release", "rollout")) for (event in
            listOf("push", "schedule", "workflow_dispatch")) for (branch in
            listOf("main", "other")) for (switch in listOf(null, "", "ture", "false", "true")) {
            output.writeText("")
            val name = if (mode == "delivery") "IOS_DELIVERY_ENABLED" else "IOS_APPSTORE_ENABLED"
            val env =
                mapOf(
                    "DELIVERY_VALIDATION_MODE" to mode,
                    "DELIVERY_ACTION_BRANCH" to "main",
                    "GITHUB_REF" to "refs/heads/$branch",
                    "GITHUB_EVENT_NAME" to event,
                    "GITHUB_OUTPUT" to output.toString(),
                ) + listOfNotNull(switch?.let { name to it })
            val stdout = ByteArrayOutputStream()
            val original = System.out
            val result =
                try {
                    System.setOut(PrintStream(stdout))
                    runCatching { runTask(arrayOf("gate"), root, env, RecordingCommands()) }
                } finally {
                    System.setOut(original)
                }
            val label = "$mode $event $branch $switch"
            if (switch !in setOf("true", "false")) {
                assertTrue(result.isFailure, label)
                assertContains(
                    result.exceptionOrNull()?.message.orEmpty(),
                    "$name must be exactly true or false",
                )
            } else if (branch == "main" && switch == "true") {
                result.getOrThrow()
                assertEquals("run=true\nverify=false\n", output.readText(), label)
            } else {
                val reason =
                    if (branch != "main") "Run this workflow from branch main" else "Set $name=true"
                if (event == "workflow_dispatch") {
                    assertTrue(result.isFailure, label)
                    assertContains(result.exceptionOrNull()?.message.orEmpty(), reason)
                } else {
                    result.getOrThrow()
                    val verify = mode == "delivery" && branch == "main" && switch == "false"
                    assertEquals("run=false\nverify=$verify\n", output.readText(), label)
                    assertContains(stdout.toString(), "::notice::")
                    assertContains(stdout.toString(), reason)
                }
            }
        }
    }

    @Test
    fun `maintenance diagnostics need the branch gate but no deployment switch`() = temp { root ->
        val output = root.resolve("output").createFile()
        val env =
            mapOf(
                "DELIVERY_VALIDATION_MODE" to "maintenance",
                "DELIVERY_ACTION_BRANCH" to "main",
                "GITHUB_REF" to "refs/heads/main",
                "GITHUB_EVENT_NAME" to "workflow_dispatch",
                "GITHUB_OUTPUT" to output.toString(),
            )
        runTask(arrayOf("gate"), root, env, RecordingCommands())
        assertEquals("run=true\nverify=false\n", output.readText())
        assertFailsWith<IllegalStateException> {
            runTask(
                arrayOf("gate"),
                root,
                env + ("GITHUB_REF" to "refs/heads/other"),
                RecordingCommands(),
            )
        }
    }
}
