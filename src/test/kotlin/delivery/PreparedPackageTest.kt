package delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import org.junit.Test

internal fun preparedIpa(preparation: Preparation, bundleId: String = config.bundleId): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        val info =
            """<plist><dict><key>CFBundleIdentifier</key><string>$bundleId</string><key>CFBundleShortVersionString</key><string>${preparation.version}</string><key>CFBundleVersion</key><string>${preparation.number}</string><key>CFBundleExecutable</key><string>App</string></dict></plist>"""
        mapOf("Info.plist" to info, "App" to "neutral executable").forEach { (name, value) ->
            zip.putNextEntry(ZipEntry("Payload/App.app/$name"))
            zip.write(value.toByteArray())
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

internal fun preparedManifest(
    preparation: Preparation,
    ipa: ByteArray = preparedIpa(preparation),
    symbols: ByteArray? = null,
) =
    ArtifactManifest(
        schema = 2,
        sha = preparation.request.sha,
        version = preparation.version,
        number = preparation.number,
        workflow = preparation.workflow,
        artifactName = artifactName(preparation.number),
        ipa = ArtifactFile("App.ipa", digest(ipa), ipa.size.toLong()),
        symbols = symbols?.let { ArtifactFile("symbols.zip", digest(it), it.size.toLong()) },
        symbolsAbsentReason = if (symbols == null) "Archive contained no dSYMs" else null,
        preparation = preparation,
    )

class PreparedPackageTest {
    private fun reserve(origin: PackageOrigin = PackageOrigin.CI_VERIFIED) =
        PreparationReservations(MemoryReservations()) { setOf("234.1") }
            .reserve(preparationRequest(origin = origin), MarketingVersion("26.11.01"))

    private fun commands() =
        RecordingCommands().apply {
            respond = { call ->
                var stdout = ""
                when (call.args.first()) {
                    "plutil" -> stdout = Path.of(call.args.last()).readText()
                    "ditto" ->
                        Path.of(call.args.last()).resolve("Payload/App.app").createDirectories()
                    "codesign" ->
                        if ("--entitlements" in call.args)
                            stdout =
                                """<plist><dict><key>application-identifier</key><string>${config.teamId}.${config.bundleId}</string><key>com.apple.developer.team-identifier</key><string>${config.teamId}</string><key>get-task-allow</key><false/></dict></plist>"""
                }
                CommandResult(0, stdout, "")
            }
        }

    private fun artifacts(
        manifest: ArtifactManifest,
        files: Map<String, ByteArray>,
        expired: Boolean = false,
    ) =
        object : BuildArtifacts {
            override fun find(name: String) =
                RetainedBuild(name, manifest.preparation!!.request.producer.runId, expired)

            override fun retainedByThisRun(): RetainedBuild? = null

            override fun download(build: RetainedBuild, output: Path): RetainedPackage {
                validatePackage(manifest, files)
                output.createDirectories()
                files.forEach { (name, bytes) -> output.resolve(name).writeBytes(bytes) }
                return RetainedPackage(output.resolve(manifest.ipa.name), manifest)
            }
        }

    @Test
    fun `sealed package round trips exact IPA symbols and manual origin without rebuilding`() =
        temp { output ->
            val p = reserve(PackageOrigin.MANUAL)
            val ipa = preparedIpa(p)
            val symbols = "matching symbols".toByteArray()
            val manifest = preparedManifest(p, ipa, symbols)
            val files =
                mapOf(
                    "App.ipa" to ipa,
                    "symbols.zip" to symbols,
                    "manifest.json" to json.encodeToString(manifest).toByteArray(),
                )
            val downloaded =
                PreparedPackages(artifacts(manifest, files), config, commands(), emptyMap())
                    .download(PreparationRecord(p, manifest), output)
            assertContentEquals(ipa, downloaded.ipa.readBytes())
            assertContentEquals(symbols, output.resolve("symbols.zip").readBytes())
            assertEquals(
                manifest,
                json.decodeFromString<ArtifactManifest>(output.resolve("manifest.json").readText()),
            )
            assertEquals(PackageOrigin.MANUAL, downloaded.manifest.preparation!!.request.origin)
        }

    @Test
    fun `actual IPA app and build identity must match the externally reserved source`() =
        temp { output ->
            val p = reserve()
            val wrongApp = preparedIpa(p, "example.wrong.app")
            val selfConsistent = preparedManifest(p, wrongApp)
            val files =
                mapOf(
                    "App.ipa" to wrongApp,
                    "manifest.json" to json.encodeToString(selfConsistent).toByteArray(),
                )
            assertFailsWith<IllegalArgumentException> {
                PreparedPackages(artifacts(selfConsistent, files), config, commands(), emptyMap())
                    .download(PreparationRecord(p, selfConsistent), output)
            }
            val wrongBuildIpa = preparedIpa(p.copy(number = BuildNumber("234.1.9")))
            val wrongBuild = preparedManifest(p, wrongBuildIpa)
            assertFails {
                PreparedPackages(
                        artifacts(
                            wrongBuild,
                            mapOf(
                                "App.ipa" to wrongBuildIpa,
                                "manifest.json" to json.encodeToString(wrongBuild).toByteArray(),
                            ),
                        ),
                        config,
                        commands(),
                        emptyMap(),
                    )
                    .download(PreparationRecord(p, wrongBuild), output)
            }
        }

    @Test
    fun `a self-consistent wrong source manifest does not satisfy a trusted reservation`() =
        temp { output ->
            val p = reserve()
            val expected = preparedManifest(p)
            val wrongSource = p.copy(request = p.request.copy(sha = "b".repeat(40)))
            val other = preparedManifest(wrongSource)
            val files =
                mapOf(
                    "App.ipa" to preparedIpa(wrongSource),
                    "manifest.json" to json.encodeToString(other).toByteArray(),
                )
            assertFails {
                PreparedPackages(artifacts(other, files), config, commands(), emptyMap())
                    .download(PreparationRecord(p, expected), output)
            }
        }

    @Test
    fun `sealing binds bytes once including across lost responses and rejects changed symbols`() {
        val store = MemoryReservations()
        val reservations = PreparationReservations(store) { emptySet() }
        val p = reservations.reserve(preparationRequest(), MarketingVersion("26.11.01"))
        val manifest = preparedManifest(p)
        store.loseResponse = true
        val record = reservations.seal(p, manifest)
        assertEquals(record, reservations.seal(p, manifest))
        store.loseResponse = false
        assertFails {
            reservations.seal(p, preparedManifest(p, symbols = "new symbols".toByteArray()))
        }
        assertEquals(p, reservations.reserve(p.request, MarketingVersion("26.12.01")))
    }

    @Test
    fun `expired missing or tampered prepared bytes are never silently rebuilt`() = temp { output ->
        val p = reserve()
        val manifest = preparedManifest(p)
        val files =
            mapOf(
                "App.ipa" to preparedIpa(p),
                "manifest.json" to json.encodeToString(manifest).toByteArray(),
            )
        assertFails {
            PreparedPackages(
                    artifacts(manifest, files, expired = true),
                    config,
                    commands(),
                    emptyMap(),
                )
                .download(PreparationRecord(p, manifest), output)
        }
        assertFails {
            PreparedPackages(artifacts(manifest, files - "App.ipa"), config, commands(), emptyMap())
                .download(PreparationRecord(p, manifest), output)
        }
        assertFails {
            PreparedPackages(
                    artifacts(manifest, files + ("App.ipa" to "tampered".toByteArray())),
                    config,
                    commands(),
                    emptyMap(),
                )
                .download(PreparationRecord(p, manifest), output)
        }
        assertFails {
            PreparedPackages(artifacts(manifest, files), config, commands(), emptyMap())
                .download(PreparationRecord(p), output)
        }
    }

    @Test
    fun `legacy package and receipt decode unchanged while schema two cannot lose provenance`() {
        val legacy =
            """{"sha":"historical-sha","version":"26.09.01","number":"234.1","workflow":"https://example.test/run","artifactName":"ios-build-234.1","ipa":{"name":"App.ipa","sha256":"old-digest","bytes":3},"symbolsAbsentReason":"No dSYMs"}"""
        val manifest = json.decodeFromString<ArtifactManifest>(legacy)
        assertEquals(1, manifest.schema)
        assertNull(manifest.preparation)
        val receipt =
            json.decodeFromString<Receipt>(
                """{"sha":"historical-sha","version":"26.09.01","number":"234.1","asc_build_id":"apple-id"}"""
            )
        assertNull(receipt.preparation)
        val p = reserve(PackageOrigin.MANUAL)
        val modern = Receipt(p.request.sha, p.version, p.number, "apple-new", preparation = p)
        assertEquals(modern, json.decodeFromString<Receipt>(json.encodeToString(modern)))
        assertFails { preparedManifest(p).copy(schema = 1) }
        assertFails { preparedManifest(p).copy(schema = 1, preparation = null) }
        assertFails { preparedManifest(p).copy(preparation = null) }
        assertFails {
            preparedManifest(p).copy(ipa = preparedManifest(p).ipa.copy(name = "manifest.json"))
        }
    }
}
