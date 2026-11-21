package delivery

import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class XcodeProjectTest {
    private val projectJson = Json {
        allowTrailingComma = true
        allowComments = true
    }
    private val fixture = resource("signing/Example.xcodeproj/project.xcproj")
    private val build =
        Build("source-sha", MarketingVersion("26.09.01"), BuildNumber("234.1"), "build-url")
    private val identity = SigningIdentity("profile", "certificate")

    @Test
    fun `full application product type overlays correctly while full bundle type remains rejected`() =
        temp { root ->
            val project = root.resolve(config.ios.project).resolve("project.xcproj")
            project.parent.createDirectories()
            val full =
                fixture.replace(
                    "\"product-type\": \"application\"",
                    "\"full-product-type\": \"com.apple.product-type.application\"",
                )
            project.writeText(full)
            var archived = false
            Signing(config, RecordingCommands(), emptyMap()).withProject(root, build, identity) {
                archived = true
                assertContains(project.readText(), "MARKETING_VERSION[config=Release]")
                assertContains(project.readText(), "com.apple.product-type.application")
            }
            assertTrue(archived)
            assertEquals(full, project.readText())
            val invalid =
                full.replace("com.apple.product-type.application", "com.apple.product-type.bundle")
            project.writeText(invalid)
            assertFailsWith<ArtifactError> {
                Signing(config, RecordingCommands(), emptyMap()).withProject(
                    root,
                    build,
                    identity,
                ) {
                    fail("Bundle must not archive as an application")
                }
            }
            assertEquals(invalid, project.readText())
        }

    @Test
    fun `hierarchical project restores exact bytes when archive fails`() = temp { root ->
        val project = root.resolve(config.ios.project).resolve("project.xcproj")
        project.parent.createDirectories()
        val original = fixture.replace("\n", "\r\n").toByteArray()
        project.writeBytes(original)
        val commands = RecordingCommands()
        val failure = CommandError("archive failed", exit = 65)
        val observed =
            assertFailsWith<CommandError> {
                Signing(config, commands, emptyMap()).withProject(root, build, identity) {
                    assertContains(
                        project.readText(),
                        "PROVISIONING_PROFILE_SPECIFIER[config=Release]",
                    )
                    throw failure
                }
            }
        assertSame(failure, observed)
        assertContentEquals(original, project.readBytes())
        assertTrue(commands.calls.isEmpty())
        assertFalse(project.parent.resolve("project.pbxproj").exists())
    }

    @Test
    fun `ambiguous or unsupported project formats never reach archive or mutate source`() =
        temp { root ->
            val directory = root.resolve(config.ios.project).createDirectories()
            val project = directory.resolve("project.xcproj")
            val legacy = directory.resolve("project.pbxproj")
            val commands = RecordingCommands()
            val invoke = {
                Signing(config, commands, emptyMap()).withProject(root, build, identity) {
                    fail("invalid project reached archive")
                }
            }
            assertFailsWith<ArtifactError> { invoke() }
            project.writeText(fixture)
            legacy.writeText("original legacy bytes")
            assertFailsWith<ArtifactError> { invoke() }
            assertEquals("original legacy bytes", legacy.readText())
            assertEquals(fixture, project.readText())
            legacy.deleteExisting()
            val original = projectJson.parseToJsonElement(fixture).jsonObject
            val targets = original.getValue("targets").jsonArray
            val invalid =
                listOf(
                    "not JSON",
                    "[]",
                    "{\"objects\":{}}",
                    JsonObject(
                            original +
                                ("configurations" to JsonArray(listOf(JsonPrimitive("Debug"))))
                        )
                        .toString(),
                    JsonObject(
                            original +
                                ("configurations" to
                                    JsonArray(
                                        listOf(JsonPrimitive("Release"), JsonPrimitive("Release"))
                                    ))
                        )
                        .toString(),
                    JsonObject(original + ("targets" to JsonArray(targets + targets[0])))
                        .toString(),
                    fixture.replace(
                        "\"product-type\": \"application\"",
                        "\"product-type\": \"bundle\"",
                    ),
                    fixture.replace(
                        "\"default-configuration\"",
                        "\"required-capabilities\": [\"future-feature\"], \"default-configuration\"",
                    ),
                    fixture.replace(
                        "CODE_SIGN_STYLE[config=Release]",
                        "CODE_SIGN_STYLE[sdk=iphoneos*]",
                    ),
                    fixture.replace(
                        "CODE_SIGN_STYLE[config=Release]",
                        "CODE_SIGN_STYLE[config=Release][sdk=iphoneos*]",
                    ),
                    fixture.replace(
                        "CODE_SIGN_STYLE[config=Release]",
                        "CODE_SIGN_STYLE[config=Rel*]",
                    ),
                    fixture.replace(
                        "CODE_SIGN_STYLE[config=Release]",
                        "CODE_SIGN_STYLE[config=Debug]",
                    ),
                )
            invalid.forEach { contents ->
                project.writeText(contents)
                assertFailsWith<ArtifactError> { invoke() }
                assertEquals(contents, project.readText())
            }
            assertTrue(commands.calls.isEmpty())
        }

    @Test
    fun `configuration objects and custom configurations retain unrelated settings`() =
        temp { root ->
            val project = root.resolve(config.ios.project).resolve("project.xcproj")
            project.parent.createDirectories()
            val contents =
                fixture.replace(
                    "\"Debug\", \"Release\",",
                    "\"Debug\", {\"name\":\"Release\",\"file\":\"Release.xcconfig\"}, \"Profile\",",
                )
            project.writeText(contents)
            Signing(config, RecordingCommands(), emptyMap()).withProject(root, build, identity) {
                val changed = projectJson.parseToJsonElement(project.readText()).jsonObject
                assertEquals(
                    projectJson.parseToJsonElement(contents).jsonObject["configurations"],
                    changed["configurations"],
                )
                val settings =
                    changed
                        .getValue("targets")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("build-settings")
                        .jsonObject
                assertEquals("1.0", settings.string("MARKETING_VERSION[config=Profile]"))
                assertEquals("Automatic", settings.string("CODE_SIGN_STYLE[config=Profile]"))
            }
            assertEquals(contents, project.readText())
        }

    @Test
    fun `hierarchical project overlays only the selected application Release settings`() =
        temp { root ->
            val project = root.resolve(config.ios.project).resolve("project.xcproj")
            project.parent.createDirectories()
            project.writeText(fixture)
            val original = projectJson.parseToJsonElement(fixture).jsonObject
            val commands = RecordingCommands()
            Signing(config, commands, emptyMap()).withProject(root, build, identity) {
                val changed = projectJson.parseToJsonElement(project.readText()).jsonObject
                assertEquals(original - "targets", changed - "targets")
                val oldTargets = original.getValue("targets").jsonArray
                val newTargets = changed.getValue("targets").jsonArray
                assertEquals(oldTargets[1], newTargets[1])
                val before = oldTargets[0].jsonObject
                val after = newTargets[0].jsonObject
                assertEquals(before - "build-settings", after - "build-settings")
                val settings = after.getValue("build-settings").jsonObject
                val expected =
                    mapOf(
                            "CODE_SIGN_STYLE" to "Manual",
                            "CODE_SIGN_IDENTITY" to "certificate",
                            "DEVELOPMENT_TEAM" to config.teamId,
                            "PROVISIONING_PROFILE_SPECIFIER" to "profile",
                            "MARKETING_VERSION" to "26.09.01",
                            "CURRENT_PROJECT_VERSION" to "234.1",
                        )
                        .mapKeys { "${it.key}[config=Release]" }
                        .mapValues { JsonPrimitive(it.value) }
                val oldSettings = before.getValue("build-settings").jsonObject
                val commonKeys =
                    setOf("CODE_SIGN_STYLE", "MARKETING_VERSION", "CURRENT_PROJECT_VERSION")
                val preservedDebug = commonKeys.associate {
                    "$it[config=Debug]" to oldSettings.getValue(it)
                }
                assertEquals(
                    JsonObject((oldSettings - commonKeys) + preservedDebug + expected),
                    settings,
                )
                assertFalse(project.parent.resolve("project.pbxproj").exists())
            }
            assertTrue(
                commands.calls.isEmpty(),
                "JSON projects must not go through plist conversion",
            )
            assertContentEquals(fixture.toByteArray(), project.readBytes())
        }
}
