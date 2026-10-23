package delivery

import java.nio.file.Path
import kotlin.io.path.*

interface Source {
    fun commits(baseline: String, tip: String): List<String>

    fun count(sha: String): Int

    // The first-parent delivery-branch commit with this commit count, if any.
    fun commitWithCount(count: Int): String?

    fun isAncestor(ancestor: String, descendant: String): Boolean

    fun notes(base: String, tip: String): String

    fun <T> snapshot(sha: String, action: (Path) -> T): T
}

class GitSource(
    private val root: Path,
    private val config: DeliveryConfig,
    private val commands: Commands,
    private val branch: String,
) : Source {
    fun git(vararg args: String): String =
        commands.run(listOf("git", *args), cwd = root, env = buildEnvironment()).trim()

    override fun commits(baseline: String, tip: String): List<String> {
        git("merge-base", "--is-ancestor", baseline, tip)
        return git("rev-list", "--reverse", "--topo-order", "$baseline..$tip")
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
    }

    override fun count(sha: String) = git("rev-list", "--count", sha).toInt()

    override fun isAncestor(ancestor: String, descendant: String): Boolean {
        val result =
            commands.execute(
                listOf("git", "merge-base", "--is-ancestor", ancestor, descendant),
                cwd = root,
                env = buildEnvironment(),
            )
        require(result.exit in 0..1) { "git cannot compare $ancestor and $descendant" }
        return result.exit == 0
    }

    override fun commitWithCount(count: Int): String? {
        val chain =
            git("rev-list", "--first-parent", "--reverse", "origin/$branch").lines().filter {
                it.isNotBlank()
            }
        // Counts grow along the first-parent chain; with linear history the index is exact.
        var low = 0
        var high = chain.lastIndex
        while (low <= high) {
            val middle = (low + high) / 2
            val found = count(chain[middle])
            when {
                found == count -> return chain[middle]
                found < count -> low = middle + 1
                else -> high = middle - 1
            }
        }
        return null
    }

    override fun notes(base: String, tip: String): String =
        git(
                "diff",
                "--name-only",
                "--diff-filter=AM",
                base,
                tip,
                "--",
                "${config.releaseNotes.directory.trimEnd('/')}/",
            )
            .lines()
            .filter { it.endsWith(".md") && !it.endsWith("README.md") }
            .joinToString("\n\n") { git("show", "$tip:$it") }
            .take(4000)
            .ifEmpty { config.releaseNotes.fallback }

    override fun <T> snapshot(sha: String, action: (Path) -> T): T {
        val temp = createTempDirectory("delivery-source-")
        val source = temp.resolve("source")
        try {
            git("worktree", "add", "--detach", source.toString(), sha)
            try {
                return action(source)
            } finally {
                git("worktree", "remove", "--force", source.toString())
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
