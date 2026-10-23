package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class BuildTest {
    @Test
    fun `foreign and malformed marketing versions do not consume a revision`() {
        assertEquals(
            "26.09.01",
            Policy.marketingVersion(now, setOf("2026.06.02", "1.0", "26.09.999", "26.09.1")).value,
        )
    }

    @Test
    fun `a version Apple already has closes its train for new builds`() {
        assertEquals("26.09.01", Policy.marketingVersion(now, emptySet()).value)
        // Submitted from App Store Connect, like 26.09.01 on 2026-09-25.
        assertEquals("26.09.02", Policy.marketingVersion(now, setOf("26.09.01")).value)
        assertEquals("26.09.04", Policy.marketingVersion(now, setOf("26.09.01", "26.09.03")).value)
        assertEquals(
            "26.10.01",
            Policy.marketingVersion(now.plusMonths(1), setOf("26.09.03")).value,
        )
        assertEquals("27.01.01", Policy.marketingVersion(now.plusMonths(4), setOf("1.0")).value)
        assertFails { Policy.marketingVersion(now, setOf("26.09.99")) }
    }

    @Test
    fun `build numbers stay inside Apple's range`() {
        assertEquals("404.2", Policy.buildNumber(404, 2).value)
        listOf(0 to 1, 10000 to 1, 404 to 0, 404 to 100).forEach { (count, attempt) ->
            assertFails { Policy.buildNumber(count, attempt) }
        }
    }

    @Test
    fun `invalid versions and numbers are rejected`() {
        listOf("1.0", "26.13.01", "26.09.00").forEach { assertFails { MarketingVersion(it) } }
        listOf("7", "300", "0.1", "1.0", "1.100").forEach { assertFails { BuildNumber(it) } }
        assertEquals(2, BuildNumber("404.2").attempt)
    }

    @Test
    fun `an archive writes its manifest and build evidence`() = temp { output ->
        val build =
            Build(
                "a".repeat(40),
                MarketingVersion("26.09.02"),
                BuildNumber("405.1"),
                "https://example.test/run",
                "26.6",
                "5.1.0",
                "digest",
                42,
            )
        build.artifact =
            ArtifactManifest(
                sha = build.sha,
                version = build.version,
                number = build.number,
                workflow = "https://example.test/run",
                artifactName = artifactName(build.number),
                ipa = ArtifactFile("ExampleApp.ipa", "digest", 42),
                symbolsAbsentReason = "Archive contained no dSYMs",
            )
        writeArchiveEvidence(output, build)
        assertEquals(
            build.artifact,
            json.decodeFromString<ArtifactManifest>(output.resolve("manifest.json").readText()),
        )
        assertContains(output.resolve("artifact.json").readText(), "\"405.1\"")
    }
}
