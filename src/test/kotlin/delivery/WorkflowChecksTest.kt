package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class WorkflowChecksTest {
    @Test
    fun `own CI workflows validate without reusable delivery files`() = temp { root ->
        val repository = Path.of(System.getProperty("delivery.root"))
        val workflows = root.resolve(".github/workflows").createDirectories()
        for (file in
            listOf("check.yml", "self-test.yml", "composite-self-test.yml", "release.yml")) {
            repository.resolve(".github/workflows/$file").copyTo(workflows.resolve(file))
        }
        WorkflowChecks().validate(root)
    }

    @Test
    fun `TestFlight owns phase conditions and detects failures inside the composite`() =
        temp { root ->
            val original =
                Path.of(System.getProperty("delivery.root"))
                    .resolve("testflight/action.yml")
                    .readText()
            val path = root.resolve("testflight/action.yml")
            path.parent.createDirectories()
            path.writeText(original)
            CompositeChecks().validate(root)
            val failed = Regex("IOS_EXECUTION_FAILED: .*\\n").find(original)!!.value
            for (changed in
                listOf(
                    original.replace(
                        failed,
                        "IOS_EXECUTION_FAILED: \${{ job.status != 'success' }}\n",
                    ),
                    original.replace(" || steps.evidence.outcome == 'failure'", ""),
                    original.replace("if: steps.prepare.outputs.publish == 'true'", "if: always()"),
                    original.replace("if: steps.prepare.outputs.retain == 'true'", "if: always()"),
                    original.replace("id: prepare", "id: build"),
                    original.replace(
                        "!cancelled() && steps.start.outcome == 'success'",
                        "success()",
                    ),
                )) {
                assertNotEquals(original, changed)
                path.writeText(changed)
                assertFails { CompositeChecks().validate(root) }
            }
        }

    @Test
    fun `maintenance signing stays outside API credentials and evidence retention`() =
        temp { root ->
            val original =
                Path.of(System.getProperty("delivery.root"))
                    .resolve("maintenance/action.yml")
                    .readText()
            val path = root.resolve("maintenance/action.yml")
            path.parent.createDirectories()
            path.writeText(original)
            CompositeChecks().validate(root)
            for (changed in
                listOf(
                    original.replace(
                        "if: always() && env.DELIVERY_OPERATION != 'verify-signing'",
                        "if: always()",
                    ),
                    original.replace(
                        "        IOS_PROFILE_BASE64:",
                        "        ASC_PRIVATE_KEY: \${{ inputs.asc-private-key }}\n        IOS_PROFILE_BASE64:",
                    ),
                )) {
                path.writeText(changed)
                assertFails { CompositeChecks().validate(root) }
            }
        }

    @Test
    fun `release composite cannot lose its unconditional evidence retention`() = temp { root ->
        val original =
            Path.of(System.getProperty("delivery.root"))
                .resolve("app-store-release/action.yml")
                .readText()
        val path = root.resolve("app-store-release/action.yml")
        path.parent.createDirectories()
        path.writeText(original)
        CompositeChecks().validate(root)
        for (changed in
            listOf(
                original.replace("if: always()\n", "if: success()\n"),
                original.replace("fetch-depth: 0", "fetch-depth: 1"),
            )) {
            path.writeText(changed)
            assertFails { CompositeChecks().validate(root) }
        }
    }

    @Test
    fun `composite checks own credential routing ordering retention and shared installation`() =
        temp { root ->
            val repository = Path.of(System.getProperty("delivery.root"))
            val workflows = root.resolve(".github/workflows").createDirectories()
            repository.resolve(".github/workflows").listDirectoryEntries("*.yml").forEach {
                it.copyTo(workflows.resolve(it.fileName))
            }
            val action = root.resolve("testflight/action.yml")
            action.parent.createDirectories()
            val pin = "a".repeat(40)
            val fixture =
                """
            name: Testflight
            runs:
              using: composite
              steps:
                - id: engine-key
                  run: bash cache-key.sh
                - id: engine-cache
                  uses: actions/cache@$pin
                - id: engine-gradle
                  uses: actions/cache@$pin
                - id: engine-install
                  run: bash install.sh
                - run: mobile-delivery selectXcode
                - id: gate
                  run: mobile-delivery gate
                - run: mobile-delivery jobPolicy
                - run: mobile-delivery validate
                  env:
                    DELIVERY_SECRET_PRESENCE: >-
                      {"ASC_PRIVATE_KEY": ${'$'}{{ inputs.asc-private-key != '' }}}
                - run: mobile-delivery buildBeta
                  env:
                    ASC_PRIVATE_KEY: ${'$'}{{ inputs.asc-private-key }}
                    IOS_PROFILE_BASE64: ${'$'}{{ inputs.provisioning-profile }}
                - id: package
                  uses: actions/upload-artifact@$pin
                  with:
                    name: ${'$'}{{ steps.prepare.outputs.artifact_name }}
                    retention-days: 90
                    if-no-files-found: error
                - run: mobile-delivery uploadBeta
                  env:
                    IOS_RECEIPT_TOKEN: ${'$'}{{ inputs.receipt-token }}
                - id: evidence
                  if: always()
                  uses: actions/upload-artifact@$pin
                  with:
                    name: evidence
                    retention-days: 90
                - run: mobile-delivery finishRun
        """
                    .trimIndent()
            action.writeText(fixture)
            WorkflowChecks().validate(root)
            val mutations: List<(String) -> String> =
                listOf(
                    { it.replace("mobile-delivery buildBeta", "mobile-delivery verify") },
                    { it.replace("mobile-delivery uploadBeta", "mobile-delivery installAsc") },
                    { it.replace("inputs.asc-private-key != ''", "inputs.asc-private-key") },
                    { it.replace("inputs.asc-private-key }}", "toJSON(inputs) }}") },
                    { it.replace("inputs.asc-private-key }}", "toJSON(secrets) }}") },
                    { it.replace("inputs.asc-private-key }}", "inputs['asc-private-key'] }}") },
                    { it.replace("inputs.provisioning-profile }}", "github.token }}") },
                    { it.replace("inputs.provisioning-profile }}", "github['token'] }}") },
                    { it.replace("inputs.provisioning-profile }}", "toJSON(github) }}") },
                    { it.replace("@$pin", "@v1") },
                    { it.replace("retention-days: 90", "retention-days: 7") },
                    { it.replace("if-no-files-found: error", "if-no-files-found: warn") },
                    { it.replace("mobile-delivery gate", "mobile-delivery audit") },
                    { it.replace("mobile-delivery jobPolicy", "mobile-delivery audit") },
                    { it.replace("mobile-delivery validate", "mobile-delivery audit") },
                    { text ->
                        val start = text.indexOf("    - id: package")
                        val upload = text.indexOf("    - run: mobile-delivery uploadBeta")
                        val end = text.indexOf("    - id: evidence")
                        text.take(start) +
                            text.substring(upload, end) +
                            text.substring(start, upload) +
                            text.substring(end)
                    },
                    {
                        it.replace(
                            "- id: evidence",
                            "- run: mobile-delivery finishRun\n    - id: evidence",
                        )
                    },
                    { it.replace("if: always()", "if: success()") },
                    { it.replace("id: engine-cache", "id: other-cache") },
                    {
                        it.replace(
                            "run: mobile-delivery buildBeta",
                            "run: mobile-delivery buildBeta\n      name: \${{ inputs.asc-private-key }}",
                        )
                    },
                )
            mutations.forEachIndexed { index, mutate ->
                val changed = mutate(fixture)
                assertNotEquals(fixture, changed, "Mutation $index must change the fixture")
                action.writeText(changed)
                assertFails("Composite mutation $index should fail") {
                    WorkflowChecks().validate(root)
                }
            }
            action.writeText(fixture)
            val other = root.resolve("app-store-rollout/action.yml")
            other.parent.createDirectories()
            other.writeText(
                fixture.replace("run: bash install.sh", "run: bash different-install.sh")
            )
            assertFails { WorkflowChecks().validate(root) }
        }

    @Test
    fun `CI checks retain pinned actions scoped values and unpersisted checkout`() = temp { root ->
        val original =
            Path.of(System.getProperty("delivery.root"))
                .resolve(".github/workflows/check.yml")
                .readText()
        val file = root.resolve(".github/workflows/check.yml")
        file.parent.createDirectories()
        file.writeText(original.replace("name: Check", "name: Quality gates"))
        WorkflowChecks().validate(root)
        for (changed in
            listOf(
                original.replace("@3d3c42e5aac5ba805825da76410c181273ba90b1", "@v7"),
                original.replace("persist-credentials: false", "persist-credentials: true"),
                original.replace("permissions:", "env: {TOKEN: example}\npermissions:"),
                original.replace("    steps:", "    env: {TOKEN: example}\n    steps:"),
                original.replace(
                    "persist-credentials: false",
                    "persist-credentials: false\n          persist-credentials: true",
                ),
            )) {
            assertNotEquals(original, changed)
            file.writeText(changed)
            assertFails { WorkflowChecks().validate(root) }
        }
    }
}
