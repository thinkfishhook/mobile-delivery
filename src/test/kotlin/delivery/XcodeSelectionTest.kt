package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class XcodeSelectionTest {
    @Test
    fun `versioned underscore hyphen and default Xcode names are selected by actual version`() {
        for (name in
            listOf("Xcode_${config.xcode}.app", "Xcode-${config.xcode}.app", "Xcode.app")) temp {
            root ->
            val app = root.resolve(name).createDirectories().resolve("Contents/Developer")
            val envFile = root.resolve("environment").createFile()
            val commands = RecordingCommands()
            commands.respond = { call ->
                assertNull(call.env["ASC_PRIVATE_KEY"])
                assertNull(call.env["POSTHOG_TOKEN"])
                if (call.args.first() == "uname") CommandResult(0, "Darwin", "")
                else {
                    assertEquals(listOf("xcodebuild", "-version"), call.args)
                    assertEquals(app.toString(), call.env["DEVELOPER_DIR"])
                    CommandResult(0, "Xcode ${config.xcode}\nBuild version fixture", "")
                }
            }
            selectXcode(
                config,
                commands,
                mapOf(
                    "GITHUB_ENV" to envFile.toString(),
                    "ASC_PRIVATE_KEY" to "private",
                    "POSTHOG_TOKEN" to "private",
                ),
                root,
            )
            assertEquals("DEVELOPER_DIR=$app\n", envFile.readText())
            assertFalse(commands.calls.any { "--switch" in it.args })
        }
    }

    @Test
    fun `selection ignores misleading app names and lists all installed versions on mismatch`() =
        temp { root ->
            val named = root.resolve("Xcode_${config.xcode}.app").createDirectories()
            val fallback = root.resolve("Xcode.app").createDirectories()
            val envFile = root.resolve("environment")
            val commands = RecordingCommands()
            commands.respond = { call ->
                if (call.args.first() == "uname") CommandResult(0, "Darwin", "")
                else
                    CommandResult(
                        0,
                        if (call.env["DEVELOPER_DIR"] == "$named/Contents/Developer") "Xcode 1.0"
                        else "Xcode 2.0",
                        "",
                    )
            }
            val failure =
                assertFailsWith<IllegalStateException> {
                    selectXcode(config, commands, mapOf("GITHUB_ENV" to envFile.toString()), root)
                }
            assertContains(failure.message.orEmpty(), "Expected Xcode ${config.xcode}")
            assertContains(failure.message.orEmpty(), "Xcode 1.0: $named/Contents/Developer")
            assertContains(failure.message.orEmpty(), "Xcode 2.0: $fallback/Contents/Developer")
            assertFalse(envFile.exists())
        }

    @Test
    fun `unreadable Xcode is skipped and matching default is exported`() = temp { root ->
        root.resolve("Xcode_${config.xcode}.app").createDirectories()
        val fallback = root.resolve("Xcode.app").createDirectories()
        val envFile = root.resolve("environment").createFile()
        val commands = RecordingCommands()
        commands.respond = { call ->
            when {
                call.args.first() == "uname" -> CommandResult(0, "Darwin", "")
                call.env["DEVELOPER_DIR"] == "$fallback/Contents/Developer" ->
                    CommandResult(0, "Xcode ${config.xcode}", "")
                else -> CommandResult(1, "", "unusable installation")
            }
        }
        selectXcode(config, commands, mapOf("GITHUB_ENV" to envFile.toString()), root)
        assertEquals("DEVELOPER_DIR=$fallback/Contents/Developer\n", envFile.readText())
    }
}
