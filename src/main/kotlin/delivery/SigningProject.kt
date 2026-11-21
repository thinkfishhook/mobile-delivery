package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
private val xcodeProjectJson = Json {
    allowTrailingComma = true
    allowComments = true
}

private fun signingSettings(config: DeliveryConfig, build: Build, identity: SigningIdentity) =
    mapOf(
        "CODE_SIGN_STYLE" to JsonPrimitive("Manual"),
        "CODE_SIGN_IDENTITY" to JsonPrimitive(identity.certificate),
        "DEVELOPMENT_TEAM" to JsonPrimitive(config.teamId),
        "PROVISIONING_PROFILE_SPECIFIER" to JsonPrimitive(identity.profile),
        "MARKETING_VERSION" to JsonPrimitive(build.version.value),
        "CURRENT_PROJECT_VERSION" to JsonPrimitive(build.number.value),
    )

fun appSigningProject(
    document: JsonObject,
    config: DeliveryConfig,
    build: Build,
    identity: SigningIdentity,
): JsonObject {
    val objects = document.getValue("objects").jsonObject
    val targets =
        objects.values
            .map { it.jsonObject }
            .filter {
                it["isa"]?.jsonPrimitive?.content == "PBXNativeTarget" &&
                    it["name"]?.jsonPrimitive?.content == config.ios.target &&
                    it["productType"]?.jsonPrimitive?.content ==
                        "com.apple.product-type.application"
            }
    if (targets.size != 1)
        throw ArtifactError("Expected one ${config.ios.target} application target")
    val configs =
        objects
            .getValue(targets.single().string("buildConfigurationList"))
            .jsonObject
            .getValue("buildConfigurations")
            .jsonArray
    val releases =
        configs
            .map { it.jsonPrimitive.content }
            .filter { objects.getValue(it).jsonObject.string("name") == "Release" }
    if (releases.size != 1) throw ArtifactError("Expected one app Release configuration")
    val key = releases.single()
    val release = objects.getValue(key).jsonObject
    val settings =
        release.getValue("buildSettings").jsonObject + signingSettings(config, build, identity)
    return JsonObject(
        document +
            ("objects" to
                JsonObject(
                    objects +
                        (key to JsonObject(release + ("buildSettings" to JsonObject(settings))))
                ))
    )
}

private fun hierarchicalSigningProject(
    text: String,
    config: DeliveryConfig,
    build: Build,
    identity: SigningIdentity,
): JsonObject {
    // Validate the structure we edit; preserve all unrelated project fields verbatim in the tree.
    val document =
        try {
            xcodeProjectJson.parseToJsonElement(text).jsonObject
        } catch (_: IllegalArgumentException) {
            throw ArtifactError("Unsupported project.xcproj: expected hierarchical Xcode JSON")
        }
    if (
        "objects" in document ||
            (document["required-capabilities"] ?: JsonArray(emptyList())) != JsonArray(emptyList())
    )
        throw ArtifactError("Unsupported project.xcproj structure or required capabilities")
    val configurations =
        document["configurations"] as? JsonArray
            ?: throw ArtifactError("Expected project.xcproj configurations")
    val names = configurations.map {
        val name = if (it is JsonObject) it["name"] else it
        (name as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
            ?: throw ArtifactError("Unsupported project.xcproj configuration")
    }
    if (
        names.count { it == "Release" } != 1 ||
            names.distinct().size != names.size ||
            names.any { it.isBlank() || it.any { char -> char in "[]*?=" } }
    )
        throw ArtifactError("Expected one app Release configuration without duplicate names")
    val targets =
        (document["targets"] as? JsonArray)?.map {
            it as? JsonObject ?: throw ArtifactError("Unsupported project.xcproj target")
        } ?: throw ArtifactError("Expected project.xcproj targets")
    val matches = targets.indices.filter { targets[it]["name"] == JsonPrimitive(config.ios.target) }
    if (
        matches.size != 1 ||
            targets[matches.single()]["product-type"] != JsonPrimitive("application")
    )
        throw ArtifactError("Expected one ${config.ios.target} application target")
    val index = matches.single()
    val target = targets[index]
    val settings =
        (target["build-settings"] ?: JsonObject(emptyMap())) as? JsonObject
            ?: throw ArtifactError("Expected project.xcproj target build-settings object")
    val overlay = signingSettings(config, build, identity)
    for (key in settings.keys) {
        val base = key.substringBefore('[')
        if (base !in overlay || key == base) continue
        // A more specific SDK/architecture/wildcard condition could defeat the overlay.
        if (names.none { key == "$base[config=$it]" })
            throw ArtifactError("Unsupported conditional signing/version setting: $key")
        if (base in settings && key != "$base[config=Release]")
            throw ArtifactError(
                "Ambiguous common and configuration-specific signing/version setting: $key"
            )
    }
    // Xcode 27.2 can let an unconditional value shadow its config-qualified counterpart.
    // Expand only the six managed keys across the declared configurations before replacing Release.
    val updatedSettings = settings.toMutableMap()
    for ((key, value) in overlay) {
        updatedSettings.remove(key)?.let { common ->
            names
                .filter { it != "Release" }
                .forEach { name ->
                    updatedSettings.putIfAbsent("$key[config=$name]", common)
                }
        }
        updatedSettings["$key[config=Release]"] = value
    }
    val updated = JsonObject(target + ("build-settings" to JsonObject(updatedSettings)))
    return JsonObject(
        document +
            ("targets" to JsonArray(targets.mapIndexed { i, t -> if (i == index) updated else t }))
    )
}

internal fun <T> withSigningProject(
    directory: Path,
    config: DeliveryConfig,
    build: Build,
    identity: SigningIdentity,
    run: (List<String>) -> String,
    action: () -> T,
): T {
    val files =
        listOf("project.pbxproj", "project.xcproj").map(directory::resolve).filter { it.exists() }
    if (files.size != 1 || !files.single().isRegularFile())
        throw ArtifactError("Expected exactly one project.pbxproj or project.xcproj in $directory")
    val project = files.single()
    val hierarchical = project.name == "project.xcproj"
    val original = project.readBytes()
    val overlay =
        if (hierarchical) {
            hierarchicalSigningProject(original.decodeToString(), config, build, identity)
        } else {
            val document =
                json
                    .parseToJsonElement(
                        run(listOf("plutil", "-convert", "json", "-o", "-", project.toString()))
                    )
                    .jsonObject
            appSigningProject(document, config, build, identity)
        }
    try {
        project.writeText(overlay.toString())
        if (!hierarchical) run(listOf("plutil", "-convert", "xml1", project.toString()))
        return action()
    } finally {
        project.writeBytes(original)
    }
}
