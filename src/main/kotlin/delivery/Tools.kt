package delivery

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.io.path.*
import kotlinx.serialization.json.*

fun setupAndroid(
    root: Path,
    config: DeliveryConfig,
    commands: Commands,
    env: Map<String, String>,
) {
    if (config.androidPackages.isEmpty()) {
        println("No Android SDK packages configured.")
        return
    }
    val log = root.resolve("build/delivery").createDirectories().resolve("setup-android.log")
    log.writeText("")
    println("Installing Android SDK packages: ${config.androidPackages.joinToString()}")
    try {
        commands.run(
            listOf("sdkmanager") + config.androidPackages,
            env = toolEnvironment(env),
            log = log,
        )
    } finally {
        // Setup runs before verification; retain its output even if provisioning fails.
        print(log.readText())
    }
}

fun installAsc(toolchain: Toolchain, commands: Commands, env: Map<String, String>) {
    val system = commands.run(listOf("uname", "-s")).trim()
    val architecture = commands.run(listOf("uname", "-m")).trim()
    val asset =
        requireNotNull(toolchain.ascAssets["$system/$architecture"]) {
            val supported =
                toolchain.ascAssets.keys.sorted().joinToString { it.replace("Darwin/", "macOS/") }
            "Unsupported ASC platform: $system/$architecture. Supported platforms: $supported. " +
                "For iOS delivery, use runs-on: macos-26 or an arm64 self-hosted Mac."
        }
    val uri =
        URI(
            "https://github.com/rorkai/App-Store-Connect-CLI/releases/download/${toolchain.asc}/asc_${toolchain.asc}_${asset.name}"
        )
    val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120)).build()
    val response =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofByteArray())
    require(response.statusCode() == 200) { "ASC download failed: HTTP ${response.statusCode()}" }
    require(digest(response.body()) == asset.sha256) { "ASC checksum mismatch" }
    val destination = Path.of(env.getValue("RUNNER_TEMP"), "asc-bin").createDirectories()
    destination.resolve("asc").apply {
        writeBytes(response.body())
        toFile().setExecutable(true, false)
    }
    Path.of(env.getValue("GITHUB_PATH")).appendText("$destination\n")
}

// Explicit provisioning command retained for operators; never called by tests or CI.
fun bootstrap(
    args: List<String>,
    config: DeliveryConfig,
    commands: Commands,
    env: Map<String, String>,
) {
    val required = setOf("--asc-config", "--p12", "--p12-password-file", "--profile")
    val flags = args.chunked(2).map { it[0] }
    require(args.size % 2 == 0 && flags.size == flags.toSet().size && flags.toSet() == required) {
        "Supply --asc-config, --p12, --p12-password-file and --profile (one argument per line)"
    }
    val paths = args.chunked(2).associate { it[0] to Path.of(it[1]) }
    val document = json.parseToJsonElement(paths.getValue("--asc-config").readText()).jsonObject
    val selected =
        document["keys"]
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.find { it["name"] == document["default_key_name"] } ?: document
    val key = Path.of(selected.string("private_key_path")).readText()
    require("PRIVATE KEY" in key) { "ASC private key file is not PEM" }
    validateProfile(
        parsePlist(
            commands.run(
                listOf("security", "cms", "-D", "-i", paths.getValue("--profile").toString()),
                env = buildEnvironment(env),
            )
        ),
        config,
    )
    val values =
        mapOf(
            "ASC_KEY_ID" to selected.string("key_id"),
            "ASC_ISSUER_ID" to selected.string("issuer_id"),
            "ASC_PRIVATE_KEY" to key,
            "IOS_DISTRIBUTION_P12_BASE64" to
                Base64.getEncoder().encodeToString(paths.getValue("--p12").readBytes()),
            "IOS_DISTRIBUTION_P12_PASSWORD" to
                paths.getValue("--p12-password-file").readText().trim(),
            "IOS_PROFILE_BASE64" to
                Base64.getEncoder().encodeToString(paths.getValue("--profile").readBytes()),
        )
    require(values.values.all { it.isNotEmpty() }) { "Missing secret value" }
    val repo =
        env["GITHUB_REPOSITORY"]
            ?: commands
                .run(
                    listOf(
                        "gh",
                        "repo",
                        "view",
                        "--json",
                        "nameWithOwner",
                        "--jq",
                        ".nameWithOwner",
                    ),
                    env = toolEnvironment(env, "GH_TOKEN", "GITHUB_TOKEN"),
                )
                .trim()
    values.forEach { (name, value) ->
        commands.run(
            listOf("gh", "secret", "set", name, "--repo", repo, "--env", DELIVERY_ENVIRONMENT),
            env = toolEnvironment(env, "GH_TOKEN", "GITHUB_TOKEN"),
            input = value,
        )
        println("Configured $name; value was not printed.")
    }
}
