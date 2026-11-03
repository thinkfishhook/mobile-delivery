package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class QuickstartTest {
    private val repository = Path.of(System.getProperty("delivery.root"))
    private val overlay = repository.resolve("examples/quickstart")

    @Test
    fun `README and quickstart pin all four composites to the same v1 release`() {
        val composites =
            setOf("testflight", "maintenance", "app-store-release", "app-store-rollout")
        val reference =
            Regex("thinkfishhook/mobile-delivery/([a-z-]+)@([a-f0-9]{40}) # ([^\\r\\n]+)")
        val files =
            overlay.resolve(".github/workflows").listDirectoryEntries("*.yml") +
                listOf(repository.resolve("README.md"))
        val pins = files.flatMap { reference.findAll(it.readText()).toList() }
        assertEquals(setOf("v1.0.0"), pins.map { it.groupValues[3] }.toSet())
        assertEquals(8, pins.size)
        for (file in files) {
            val matches = reference.findAll(file.readText()).toList()
            assertEquals(
                if (file.name == "README.md") composites else setOf(file.nameWithoutExtension),
                matches.map { it.groupValues[1] }.toSet(),
                file.name,
            )
        }
        assertEquals(1, pins.map { it.groupValues[2] }.toSet().size)
    }

    @Test
    fun `every quickstart push workflow excludes receipt tags`() {
        val workflows =
            overlay.resolve(".github/workflows").listDirectoryEntries().filter {
                it.extension in setOf("yml", "yaml")
            }
        assertTrue(workflows.isNotEmpty())
        for (file in workflows) {
            val document = workflowDocument(file.readText())
            // SnakeYAML's YAML 1.1 resolver treats unquoted on as true.
            val events = yamlMapping(document["on"] ?: document[true])
            if ("push" !in events) continue
            val push = yamlMapping(events["push"])
            val branches = push["branches"] as? List<*>
            val ignored = (push["tags-ignore"] as? List<*>).orEmpty()
            assertTrue(
                (branches?.isNotEmpty() == true && "tags" !in push) ||
                    ("deploy/**" in ignored && "submit/**" in ignored),
                "${file.name} must filter pushes to branches or ignore both receipt namespaces",
            )
        }
    }

    @Test
    fun `Dependabot groups all mobile delivery composites`() {
        val document = workflowDocument(overlay.resolve(".github/dependabot.yml").readText())
        val updates = (document["updates"] as List<*>).map(::yamlMapping)
        val actions = updates.single {
            it["package-ecosystem"] == "github-actions" && it["directory"] == "/"
        }
        val groups = yamlMapping(actions["groups"])
        assertTrue(
            groups.values.map(::yamlMapping).any {
                "thinkfishhook/mobile-delivery*" in (it["patterns"] as? List<*>).orEmpty()
            }
        )
    }

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
