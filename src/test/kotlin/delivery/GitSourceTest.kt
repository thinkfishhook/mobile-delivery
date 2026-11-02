package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class GitSourceTest {
    @Test
    fun `refresh includes newer history without changing the event checkout`() = temp { root ->
        val commands = ProcessCommands()
        val origin = root.resolve("origin").createDirectories()
        val checkout = root.resolve("checkout")
        fun git(directory: Path, vararg args: String) =
            commands.run(listOf("git", *args), cwd = directory, env = buildEnvironment()).trim()
        git(origin, "init", "-b", "trunk")
        git(origin, "config", "user.email", "test@example.invalid")
        git(origin, "config", "user.name", "Test")
        git(origin, "commit", "--allow-empty", "-m", "base")
        val original = git(origin, "rev-parse", "HEAD")
        git(root, "clone", "--no-local", origin.toString(), checkout.toString())
        git(origin, "commit", "--allow-empty", "-m", "next")
        val current = git(origin, "rev-parse", "HEAD")
        val source = GitSource(checkout, config, commands, "trunk")
        assertNull(source.commitWithCount(2))

        assertEquals(current, source.refreshBranchTip(buildEnvironment()))

        assertEquals(current, source.commitWithCount(2))
        assertEquals(2, source.count(current))
        assertEquals(original, git(checkout, "rev-parse", "HEAD"))
        val credentials =
            commands.execute(
                listOf("git", "config", "--local", "--get-all", "credential.helper"),
                cwd = checkout,
            )
        assertEquals(1, credentials.exit)
    }

    @Test
    fun `refresh routes only the GitHub credential to its transient helper`() = temp { root ->
        val commands = RecordingCommands()
        commands.response("")
        commands.response(DeliveryToolHarness.sha('b'))
        val env =
            mapOf(
                "PATH" to "/usr/bin",
                "GH_TOKEN" to "repository-token",
                "IOS_RECEIPT_TOKEN" to "receipt-token",
                "ASC_PRIVATE_KEY" to "apple-key",
                "APP_BUILD_ENV" to "app-values",
                "IOS_DISTRIBUTION_P12_PASSWORD" to "signing-password",
            )

        GitSource(root, config, commands, "trunk").refreshBranchTip(env)

        val fetch = commands.calls.first()
        assertEquals(
            mapOf(
                "PATH" to "/usr/bin",
                "GH_TOKEN" to "repository-token",
                "GIT_TERMINAL_PROMPT" to "0",
            ),
            fetch.env,
        )
        assertFalse(fetch.args.any { it.contains("repository-token") })
        assertEquals(
            listOf(
                "git",
                "-c",
                "credential.helper=",
                "-c",
                "credential.helper=!gh auth git-credential",
            ),
            fetch.args.take(5),
        )
        assertNull(commands.calls.last().env["GH_TOKEN"])
    }
}
