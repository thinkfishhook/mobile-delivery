package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class SplitDeliveryTest {
    @Test
    fun `interrupted unsealed preparation keeps its number but records the actual producing attempt`() =
        temp { root ->
            val world = World(root)
            val store = MemoryReservations()
            val service =
                SplitDelivery(
                    config,
                    world.apple,
                    world.source,
                    world.builds,
                    PreparationReservations(store) { emptySet() },
                    store,
                    { _, _ -> error("not retained yet") },
                    clock,
                )
            val request = preparationRequest(origin = PackageOrigin.MANUAL)
            world.builds.archiveError = CommandError("archive interrupted")
            assertFails { service.prepare(request, root) }
            val reserved = store.read().records.single().preparation
            world.builds.archiveError = null
            val result =
                service.prepare(request.copy(producer = request.producer.copy(attempt = 2)), root)
            assertEquals(reserved, result.record.preparation)
            assertEquals("${reserved.workflow}/attempts/2", result.record.manifest!!.workflow)
        }

    @Test
    fun `CI verifies frozen source once and prepares once without uploading`() = temp { root ->
        val world = World(root)
        val request = preparationRequest()
        val store = MemoryReservations()
        val service =
            SplitDelivery(
                config,
                world.apple,
                world.source,
                world.builds,
                PreparationReservations(store) { emptySet() },
                store,
                { _, _ -> error("no recovery expected") },
                clock,
            )
        service.verify(request, root)
        val result = service.prepare(request, root)
        assertEquals(listOf(request.sha), world.builds.verified)
        assertEquals(listOf(request.sha), world.builds.archived)
        assertTrue(world.apple.calls.isEmpty())
        assertEquals(PackageOrigin.CI_VERIFIED, result.record.preparation.request.origin)
        assertNotNull(result.record.manifest)
    }

    @Test
    fun `manual branch builds without tests while failed or mismatched CI proof cannot prepare`() =
        temp { root ->
            val world = World(root)
            val store = MemoryReservations()
            val service =
                SplitDelivery(
                    config,
                    world.apple,
                    world.source,
                    world.builds,
                    PreparationReservations(store) { emptySet() },
                    store,
                    { _, _ -> error("no recovery expected") },
                    clock,
                )
            assertFails { service.prepare(preparationRequest(), root) }
            assertTrue(world.builds.archived.isEmpty())
            val manual =
                preparationRequest(ref = "refs/heads/experiment", origin = PackageOrigin.MANUAL)
            service.prepare(manual, root)
            assertTrue(world.builds.verified.isEmpty())
            assertEquals(listOf(manual.sha), world.builds.archived)
            world.builds.verifyError = VerificationFailure("fixture", FailureKind.VALIDATION, "red")
            assertFails { service.verify(preparationRequest(), root) }
            assertFails { service.prepare(preparationRequest(), root) }
            assertEquals(1, world.builds.archived.size)
        }

    @Test
    fun `sealed preparation reruns reuse retained identity and bytes without another archive`() =
        temp { root ->
            val world = World(root)
            val store = MemoryReservations()
            val request = preparationRequest(origin = PackageOrigin.MANUAL)
            var recovered = 0
            val service =
                SplitDelivery(
                    config,
                    world.apple,
                    world.source,
                    world.builds,
                    PreparationReservations(store) { emptySet() },
                    store,
                    { record, directory ->
                        recovered++
                        val manifest = record.manifest!!
                        RetainedPackage(directory.resolve(manifest.ipa.name), manifest)
                    },
                    clock,
                )
            val first = service.prepare(request, root)
            val again =
                service.prepare(request.copy(producer = request.producer.copy(attempt = 2)), root)
            assertEquals(first.record, again.record)
            assertFalse(again.retain)
            assertEquals(1, recovered)
            assertEquals(1, world.builds.archived.size)
        }
}
