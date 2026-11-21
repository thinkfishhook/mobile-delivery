package delivery

import kotlin.test.*
import org.junit.Test

class PreparationCompatibilityTest {
    @Test
    fun `CI provenance for another app or repository cannot qualify an App Store candidate`() =
        temp { output ->
            val world = World(output)
            val original = preparation(PackageOrigin.CI_VERIFIED)
            world.apple.builds +=
                TestFlightBuild(
                    "wrong-context",
                    original.version.value,
                    original.number.value,
                    "VALID",
                    false,
                )
            val release =
                AppStoreRelease(
                    config,
                    world.apple,
                    world.tags,
                    world.source,
                    world.symbols(),
                    world.env("release"),
                    clock,
                )
            for (request in
                listOf(
                    original.request.copy(bundleId = "example.other.app"),
                    original.request.copy(repository = "other/repo"),
                )) {
                val foreign = original.copy(request = request)
                world.tags.tags["deploy/testflight/${foreign.version}/${foreign.number}"] =
                    foreign.request.sha to
                        Receipt(
                            foreign.request.sha,
                            foreign.version,
                            foreign.number,
                            "wrong-context",
                            preparation = foreign,
                        )
                assertNull(selectBuild(release.facts()))
            }
        }

    @Test
    fun `ledger read failure remains a reported receipt repair error`() = temp { output ->
        val world = World(output)
        val p = preparation(PackageOrigin.MANUAL)
        world.apple.builds +=
            TestFlightBuild("reserved", p.version.value, p.number.value, "VALID", false)
        val release =
            AppStoreRelease(
                config,
                world.apple,
                world.tags,
                world.source,
                world.symbols(),
                world.env("release"),
                clock,
            )
        val sweep =
            Sweep(
                config,
                world.beta(),
                release,
                world.apple,
                world.tags,
                world.source,
                world.incidents,
                { false },
                preparations = { throw CommandError("ledger unavailable") },
            )
        val report = sweep.run("source-sha")
        assertTrue(report.broken)
        assertTrue(
            report.findings.any {
                it.operationKey is IncidentSubject.Receipt &&
                    it.repairError?.contains("ledger unavailable") == true
            }
        )
        assertTrue(world.tags.tags.isEmpty())
    }

    private fun preparation(origin: PackageOrigin) =
        Preparation(
            preparationRequest(origin = origin),
            MarketingVersion("26.09.01"),
            BuildNumber("234.1.1"),
        )

    @Test
    fun `manual modern receipt is excluded from App Store selection but legacy receipt remains eligible`() =
        temp { output ->
            val world = World(output)
            world.deliver("source-sha")
            val p = preparation(PackageOrigin.MANUAL)
            world.apple.builds +=
                TestFlightBuild("manual", p.version.value, p.number.value, "VALID", false)
            val tag = "deploy/testflight/${p.version}/${p.number}"
            world.tags.write(
                tag,
                p.request.sha,
                Receipt(p.request.sha, p.version, p.number, "manual", preparation = p),
            )
            val release =
                AppStoreRelease(
                    config,
                    world.apple,
                    world.tags,
                    world.source,
                    world.symbols(),
                    world.env("release"),
                    clock,
                )
            assertEquals("234.1", selectBuild(release.facts())?.build?.number)
            val ci = p.copy(request = p.request.copy(origin = PackageOrigin.CI_VERIFIED))
            world.tags.tags[tag] =
                ci.request.sha to
                    Receipt(ci.request.sha, ci.version, ci.number, "manual", preparation = ci)
            assertEquals(p.number.value, selectBuild(release.facts())?.build?.number)
        }

    @Test
    fun `legacy delivery tracing ignores reserved numeric components as commit counts`() =
        temp { output ->
            val world = World(output)
            val p = preparation(PackageOrigin.MANUAL)
            world.apple.builds +=
                TestFlightBuild("manual", p.version.value, p.number.value, "VALID", false)
            assertTrue(world.beta().traces("source-sha").traces.isEmpty())
            assertFalse(world.sweep().run("source-sha").broken)
            assertTrue(world.tags.tags.isEmpty())
        }

    @Test
    fun `reserved receipt audit validates provenance instead of inferring a commit count`() =
        temp { output ->
            val world = World(output)
            val p = preparation(PackageOrigin.MANUAL)
            world.tags.write(
                "deploy/testflight/${p.version}/${p.number}",
                p.request.sha,
                Receipt(p.request.sha, p.version, p.number, "manual", preparation = p),
            )
            assertFalse(world.sweep().run("source-sha", audit = true).broken)
        }

    @Test
    fun `sweep repairs reserved receipts from sealed ledger without promoting manual origin`() =
        temp { output ->
            val world = World(output)
            val p = preparation(PackageOrigin.MANUAL)
            world.apple.builds +=
                TestFlightBuild("manual", p.version.value, p.number.value, "VALID", false)
            world.apple.testers += "manual"
            val release =
                AppStoreRelease(
                    config,
                    world.apple,
                    world.tags,
                    world.source,
                    world.symbols(),
                    world.env("release"),
                    clock,
                )
            val sweep =
                Sweep(
                    config,
                    world.beta(),
                    release,
                    world.apple,
                    world.tags,
                    world.source,
                    world.incidents,
                    { false },
                    preparations = { listOf(PreparationRecord(p, preparedManifest(p))) },
                )
            val report = sweep.run("source-sha", audit = true)
            assertFalse(report.broken)
            val receipt = world.tags.read("deploy/testflight/${p.version}/${p.number}")!!
            assertEquals(p.request.sha, receipt.sha)
            assertEquals(PackageOrigin.MANUAL, receipt.preparation?.request?.origin)
            assertNull(selectBuild(release.facts()))
        }
}
