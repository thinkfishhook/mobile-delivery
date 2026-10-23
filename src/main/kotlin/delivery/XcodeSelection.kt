package delivery

import java.nio.file.Path
import kotlin.io.path.*

fun selectXcode(
    config: DeliveryConfig,
    commands: Commands,
    env: Map<String, String>,
    applications: Path = Path.of("/Applications"),
) {
    val environment = buildEnvironment(env)
    val platform = commands.run(listOf("uname", "-s"), env = environment).trim()
    require(platform == "Darwin") { "selectXcode requires macOS; found $platform" }
    val preferred = listOf("Xcode_${config.xcode}.app", "Xcode-${config.xcode}.app", "Xcode.app")
    val apps =
        if (applications.isDirectory())
            applications.listDirectoryEntries("Xcode*.app").filter { it.isDirectory() }
        else emptyList()
    val candidates =
        apps
            .sortedWith(
                compareBy<Path> {
                        preferred.indexOf(it.name).takeIf { index -> index >= 0 } ?: preferred.size
                    }
                    .thenBy { it.name }
            )
            .map { it.resolve("Contents/Developer").toString() } +
            listOfNotNull(env["DEVELOPER_DIR"])
    val installed = mutableListOf<String>()
    for (directory in candidates.distinct()) {
        val result =
            commands.execute(
                listOf("xcodebuild", "-version"),
                env = environment + ("DEVELOPER_DIR" to directory),
            )
        val version = result.stdout.lineSequence().firstOrNull().orEmpty().trim()
        if (result.exit == 0 && version == "Xcode ${config.xcode}") {
            require('\n' !in directory && '\r' !in directory) {
                "Xcode path cannot contain line breaks: select an installation with a single-line path"
            }
            val output =
                requireNotNull(env["GITHUB_ENV"]) {
                    "selectXcode requires GITHUB_ENV to export DEVELOPER_DIR for subsequent steps"
                }
            Path.of(output).appendText("DEVELOPER_DIR=$directory\n")
            println("Selected $version: $directory")
            return
        }
        installed +=
            "${if (result.exit == 0 && version.isNotEmpty()) version else "unreadable (exit ${result.exit})"}: $directory"
    }
    error(
        "Expected Xcode ${config.xcode}; no installed Xcode matched. Installed versions and paths:\n${installed.joinToString("\n").ifEmpty { "None found under $applications" }}\nInstall the configured Xcode on this runner or correct delivery.json's xcode value."
    )
}
