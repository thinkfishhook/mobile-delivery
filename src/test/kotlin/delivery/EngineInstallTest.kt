package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class EngineInstallTest {
    private val script =
        Path.of(System.getProperty("delivery.root")).resolve("internal/engine/install.sh")

    @Test
    fun `installer publishes a runnable distribution from paths containing spaces`() =
        temp { root ->
            val source = root.resolve("action contents").createDirectories()
            source
                .resolve("gradlew")
                .writeText(
                    """
                    #!/bin/bash
                    set -euo pipefail
                    [[ "${'$'}1" == -p && "${'$'}2" == "${'$'}(dirname "${'$'}0")" && "${'$'}3" == --no-daemon && "${'$'}4" == --quiet && "${'$'}5" == installDist ]]
                    printf 'build\n' >> "${'$'}2/build-count"
                    mkdir -p "${'$'}2/build/install/mobile-delivery/bin"
                    printf '#!/bin/sh\nprintf "engine ready\\n"\n' > "${'$'}2/build/install/mobile-delivery/bin/mobile-delivery"
                    chmod +x "${'$'}2/build/install/mobile-delivery/bin/mobile-delivery"
                    """
                        .trimIndent()
                )
            source.resolve("gradlew").toFile().setExecutable(true)
            val pathFile = root.resolve("github path")
            val result = install(source, root, pathFile)
            assertEquals(0, result.exit, result.stderr)
            val bin = Path.of(pathFile.readText().trim())
            assertTrue(bin.startsWith(root.resolve("runner temp")))
            val run =
                ProcessCommands()
                    .execute(
                        listOf(bin.resolve("mobile-delivery").toString()),
                        env = buildEnvironment() + ("JAVA_HOME" to "/callers/jdk"),
                    )
            assertEquals(0, run.exit, run.stderr)
            assertEquals("engine ready\n", run.stdout)
            assertEquals("/callers/jdk", root.resolve("java-home-seen").readText().trim())
            val second = install(source, root, root.resolve("second-path"))
            assertEquals(0, second.exit, second.stderr)
            assertEquals(listOf("build"), source.resolve("build-count").readLines())
            source.resolve("src").createDirectories().resolve("main.kt").writeText("changed source")
            assertEquals(0, install(source, root, root.resolve("changed-path")).exit)
            assertEquals(listOf("build", "build"), source.resolve("build-count").readLines())
        }

    @Test
    fun `cache key covers engine sources build inputs and installer independent of checkout path`() =
        temp { root ->
            val source = root.resolve("source one").createDirectories()
            val copy = root.resolve("source two").createDirectories()
            val files =
                listOf(
                    "src/main/kotlin/App.kt",
                    "src/main/resources/tool.json",
                    "src/test/kotlin/AppTest.kt",
                    "gradle/wrapper/gradle-wrapper.properties",
                    "gradle/libs.versions.toml",
                    "build.gradle.kts",
                    "settings.gradle.kts",
                    "gradlew",
                    "internal/engine/install.sh",
                )
            for (folder in listOf(source, copy)) for (file in files) folder.resolve(file).apply {
                parent.createDirectories()
                writeText("original")
            }
            val keyScript = script.resolveSibling("cache-key.sh")
            fun key(folder: Path, architecture: String = "ARM64"): String {
                val result =
                    ProcessCommands()
                        .execute(
                            listOf("bash", keyScript.toString(), folder.toString()),
                            env =
                                buildEnvironment() +
                                    mapOf("RUNNER_OS" to "macOS", "RUNNER_ARCH" to architecture),
                        )
                assertEquals(0, result.exit, result.stderr)
                return result.stdout.trim()
            }
            val original = key(source)
            assertEquals(original, key(copy))
            assertNotEquals(original, key(source, "X64"))
            for (file in files) {
                source.resolve(file).writeText("modified")
                assertNotEquals(original, key(source), file)
                source.resolve(file).writeText("original")
            }
        }

    @Test
    fun `failed engine build does not publish a stale distribution`() = temp { root ->
        val source = root.resolve("action").createDirectories()
        source.resolve("gradlew").writeText("#!/bin/sh\nexit 17\n")
        source.resolve("gradlew").toFile().setExecutable(true)
        source
            .resolve("build/install/mobile-delivery/bin")
            .createDirectories()
            .resolve("mobile-delivery")
            .writeText("stale")
        val pathFile = root.resolve("github-path")
        assertEquals(17, install(source, root, pathFile).exit)
        assertFalse(pathFile.exists())
    }

    private fun install(source: Path, root: Path, pathFile: Path): CommandResult {
        val javaHome = root.resolve("jdk 21")
        val java = javaHome.resolve("bin/java")
        java.parent.createDirectories()
        java.writeText(
            """
            #!/bin/bash
            if [[ "${'$'}1" == -version ]]; then
              echo 'openjdk version "21.0.11"' >&2
            else
              printf '%s' "${'$'}{JAVA_HOME-}" > "${'$'}(dirname "${'$'}0")/../../java-home-seen"
              printf 'engine ready\n'
            fi
            """
                .trimIndent()
        )
        java.toFile().setExecutable(true)
        source.resolve("src/main/resources/tool.json").apply {
            parent.createDirectories()
            if (!exists()) writeText("{}")
        }
        return ProcessCommands()
            .execute(
                listOf("bash", script.toString(), source.toString()),
                root,
                buildEnvironment() +
                    mapOf(
                        "RUNNER_TEMP" to root.resolve("runner temp").toString(),
                        "GITHUB_PATH" to pathFile.toString(),
                        "GITHUB_RUN_ID" to "123",
                        "GITHUB_RUN_ATTEMPT" to "2",
                        "GITHUB_JOB" to "test",
                        "JAVA_HOME_21_X64" to javaHome.toString(),
                        "JAVA_HOME_21_ARM64" to javaHome.toString(),
                    ),
            )
    }
}
