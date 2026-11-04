package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class WorkflowChecksTest {
    private val composites =
        listOf("testflight", "maintenance", "app-store-release", "app-store-rollout")

    private fun copyComposites(root: Path) {
        val repository = Path.of(System.getProperty("delivery.root"))
        composites.forEach { action ->
            val target = root.resolve("$action/action.yml")
            target.parent.createDirectories()
            repository.resolve("$action/action.yml").copyTo(target, overwrite = true)
        }
    }

    private fun rejectPhaseChanges(change: (List<Map<*, *>>, Map<*, *>) -> List<Map<*, *>>) =
        temp { root ->
            copyComposites(root)
            val operations =
                mapOf(
                    "testflight" to
                        listOf(
                            "selectXcode",
                            "startRun",
                            "setupAndroid",
                            "verify",
                            "buildBeta",
                            "uploadBeta",
                            "finishRun",
                        ),
                    "maintenance" to listOf("sweep", "audit", "appStorePreflight", "verifySigning"),
                    "app-store-release" to listOf("appStoreRelease"),
                    "app-store-rollout" to listOf("rolloutControl"),
                )
            for ((action, ownPhases) in operations) {
                val path = root.resolve("$action/action.yml")
                val original = path.readText()
                val document = workflowDocument(original)
                val runs = yamlMapping(document["runs"])
                val steps = workflowSteps(runs)
                for (phase in
                    listOf("gate", "jobPolicy", "validate", "installAsc", "compositeSummary") +
                        ownPhases) {
                    val selected = steps.single { it["run"] == "mobile-delivery $phase" }
                    val changed =
                        document + ("runs" to (runs + ("steps" to change(steps, selected))))
                    path.writeText(Yaml().dump(changed))
                    val failure =
                        assertFailsWith<IllegalArgumentException>("$action $phase") {
                            CompositeChecks().validate(root, listOf(action))
                        }
                    assertContains(failure.message.orEmpty(), phase)
                }
                path.writeText(original)
            }
        }

    @Test
    fun `evidence policy accepts interrupted real deliveries but rejects cancellation suppression`() =
        temp { root ->
            copyComposites(root)
            val path = root.resolve("testflight/action.yml")
            val original = path.readText()
            val restored =
                original.replace(
                    "if: \${{ !cancelled() && steps.start.outputs.operation != 'none' }}",
                    "if: \${{ always() && steps.start.outputs.operation != 'none' }}",
                )
            path.writeText(restored)
            CompositeChecks().validate(root, listOf("testflight"))
            path.writeText(
                restored.replace(
                    "always() && steps.start.outputs.operation != 'none'",
                    "!cancelled() && steps.start.outputs.operation != 'none'",
                )
            )
            assertFails { CompositeChecks().validate(root, listOf("testflight")) }
        }

    @Test
    fun `composites cannot silently lose a required phase`() = rejectPhaseChanges { steps, phase ->
        steps.filter { it !== phase }
    }

    @Test
    fun `composites cannot run a required phase twice`() = rejectPhaseChanges { steps, phase ->
        steps + listOf(phase + ("id" to "duplicate-phase"))
    }

    @Test
    fun `repository checks reject every missing supported composite`() = temp { root ->
        root.resolve(".github/workflows").createDirectories()
        copyComposites(root)
        WorkflowChecks().validate(root)
        for (action in composites) {
            val path = root.resolve("$action/action.yml")
            val original = path.readText()
            path.deleteExisting()
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    WorkflowChecks().validate(root)
                }
            assertContains(failure.message.orEmpty(), "$action/action.yml")
            path.writeText(original)
        }
    }

    @Test
    fun `own CI workflows validate without reusable delivery files`() = temp { root ->
        copyComposites(root)
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
            CompositeChecks().validate(root, listOf("testflight"))
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
                        "always() && steps.start.outputs.operation != 'none'",
                        "always()",
                    ),
                    original.replace(
                        "!cancelled() && steps.start.outcome == 'success'",
                        "success()",
                    ),
                )) {
                assertNotEquals(original, changed)
                path.writeText(changed)
                assertFails { CompositeChecks().validate(root, listOf("testflight")) }
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
            CompositeChecks().validate(root, listOf("maintenance"))
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
                assertFails { CompositeChecks().validate(root, listOf("maintenance")) }
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
        CompositeChecks().validate(root, listOf("app-store-release"))
        for (changed in
            listOf(
                original.replace("if: always()\n", "if: success()\n"),
                original.replace("fetch-depth: 0", "fetch-depth: 1"),
            )) {
            path.writeText(changed)
            assertFails { CompositeChecks().validate(root, listOf("app-store-release")) }
        }
    }

    private fun movePhaseStep(
        text: String,
        selected: String,
        target: String,
        after: Boolean = false,
    ): String {
        val document = workflowDocument(text)
        val runs = yamlMapping(document["runs"])
        val steps = workflowSteps(runs).toMutableList()
        val moved = steps.single { it["id"] == selected || it["run"] == selected }
        steps.remove(moved)
        val index = steps.indexOfFirst { it["id"] == target || it["run"] == target }
        require(index >= 0)
        steps.add(index + if (after) 1 else 0, moved)
        return Yaml().dump(document + ("runs" to (runs + ("steps" to steps))))
    }

    @Test
    fun `composite checks own credential routing ordering retention and shared installation`() =
        temp { root ->
            val action = root.resolve("testflight/action.yml")
            action.parent.createDirectories()
            val fixture =
                Path.of(System.getProperty("delivery.root"))
                    .resolve("testflight/action.yml")
                    .readText()
            val pin = Regex("uses: [^@\\n]+@([a-f0-9]{40})").find(fixture)!!.groupValues[1]
            action.writeText(fixture)
            CompositeChecks().validate(root, listOf("testflight"))
            val mutations: List<(String) -> String> =
                listOf(
                    {
                        it.replace(
                            "run: mobile-delivery verify",
                            "run: mobile-delivery verify\n      env:\n        ASC_PRIVATE_KEY: \${{ inputs.asc-private-key }}",
                        )
                    },
                    {
                        it.replace(
                            "run: mobile-delivery installAsc",
                            "run: mobile-delivery installAsc\n      env:\n        IOS_RECEIPT_TOKEN: \${{ inputs.receipt-token }}",
                        )
                    },
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
                        movePhaseStep(text, "package", "mobile-delivery uploadBeta", after = true)
                    },
                    { text -> movePhaseStep(text, "mobile-delivery finishRun", "evidence") },
                    {
                        it.replace(
                            "!cancelled() && steps.start.outputs.operation != 'none'",
                            "success()",
                        )
                    },
                    { it.replace("id: engine-cache", "id: other-cache") },
                    {
                        it.replace(
                            "run: mobile-delivery buildBeta",
                            "run: mobile-delivery buildBeta\n      working-directory: \${{ inputs.asc-private-key }}",
                        )
                    },
                )
            mutations.forEachIndexed { index, mutate ->
                val changed = mutate(fixture)
                assertNotEquals(fixture, changed, "Mutation $index must change the fixture")
                action.writeText(changed)
                assertFails("Composite mutation $index should fail") {
                    CompositeChecks().validate(root, listOf("testflight"))
                }
            }
            action.writeText(fixture)
            val other = root.resolve("app-store-rollout/action.yml")
            other.parent.createDirectories()
            other.writeText(
                fixture.replace(
                    "internal/engine/install.sh",
                    "internal/engine/different-install.sh",
                )
            )
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    CompositeChecks().validate(root, listOf("testflight", "app-store-rollout"))
                }
            assertContains(failure.message.orEmpty(), "installation steps must match")
        }

    @Test
    fun `local TestFlight callers require the write grant and reject broader scopes`() =
        temp { root ->
            copyComposites(root)
            val file = root.resolve(".github/workflows/caller.yml")
            file.parent.createDirectories()
            val minimum = "{ contents: read, actions: write, issues: write }"
            for (permissions in
                listOf(
                    minimum,
                    minimum.replace("actions: write", "actions: read"),
                    minimum.replace("contents: read", "contents: write"),
                )) {
                file.writeText(
                    """
                on: workflow_dispatch
                jobs:
                  ship:
                    runs-on: ubuntu-latest
                    permissions: $permissions
                    concurrency: { group: ios-delivery, queue: max, cancel-in-progress: false }
                    steps:
                      - uses: ./testflight
            """
                        .trimIndent()
                )
                if (permissions == minimum) WorkflowChecks().validate(root)
                else {
                    val failure =
                        assertFailsWith<IllegalArgumentException> {
                            WorkflowChecks().validate(root)
                        }
                    assertContains(failure.message.orEmpty(), "permissions: $minimum")
                }
            }
        }

    @Test
    fun `CI checks retain pinned actions scoped values and unpersisted checkout`() = temp { root ->
        copyComposites(root)
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
