package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class SplitPublisherTest {
    @Test
    fun `automatic handoff uploads prepared bytes once and recovers a failed receipt write`() =
        temp { root ->
            val world = World(root)
            val store = MemoryReservations()
            val request = preparationRequest()
            val service =
                SplitDelivery(
                    config,
                    world.apple,
                    world.source,
                    world.builds,
                    PreparationReservations(store) { emptySet() },
                    store,
                    { _, _ -> error("no recovery yet") },
                    clock,
                )
            service.verify(request, root)
            val result = service.prepare(request, root)
            val manifest = result.record.manifest!!
            var downloads = 0
            val download: (PreparationRecord, java.nio.file.Path) -> RetainedPackage =
                { record, directory ->
                    downloads++
                    assertEquals(result.record, record)
                    for (name in listOf(manifest.ipa.name, "manifest.json")) result.directory
                        .resolve(name)
                        .copyTo(directory.resolve(name), overwrite = true)
                    RetainedPackage(directory.resolve(manifest.ipa.name), manifest)
                }
            world.tags.failWrites = true
            val receipt = world.beta().publishPrepared(result.record, root, download, {})
            assertEquals(manifest.ipa.sha256, receipt.ipaSha256)
            assertEquals(listOf(manifest.number.value), world.apple.uploadedNumbers)
            world.tags.failWrites = false
            world
                .beta()
                .publishPrepared(
                    result.record,
                    root,
                    { _, _ -> error("Apple already has the bytes") },
                    { error("must reconcile") },
                )
            assertEquals(1, downloads)
            assertEquals(1, world.apple.uploadedNumbers.size)
            assertEquals(listOf(request.sha), world.builds.verified)
            assertEquals(listOf(request.sha), world.builds.archived)
            assertTrue(world.tags.names("deploy/testflight/").isNotEmpty())
        }

    @Test
    fun `branch advancement during download prevents a new upload`() = temp { root ->
        val world = World(root)
        val preparation =
            Preparation(preparationRequest(), MarketingVersion("26.09.01"), BuildNumber("234.1.1"))
        val manifest = preparedManifest(preparation)
        var admitted = 0
        var downloads = 0
        assertFailsWith<SplitSuperseded> {
            world.beta().publishPrepared(
                PreparationRecord(preparation, manifest),
                root,
                { _, _ ->
                    downloads++
                    RetainedPackage(root.resolve("App.ipa"), manifest)
                },
            ) {
                if (++admitted == 2) throw SplitSuperseded()
            }
        }
        assertEquals(1, downloads)
        assertEquals(2, admitted)
        assertTrue(world.apple.uploadedNumbers.isEmpty())
    }

    @Test
    fun `attempt provenance cannot switch repositories runs or precede its reservation`() {
        val request = preparationRequest().let { it.copy(producer = it.producer.copy(attempt = 2)) }
        val preparation = Preparation(request, MarketingVersion("26.09.01"), BuildNumber("234.1.1"))
        val manifest = preparedManifest(preparation)
        for (workflow in
            listOf(
                "2",
                "${preparation.workflow}/attempts/1",
                "${preparation.workflow}/attempts/02",
                "https://github.com/foreign/repo/actions/runs/123/attempts/2",
                "${preparation.workflow}4/attempts/2",
            )) {
            assertFails { manifest.copy(workflow = workflow) }
        }
        assertEquals(
            3,
            manifest.copy(workflow = "${preparation.workflow}/attempts/3").producingAttempt,
        )
    }

    @Test
    fun `already uploaded package resumes processing without expired artifact or obsolete-source cancellation`() =
        temp { root ->
            val world = World(root)
            val preparation =
                Preparation(
                    preparationRequest(),
                    MarketingVersion("26.09.01"),
                    BuildNumber("234.1.1"),
                )
            val record = PreparationRecord(preparation, preparedManifest(preparation))
            world.apple.builds +=
                TestFlightBuild(
                    "existing",
                    preparation.version.value,
                    preparation.number.value,
                    "VALID",
                    false,
                )
            val receipt =
                world
                    .beta()
                    .publishPrepared(
                        record,
                        root,
                        { _, _ ->
                            error("expired package must not prevent existing Apple recovery")
                        },
                        { error("must not apply new-upload admission to existing Apple build") },
                    )
            assertEquals(preparation.number, receipt.number)
            assertEquals(preparation, receipt.preparation)
            assertTrue(world.apple.uploadedNumbers.isEmpty())
            assertTrue(world.builds.verified.isEmpty())
            assertTrue(world.builds.archived.isEmpty())
        }

    @Test
    fun `stale new upload is rejected immediately before Apple without rebuild or reverify`() =
        temp { root ->
            val world = World(root)
            val preparation =
                Preparation(
                    preparationRequest(),
                    MarketingVersion("26.09.01"),
                    BuildNumber("234.1.1"),
                )
            val manifest = preparedManifest(preparation)
            var downloads = 0
            assertFailsWith<SplitSuperseded> {
                world
                    .beta()
                    .publishPrepared(
                        PreparationRecord(preparation, manifest),
                        root,
                        { _, _ ->
                            downloads++
                            RetainedPackage(root.resolve("App.ipa"), manifest)
                        },
                        { throw SplitSuperseded() },
                    )
            }
            assertEquals(0, downloads)
            assertTrue(world.apple.uploadedNumbers.isEmpty())
            assertTrue(world.builds.verified.isEmpty())
            assertTrue(world.builds.archived.isEmpty())
        }
}
