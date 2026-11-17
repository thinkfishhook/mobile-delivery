package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.json.*

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
        release.getValue("buildSettings").jsonObject +
            mapOf(
                "CODE_SIGN_STYLE" to JsonPrimitive("Manual"),
                "CODE_SIGN_IDENTITY" to JsonPrimitive(identity.certificate),
                "DEVELOPMENT_TEAM" to JsonPrimitive(config.teamId),
                "PROVISIONING_PROFILE_SPECIFIER" to JsonPrimitive(identity.profile),
                "MARKETING_VERSION" to JsonPrimitive(build.version.value),
                "CURRENT_PROJECT_VERSION" to JsonPrimitive(build.number.value),
            )
    return JsonObject(
        document +
            ("objects" to
                JsonObject(
                    objects +
                        (key to JsonObject(release + ("buildSettings" to JsonObject(settings))))
                ))
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
    val project = directory.resolve("project.pbxproj")
    val original = project.readBytes()
    val document =
        json
            .parseToJsonElement(
                run(listOf("plutil", "-convert", "json", "-o", "-", project.toString()))
            )
            .jsonObject
    val overlay = appSigningProject(document, config, build, identity)
    try {
        project.writeText(overlay.toString())
        run(listOf("plutil", "-convert", "xml1", project.toString()))
        return action()
    } finally {
        project.writeBytes(original)
    }
}
