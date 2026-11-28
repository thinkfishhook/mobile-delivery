package delivery

import kotlin.test.*
import org.junit.Test

class SweepPreparationTest {
    @Test
    fun `sweep repairs a two-component build from its reservation before matching commit counts`() =
        temp { output ->
            val world = World(output)
            val preparation = twoComponentPreparation()
            val delivered = mutableListOf<String>()
            addTestableBuild(world, preparation)
            val sweep =
                world.sweepWithPreparation(
                    PreparationRecord(preparation, preparedManifest(preparation)),
                    onDelivered = { sha, _ -> delivered += sha },
                )

            val report = sweep.run(preparation.request.sha)

            assertFalse(report.broken)
            val receipt = world.tags.read(tag(preparation))!!
            assertEquals(preparation.request.sha, receipt.sha)
            assertEquals(preparation, receipt.preparation)
            assertEquals(listOf(preparation.request.sha), delivered)
            assertFalse(sweep.run(preparation.request.sha, audit = true).broken)
        }

    @Test
    fun `sweep never repairs an unsealed two-component reservation as a legacy build`() =
        temp { output ->
            val world = World(output)
            val preparation = twoComponentPreparation()
            addTestableBuild(world, preparation)

            world.sweepWithPreparation(PreparationRecord(preparation)).run("source-sha")

            assertNull(world.tags.read(tag(preparation)))
        }

    @Test
    fun `sweep corroborates an old two-component receipt without rewriting it`() = temp { output ->
        val world = World(output)
        val preparation = twoComponentPreparation()
        val rawReceipt =
            Receipt(preparation.request.sha, preparation.version, preparation.number, "ci")
        world.tags.write(tag(preparation), preparation.request.sha, rawReceipt)
        world.tags.failWrites = true
        world.apple.builds +=
            TestFlightBuild(
                "ci",
                preparation.version.value,
                preparation.number.value,
                "VALID",
                true,
            )
        val delivered = mutableListOf<String>()
        val sweep =
            world.sweepWithPreparation(
                PreparationRecord(preparation, preparedManifest(preparation)),
                onDelivered = { sha, _ -> delivered += sha },
            )

        val report = sweep.run(preparation.request.sha, audit = true)

        assertFalse(report.broken)
        assertEquals(listOf(preparation.request.sha), delivered)
        assertEquals(rawReceipt, world.tags.read(tag(preparation)))
    }

    @Test
    fun `audit rejects an old two-component receipt when Apple reports another build ID`() =
        temp { output ->
            val world = World(output)
            val preparation = twoComponentPreparation()
            world.tags.write(
                tag(preparation),
                preparation.request.sha,
                Receipt(preparation.request.sha, preparation.version, preparation.number, "old-id"),
            )
            addTestableBuild(world, preparation)
            val sweep =
                world.sweepWithPreparation(
                    PreparationRecord(preparation, preparedManifest(preparation))
                )

            val report = sweep.run(preparation.request.sha, audit = true)

            assertTrue(
                report.findings.any { finding ->
                    finding.operationKey == IncidentSubject.Audit && finding.repairError != null
                }
            )
            assertEquals(
                "delivery unread",
                report.findings
                    .single {
                        it.operationKey == IncidentSubject.BetaCommit(preparation.request.sha)
                    }
                    .outcome,
            )
        }

    @Test
    fun `audit rejects a two-component reserved receipt without preparation or Apple evidence`() =
        temp { output ->
            val world = World(output)
            val preparation = twoComponentPreparation()
            world.tags.write(
                tag(preparation),
                preparation.request.sha,
                Receipt(preparation.request.sha, preparation.version, preparation.number, "ci"),
            )
            val sweep =
                world.sweepWithPreparation(
                    PreparationRecord(preparation, preparedManifest(preparation))
                )

            val report = sweep.run("source-sha", audit = true)

            assertTrue(
                report.findings.any { finding ->
                    finding.operationKey == IncidentSubject.Audit && finding.repairError != null
                }
            )
        }

    private fun twoComponentPreparation() =
        Preparation(preparationRequest(), MarketingVersion("26.09.01"), BuildNumber("234.1"))

    private fun tag(preparation: Preparation) =
        "deploy/testflight/${preparation.version}/${preparation.number}"

    private fun addTestableBuild(world: World, preparation: Preparation) {
        world.apple.builds +=
            TestFlightBuild(
                "ci",
                preparation.version.value,
                preparation.number.value,
                "VALID",
                false,
            )
        world.apple.testers += "ci"
    }

    private fun World.sweepWithPreparation(
        reservation: PreparationRecord,
        onDelivered: (String, Trace) -> Unit = { _, _ -> },
    ) =
        Sweep(
            config,
            beta(),
            AppStoreRelease(config, apple, tags, source, symbols(), env("sweep"), clock),
            apple,
            tags,
            source,
            incidents,
            { false },
            preparations = { listOf(reservation) },
            onDelivered = onDelivered,
        )
}
