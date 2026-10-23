package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class QuickstartTest {
    private val overlay =
        Path.of(System.getProperty("delivery.root")).resolve("examples/quickstart")

    @Test
    fun `editing only marked JSON values produces native and KMP configurations`() = temp { root ->
        for (name in listOf("delivery.json", "delivery.kmp.example.json")) {
            val source = overlay.resolve(".github/$name").readText()
            assertContains(source, "# CHANGE ME")
            val edited = source.replace(Regex(" # CHANGE ME[^\"]*"), "")
            root.resolve(".github/delivery.json").apply {
                parent.createDirectories()
                writeText(edited)
            }
            val config = DeliveryConfig.load(root, emptyMap())
            assertTrue(config.verification.testReports.isNotEmpty())
            if (name == "delivery.json") {
                assertEquals(emptyList(), config.androidPackages)
                for (report in config.verification.testReports) {
                    assertTrue(config.verification.commands.any { report in it })
                }
            } else {
                assertTrue(config.androidPackages.isNotEmpty())
            }
        }
    }

    @Test
    fun `copied callers meet job policy with their supplied permissions and queues`() =
        temp { root ->
            for ((file, job, mode) in
                listOf(
                    Triple("testflight", "testflight", "delivery"),
                    Triple("maintenance", "maintenance", "maintenance"),
                    Triple("app-store-release", "release", "release"),
                    Triple("app-store-rollout", "rollout", "rollout"),
                )) {
                val source = overlay.resolve(".github/workflows/$file.yml").readText()
                assertTrue(
                    Regex("thinkfishhook/mobile-delivery/[^@]+@[a-f0-9]{40} # v[0-9]")
                        .containsMatchIn(source)
                )
                root.resolve(".github/workflows/$file.yml").apply {
                    parent.createDirectories()
                    writeText(source)
                }
                runTask(
                    arrayOf("jobPolicy"),
                    root,
                    mapOf(
                        "GITHUB_WORKFLOW_REF" to
                            "owner/repo/.github/workflows/$file.yml@refs/heads/main",
                        "GITHUB_JOB" to job,
                        "DELIVERY_VALIDATION_MODE" to mode,
                    ),
                    RecordingCommands(),
                )
            }
        }
}
