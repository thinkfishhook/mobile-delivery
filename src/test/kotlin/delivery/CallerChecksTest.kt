package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class CallerChecksTest {
    @Test
    fun `caller checks operate without tool sources and reject mutable or incoherent pins`() =
        temp { root ->
            val path = root.resolve(".github/workflows/verify.yml")
            path.parent.createDirectories()
            val valid =
                """
                name: Dependency PR verification
                on: { pull_request: { types: [opened, synchronize] } }
                permissions: { contents: read }
                jobs:
                  verify:
                    runs-on: macos-26
                    steps:
                      - uses: thinkfishhook/mobile-delivery/verify@${"a".repeat(40)}
                      - uses: thinkfishhook/mobile-delivery/select-xcode@${"a".repeat(40)}
                """
                    .trimIndent()
            path.writeText(valid)
            runTask(arrayOf("callerChecks"), root, emptyMap(), RecordingCommands())
            val setup = root.resolve(".github/actions/setup/action.yml")
            setup.parent.createDirectories()
            setup.writeText(
                """runs: { using: composite, steps: [{ uses: 'thinkfishhook/mobile-delivery/select-xcode@${"b".repeat(40)}' }] }"""
            )
            assertFails { CallerChecks().validate(root) }
            setup.deleteExisting()

            for (changed in
                listOf(
                    valid.replace("verify@${"a".repeat(40)}", "verify@v1"),
                    valid.replace(
                        "select-xcode@${"a".repeat(40)}",
                        "select-xcode@${"b".repeat(40)}",
                    ),
                    valid.replace("steps:", "env: { ASC_PRIVATE_KEY: secret }\n    steps:"),
                    valid.replace(
                        "- uses:",
                        "- env: { KEY: '${'$'}{{ secrets.OPENAI_API_KEY }}' }\n        uses:",
                    ),
                )) {
                path.writeText(changed)
                assertFails {
                    runTask(arrayOf("callerChecks"), root, emptyMap(), RecordingCommands())
                }
            }
        }

    @Test
    fun `declared app values survive while delivery and provider credentials remain forbidden`() =
        temp { root ->
            root
                .resolve(".github")
                .createDirectories()
                .resolve("delivery.json")
                .writeText("""{"build_environment":["ANALYTICS_KEY"]}""")
            val path = root.resolve(".github/workflows/verify.yml")
            path.parent.createDirectories()
            val valid =
                """
                name: Verification
                on: { pull_request: null }
                permissions: { contents: read }
                jobs:
                  verify:
                    runs-on: macos-26
                    steps:
                      - uses: thinkfishhook/mobile-delivery/verify@${"a".repeat(40)}
                        env: { ANALYTICS_KEY: '${'$'}{{ secrets.ANALYTICS_KEY }}' }
                """
                    .trimIndent()
            path.writeText(valid)
            CallerChecks().validate(root)
            for (key in listOf("ASC_PRIVATE_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN")) {
                path.writeText(valid.replace("ANALYTICS_KEY", key))
                assertFails { CallerChecks().validate(root) }
            }
        }

    @Test
    fun `native and KMP examples pass caller policy and reject unsafe manual deployment`() =
        temp { root ->
            val repository = Path.of(System.getProperty("delivery.root"))
            for (example in listOf("xcode", "kmp")) {
                val source = repository.resolve("examples/$example/.github/workflows")
                assertTrue(source.isDirectory(), "Missing $example callers")
                val target = root.resolve(".github/workflows").createDirectories()
                target.listDirectoryEntries().forEach { it.deleteExisting() }
                source.listDirectoryEntries("*.yml").forEach { it.copyTo(target.resolve(it.name)) }
                CallerChecks().validate(root)
                val deploy = target.resolve("testflight-deploy.yml")
                val original = deploy.readText()
                for (changed in
                    listOf(
                        original.replace(
                            "  deploy:\n",
                            "  deploy:\n    if: github.ref == 'refs/heads/main'\n",
                        ),
                        original.replace(
                            "  workflow_dispatch:",
                            "  workflow_dispatch:\n    inputs: { artifact_id: { type: string } }",
                        ),
                    )) {
                    deploy.writeText(changed)
                    assertFails { CallerChecks().validate(root) }
                }
            }
        }
}
