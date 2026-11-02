package delivery

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class JobPolicyTest {
    private val minimum = "{ contents: read, actions: write, issues: write }"
    private val lock = "{ group: ios-delivery, queue: max, cancel-in-progress: false }"

    private fun run(
        root: Path,
        concurrency: String = lock,
        permissions: String = minimum,
        runner: String = "macos-26",
        mode: String = "delivery",
        workflowPermissions: String = "",
    ): String {
        root.resolve(".github/workflows/caller.yml").apply {
            parent.createDirectories()
            writeText(
                """
                name: Caller
                on: workflow_dispatch
                $workflowPermissions
                jobs:
                  ship:
                    runs-on: $runner
                    concurrency: $concurrency
                    ${if (permissions.isEmpty()) "" else "permissions: $permissions"}
                    env: { CUSTOM: value }
                    steps:
                      - run: echo 'caller preparation'
                      - uses: example/action@v1
            """
                    .trimIndent()
            )
        }
        val env =
            mapOf(
                "GITHUB_WORKFLOW_REF" to "owner/repo/.github/workflows/caller.yml@refs/heads/main",
                "GITHUB_JOB" to "ship",
                "DELIVERY_VALIDATION_MODE" to mode,
            )
        val stdout = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(stdout))
            runTask(arrayOf("jobPolicy"), root, env, RecordingCommands())
        } finally {
            System.setOut(original)
        }
        return stdout.toString()
    }

    @Test
    fun `TestFlight actions read is rejected with actions write correction`() = temp { root ->
        val failure =
            assertFailsWith<IllegalArgumentException> {
                run(root, permissions = "{ contents: read, actions: read, issues: write }")
            }
        assertContains(
            failure.message.orEmpty(),
            "permissions: { contents: read, actions: write, issues: write }",
        )
    }

    @Test
    fun `TestFlight actions write is accepted without a broader permission warning`() =
        temp { root ->
            assertEquals(
                "",
                run(root, permissions = "{ contents: read, actions: write, issues: write }"),
            )
        }

    @Test
    fun `maintenance actions write is rejected as broader than needed`() = temp { root ->
        val failure =
            assertFailsWith<IllegalArgumentException> {
                run(
                    root,
                    permissions = "{ contents: read, actions: write, issues: write }",
                    mode = "maintenance",
                )
            }
        assertContains(failure.message.orEmpty(), "broader than this action needs")
        assertContains(
            failure.message.orEmpty(),
            "permissions: { contents: read, actions: read, issues: write }",
        )
    }

    @Test
    fun `missing and unsafe queues name the caller and give the exact lock to paste`() =
        temp { root ->
            for (concurrency in
                listOf(
                    "",
                    "{ group: other, queue: max, cancel-in-progress: false }",
                    lock.replace("max", "single"),
                    lock.replace("false", "true"),
                )) {
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        run(root, concurrency = concurrency)
                    }
                assertContains(failure.message.orEmpty(), ".github/workflows/caller.yml job ship")
                assertContains(failure.message.orEmpty(), "concurrency: $lock")
            }
        }

    @Test
    fun `missing and broader permissions fail with a paste-ready minimum`() = temp { root ->
        for (permissions in
            listOf("{}", "{ contents: read, actions: read, issues: read }", "read-all")) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    run(root, permissions = permissions)
                }
            assertContains(failure.message.orEmpty(), ".github/workflows/caller.yml job ship")
            assertContains(failure.message.orEmpty(), "permissions: $minimum")
        }
        for (permissions in
            listOf(
                "{ contents: write, actions: write, issues: write, packages: write }",
                "write-all",
            )) {
            val failure =
                assertFailsWith<IllegalArgumentException> { run(root, permissions = permissions) }
            assertContains(failure.message.orEmpty(), "broader than this action needs")
            assertContains(failure.message.orEmpty(), ".github/workflows/caller.yml job ship")
            assertContains(failure.message.orEmpty(), "permissions: $minimum")
        }
    }

    @Test
    fun `runner syntax and additional caller steps do not change the policy`() = temp { root ->
        for (runner in
            listOf(
                "macos-26",
                "[self-hosted, macOS, ARM64]",
                "{ group: macs, labels: arm64 }",
                "\${{ matrix.os }}",
            )) {
            assertEquals("", run(root, runner = runner))
        }
    }

    @Test
    fun `workflow permissions inherit unless the job overrides them`() = temp { root ->
        assertEquals("", run(root, permissions = "", workflowPermissions = "permissions: $minimum"))
        assertFailsWith<IllegalArgumentException> {
            run(
                root,
                permissions = "{ contents: read }",
                workflowPermissions = "permissions: $minimum",
            )
        }
    }

    @Test
    fun `release and rollout have their own minimum and rollout has its own queue`() =
        temp { root ->
            assertEquals(
                "",
                run(
                    root,
                    permissions = "{ contents: read, actions: read, issues: read }",
                    mode = "release",
                ),
            )
            assertEquals(
                "",
                run(
                    root,
                    permissions = "{ contents: read }",
                    concurrency = lock.replace("ios-delivery", "ios-rollout"),
                    mode = "rollout",
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                run(root, permissions = "{ contents: read }", mode = "rollout")
            }
            assertEquals(
                "",
                run(
                    root,
                    permissions = "{ contents: read, actions: read, issues: write }",
                    mode = "maintenance",
                ),
            )
        }
}
