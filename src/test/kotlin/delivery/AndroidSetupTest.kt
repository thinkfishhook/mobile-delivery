package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class AndroidSetupTest {
    @Test
    fun `SDK 37 uses the published package identifier including minor version`() {
        assertContains(config.androidPackages, "platforms;android-37.0")
        assertFalse("platforms;android-37" in config.androidPackages)
    }

    @Test
    fun `SDK setup retains fresh diagnostics and propagates failures without credentials`() =
        temp { root ->
            val log =
                root.resolve("build/delivery").createDirectories().resolve("setup-android.log")
            listOf(0, 1).forEach { exit ->
                log.writeText("stale output")
                val commands = RecordingCommands()
                commands.respond = { call ->
                    assertEquals(listOf("sdkmanager") + config.androidPackages, call.args)
                    assertEquals(mapOf("ANDROID_HOME" to "/sdk"), call.env)
                    assertEquals(log, call.log)
                    assertEquals("", log.readText())
                    log.appendText("SDK diagnostic for exit $exit\n")
                    CommandResult(exit, "", "")
                }
                val env =
                    mapOf(
                        "ANDROID_HOME" to "/sdk",
                        "GH_TOKEN" to "secret",
                        "ASC_PRIVATE_KEY" to "secret",
                        "ANALYTICS_KEY" to "public",
                    )
                if (exit == 0) setupAndroid(root, config, commands, env)
                else {
                    val error =
                        assertFailsWith<CommandError> {
                            setupAndroid(root, config, commands, env)
                        }
                    assertContains(error.message.orEmpty(), "setup-android.log")
                }
                assertEquals("SDK diagnostic for exit $exit\n", log.readText())
                assertEquals(1, commands.calls.size)
            }
        }

    @Test
    fun `failed subprocess retains both output streams in the evidence log`() = temp { root ->
        val log = root.resolve("setup-android.log")
        assertFailsWith<CommandError> {
            ProcessCommands()
                .run(
                    listOf("/bin/sh", "-c", "echo progress; echo 'package not found' >&2; exit 1"),
                    env = emptyMap(),
                    log = log,
                )
        }
        assertContains(log.readText(), "progress")
        assertContains(log.readText(), "package not found")
    }
}
