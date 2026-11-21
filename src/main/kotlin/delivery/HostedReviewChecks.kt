package delivery

import java.nio.file.Path
import kotlin.io.path.*

internal class HostedReviewChecks {
    fun validate(root: Path) {
        val path = root.resolve("ai-review/action.yml")
        if (!path.exists()) return
        val document = workflowDocument(path.readText())
        val steps = workflowSteps(yamlMapping(document["runs"]))
        require(yamlMapping(document["runs"])["using"] == "composite" && steps.size == 7) {
            "AI review must retain isolated checkout/prepare/provider/publication phases"
        }
        require(
            yamlMapping(document["inputs"]).keys ==
                setOf("branch", "provider", "model", "provider-api-key", "github-token")
        ) {
            "AI review accepts only selected provider configuration and a scoped publisher token"
        }
        val prepare = steps[2]
        val codex = steps[3]
        val installClaude = steps[4]
        val claude = steps[5]
        val publish = steps[6]
        require(
            prepare["id"] == "prepare" &&
                codex["id"] == "codex" &&
                installClaude["id"] == "install-claude" &&
                claude["id"] == "claude"
        ) {
            "AI review phase identities must remain stable"
        }
        require(
            prepare["run"] == "node \"\$ACTION_PATH/../review/hosted.mjs\" prepare" &&
                claude["run"] == "node \"\$ACTION_PATH/../review/hosted.mjs\" claude" &&
                publish["run"] == "node \"\$ACTION_PATH/../review/hosted.mjs\" publish" &&
                publish["if"] == "always()"
        ) {
            "Always expose an explicit review result, including provider failures"
        }
        require(
            codex["if"] == "steps.prepare.outputs.ready == 'true' && inputs.provider == 'codex'" &&
                claude["if"] ==
                    "steps.prepare.outputs.ready == 'true' && inputs.provider == 'claude' && steps.install-claude.outcome == 'success'" &&
                installClaude["if"] ==
                    "steps.prepare.outputs.ready == 'true' && inputs.provider == 'claude'"
        ) {
            "Only the selected provider may run or receive credentials"
        }
        val settings = yamlMapping(codex["with"])
        require(
            codex["uses"].toString().startsWith("openai/codex-action@") &&
                settings["safety-strategy"] == "drop-sudo" &&
                settings["sandbox"] == "read-only" &&
                settings["codex-home"] == "\${{ steps.prepare.outputs.home }}" &&
                settings["working-directory"] == "\${{ steps.prepare.outputs.working }}" &&
                settings["output-schema-file"] == "\${{ steps.prepare.outputs.schema }}" &&
                settings["codex-version"].toString().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))
        ) {
            "Use the pinned official Codex proxy with isolated trusted config, read-only permissions and a strict schema"
        }
        require(
            installClaude["run"]
                .toString()
                .matches(
                    Regex("npm install --global @anthropic-ai/claude-code@[0-9]+\\.[0-9]+\\.[0-9]+")
                )
        ) {
            "Install a pinned Claude interface before passing credentials"
        }
        val expected =
            mapOf(
                2 to
                    setOf(
                        "ACTION_PATH",
                        "AI_REVIEW_BRANCH",
                        "AI_REVIEW_PROVIDER",
                        "AI_REVIEW_MODEL",
                        "AI_REVIEW_KEY_PRESENT",
                        "GH_TOKEN",
                    ),
                5 to setOf("ACTION_PATH", "AI_REVIEW_PLAN", "ANTHROPIC_API_KEY"),
                6 to
                    setOf(
                        "ACTION_PATH",
                        "AI_REVIEW_PLAN",
                        "AI_REVIEW_PROVIDER_OUTCOME",
                        "GH_TOKEN",
                    ),
            )
        for ((index, step) in steps.withIndex()) {
            require(secretReferences(step.toString()).isEmpty()) {
                "AI composites receive named inputs, never the secrets context"
            }
            step["uses"]?.let {
                require(Regex("[\\w./-]+@[a-f0-9]{40}").matches(it.toString())) {
                    "AI dependencies must be pinned to full commits"
                }
            }
            val env = (step["env"] as? Map<*, *>).orEmpty()
            require(env.keys == expected[index].orEmpty()) {
                "AI credentials/configuration escaped their phase"
            }
            val key = "inputs.provider-api-key"
            if (step.toString().contains(key)) {
                val with = (step["with"] as? Map<*, *>).orEmpty()
                require(
                    !step.filterKeys { it !in setOf("env", "with") }.toString().contains(key) &&
                        with
                            .filterValues { it.toString().contains(key) }
                            .all { (name, value) ->
                                index == 3 &&
                                    name == "openai-api-key" &&
                                    value == "\${{ inputs.provider-api-key }}"
                            } &&
                        env.filterValues { it.toString().contains(key) }
                            .all { (name, value) ->
                                (index == 2 &&
                                    name == "AI_REVIEW_KEY_PRESENT" &&
                                    value == "\${{ inputs.provider-api-key != '' }}") ||
                                    (index == 5 &&
                                        name == "ANTHROPIC_API_KEY" &&
                                        value == "\${{ inputs.provider-api-key }}")
                            }
                ) {
                    "The API key belongs only to its selected provider invocation"
                }
            }
            require(
                env.filterValues { it.toString().contains("inputs.github-token") }
                    .all { (name, value) ->
                        index in setOf(2, 6) &&
                            name == "GH_TOKEN" &&
                            value == "\${{ inputs.github-token }}"
                    }
            ) {
                "GitHub access belongs only to context retrieval and publication"
            }
        }
        val checkout = yamlMapping(steps[0]["with"])
        require(
            steps[0]["uses"].toString().startsWith("actions/checkout@") &&
                checkout["ref"] == "\${{ github.sha }}" &&
                checkout["fetch-depth"] == 0 &&
                checkout["persist-credentials"] == false
        ) {
            "Review checkout must retain exact landed source and no persisted token"
        }
    }
}

internal fun validateHostedReviewWorkflow(document: Map<*, *>, name: String) {
    val jobs = yamlMapping(document["jobs"])
    val job = yamlMapping(jobs[name])
    val steps = workflowSteps(job)
    require(
        document["name"] == "AI Code Review" &&
            jobs.size == 1 &&
            job["needs"] == null &&
            job["strategy"] == null &&
            job["if"] == null
    ) {
        "AI review must be its own independent workflow outside CI/deploy aggregate success"
    }
    require(job["runs-on"] == "ubuntu-latest") {
        "Run the privilege-dropping reviewer on an ephemeral GitHub Ubuntu runner"
    }
    require(
        document["env"] == null &&
            job["env"] == null &&
            steps.size == 1 &&
            steps.single()["env"] == null
    ) {
        "AI review may not inherit app/Apple/signing environments or execute caller scripts"
    }
    require(
        (job["permissions"] ?: document["permissions"]) ==
            mapOf("contents" to "read", "issues" to "write")
    ) {
        "AI review needs exactly Contents read and Issues write"
    }
    val queue = yamlMapping(job["concurrency"])
    require(
        queue["group"] == "mobile-delivery-ai-review" &&
            queue["queue"] == "max" &&
            queue["cancel-in-progress"] == false
    ) {
        "Serialize AI publication without cancelling or holding CI/deploy"
    }
    require(
        steps.single()["uses"] == "./ai-review" ||
            Regex("[\\w./-]+/ai-review@[a-f0-9]{40}").matches(steps.single()["uses"].toString())
    ) {
        "Use the pinned shared hosted review entry point"
    }
    val inputs = yamlMapping(steps.single()["with"])
    val provider = inputs["provider"] ?: "codex"
    require(
        inputs.keys.all {
            it in setOf("branch", "provider", "model", "provider-api-key", "github-token")
        } &&
            provider in setOf("codex", "claude") &&
            inputs["model"] is String &&
            inputs["model"].toString().isNotBlank()
    ) {
        "Configure one hosted provider and explicit model"
    }
    val secret = if (provider == "codex") "OPENAI_API_KEY" else "ANTHROPIC_API_KEY"
    require(
        inputs["provider-api-key"] == "\${{ secrets.$secret }}" &&
            (inputs["github-token"] == null || inputs["github-token"] == "\${{ github.token }}")
    ) {
        "Pass only the selected provider's named API secret and scoped job token"
    }
    val triggers = yamlMapping(document["on"] ?: document[true])
    require(
        triggers.keys == setOf("push") &&
            yamlMapping(triggers["push"])["branches"] == listOf(inputs["branch"]) &&
            yamlMapping(triggers["push"])["tags"] == null
    ) {
        "Review exact integration pushes once; exclude receipt tags and PR/CI triggers"
    }
}
