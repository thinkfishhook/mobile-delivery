package delivery

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*
import org.junit.Test

internal fun preparationRequest(
    sha: String = "a".repeat(40),
    ref: String = "refs/heads/trunk",
    run: String = "123",
    origin: PackageOrigin = PackageOrigin.CI_VERIFIED,
) =
    PreparationRequest(
        "owner/repo",
        config.bundleId,
        sha,
        ref,
        origin,
        PackageProducer(".github/workflows/ci.yml", run, 1),
    )

internal class MemoryReservations : PreparationStore {
    private var revision = 0
    private var records = emptyList<PreparationRecord>()
    var loseResponse = false

    @Synchronized override fun read() = PreparationSnapshot(revision.toString(), records)

    @Synchronized
    override fun write(expected: PreparationSnapshot, records: List<PreparationRecord>): Boolean {
        if (expected.revision != revision.toString()) return false
        this.records = records
        revision++
        if (loseResponse) throw CommandError("Response lost after reservation write")
        return true
    }
}

class PreparationTest {
    private val version = MarketingVersion("26.11.01")

    @Test
    fun `different branches with equal commit counts reserve distinct monotonic identities`() {
        val store = MemoryReservations()
        val reservations = PreparationReservations(store) { setOf("234.1", "233.9") }
        val trunk = reservations.reserve(preparationRequest(), version)
        val branch =
            reservations.reserve(
                preparationRequest(
                    "b".repeat(40),
                    "refs/heads/experiment",
                    "124",
                    PackageOrigin.MANUAL,
                ),
                version,
            )
        assertEquals(BuildNumber("234.1.1"), trunk.number)
        assertEquals(BuildNumber("234.1.2"), branch.number)
        assertNotEquals(trunk.id, branch.id)
        assertEquals(PackageOrigin.MANUAL, branch.request.origin)
    }

    @Test
    fun `concurrent reservations and concurrent reruns have one durable result each`() {
        val store = MemoryReservations()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val reserved =
                pool
                    .invokeAll(
                        (1..12).map { n ->
                            Callable {
                                PreparationReservations(store) { setOf("234.1") }
                                    .reserve(preparationRequest(run = n.toString()), version)
                            }
                        }
                    )
                    .map { it.get() }
            assertEquals(12, reserved.map { it.number }.toSet().size)
            val reruns =
                pool
                    .invokeAll(
                        (1..6).map {
                            Callable {
                                PreparationReservations(store) {
                                        error("existing preparation must not reallocate")
                                    }
                                    .reserve(preparationRequest(run = "1"), version)
                            }
                        }
                    )
                    .map { it.get() }
            assertEquals(1, reruns.toSet().size)
            assertEquals(12, store.read().records.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `rerun resumes first identity while deliberate rebuild reserves a new number`() {
        val reservations = PreparationReservations(MemoryReservations()) { setOf("234.1") }
        val request = preparationRequest()
        val first = reservations.reserve(request, version)
        assertEquals(
            first,
            reservations.reserve(
                request.copy(producer = request.producer.copy(attempt = 2)),
                MarketingVersion("26.12.01"),
            ),
        )
        val rebuild = reservations.reserve(request.copy(rebuild = 2), version)
        assertNotEquals(first.number, rebuild.number)
        assertFails { reservations.reserve(request.copy(sha = "b".repeat(40)), version) }
        assertFails { reservations.reserve(request.copy(origin = PackageOrigin.MANUAL), version) }
    }

    @Test
    fun `reservation survives interrupted preparation lost response and expired artifacts`() {
        val store = MemoryReservations().apply { loseResponse = true }
        val first =
            PreparationReservations(store) { setOf("234.1") }.reserve(preparationRequest(), version)
        store.loseResponse = false
        val retry =
            PreparationReservations(store) { setOf("999.1") }.reserve(preparationRequest(), version)
        assertEquals(first, retry)
        val next =
            PreparationReservations(store) { setOf("999.1") }
                .reserve(preparationRequest(run = "124"), version)
        assertEquals(BuildNumber("999.1.1"), next.number)
    }

    @Test
    fun `number allocation handles legacy aliases carry and exhausted Apple range`() {
        assertEquals(BuildNumber("235.0.1"), nextPreparationNumber(setOf("234.99.99")))
        assertEquals(BuildNumber("234.2.1"), nextPreparationNumber(setOf("0234.01.99")))
        assertEquals(BuildNumber("234.1.1"), nextPreparationNumber(setOf("234.1", "234.1.0")))
        assertFails { nextPreparationNumber(setOf("9999.99.99")) }
        assertFails { nextPreparationNumber(setOf("unrecognized-number")) }
        assertFails { BuildNumber("234.1.0") }
        assertFails { BuildNumber("234.1.100") }
        assertEquals(234, BuildNumber("234.1").legacyCount)
        assertNull(BuildNumber("234.1.1").legacyCount)
    }
}
