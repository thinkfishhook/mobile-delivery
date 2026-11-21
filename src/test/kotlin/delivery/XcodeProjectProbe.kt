package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*

/** Opt-in native readback of the same neutral fixture used by the regression tests. */
object XcodeProjectProbe {
    @JvmStatic
    fun main(args: Array<String>) = temp { root ->
        val directory = root.resolve(config.ios.project).createDirectories()
        val project = directory.resolve("project.xcproj")
        val original = resource("signing/Example.xcodeproj/project.xcproj").toByteArray()
        project.writeBytes(original)
        val sources = directory.parent.resolve("Sources").createDirectories()
        sources.resolve("Fixture.swift").writeText("struct Fixture {}\n")
        val pkg = directory.parent.resolve("SharedKit").createDirectories()
        pkg.resolve("Package.swift")
            .writeText(
                """
                // swift-tools-version: 6.0
                import PackageDescription
                let package = Package(name: "SharedKit", products: [.library(name: "SharedKit", targets: ["SharedKit"])], targets: [.target(name: "SharedKit")])
                """
                    .trimIndent() + "\n"
            )
        pkg.resolve("Sources/SharedKit")
            .createDirectories()
            .resolve("SharedKit.swift")
            .writeText("public struct SharedKit {}\n")
        val commands = ProcessCommands()
        fun settings(target: String, configuration: String, sdk: String): JsonObject {
            val result =
                commands.execute(
                    listOf(
                        "xcodebuild",
                        "-project",
                        directory.toString(),
                        "-target",
                        target,
                        "-configuration",
                        configuration,
                        "-sdk",
                        sdk,
                        "-showBuildSettings",
                        "-json",
                    ),
                    cwd = root,
                    timeoutSeconds = 60,
                )
            assertEquals(0, result.exit, result.stderr)
            return json
                .parseToJsonElement(result.stdout)
                .jsonArray
                .single()
                .jsonObject
                .getValue("buildSettings")
                .jsonObject
        }
        val build =
            Build("source-sha", MarketingVersion("26.09.01"), BuildNumber("234.1"), "build-url")
        Signing(config, commands, System.getenv()).withProject(
            root,
            build,
            SigningIdentity("fixture-profile", "fixture-certificate"),
        ) {
            val release = settings("iosApp", "Release", "iphoneos")
            assertEquals("Manual", release.string("CODE_SIGN_STYLE"))
            assertEquals("fixture-profile", release.string("PROVISIONING_PROFILE_SPECIFIER"))
            assertEquals("fixture-certificate", release.string("CODE_SIGN_IDENTITY"))
            assertEquals(config.teamId, release.string("DEVELOPMENT_TEAM"))
            assertEquals("26.09.01", release.string("MARKETING_VERSION"))
            assertEquals("234.1", release.string("CURRENT_PROJECT_VERSION"))
            val debug = settings("iosApp", "Debug", "macosx")
            assertEquals("Automatic", debug.string("CODE_SIGN_STYLE"))
            assertEquals("Apple Development", debug.string("CODE_SIGN_IDENTITY"))
            assertEquals("1.0", debug.string("MARKETING_VERSION"))
            assertEquals("1", debug.string("CURRENT_PROJECT_VERSION"))
            assertEquals("org.example.iosApp.Debug", debug.string("PRODUCT_BUNDLE_IDENTIFIER"))
            val resources = settings("Resources", "Release", "iphoneos")
            assertEquals("Automatic", resources.string("CODE_SIGN_STYLE"))
            assertTrue(
                resources["PROVISIONING_PROFILE_SPECIFIER"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .isEmpty()
            )
        }
        assertContentEquals(original, project.readBytes())
        assertFalse(directory.resolve("project.pbxproj").exists())
        println(
            "PASS: Xcode readback of iOS Release, macOS Debug and unrelated target; exact source restored. No signing/archive/UI execution."
        )
    }
}
