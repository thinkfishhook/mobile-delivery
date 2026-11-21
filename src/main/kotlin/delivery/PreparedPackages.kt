package delivery

import java.nio.file.Path

// The split workflow uses this bounded default; ledger identity outlives artifact retention.
const val PREPARED_PACKAGE_RETENTION_DAYS = 14

// Schema-2 recovery starts from trusted ledger/upstream identity, never a manually supplied
// artifact reference. #7/#8 wire reservation/preparation/upload operations to these boundaries.
class PreparedPackages(
    private val artifacts: BuildArtifacts,
    private val config: DeliveryConfig,
    private val commands: Commands,
    private val env: Map<String, String>,
) {
    fun download(record: PreparationRecord, output: Path): RetainedPackage {
        val preparation = record.preparation
        val expected =
            requireNotNull(record.manifest) {
                "Preparation has no sealed package; complete preparation before recovery"
            }
        require(preparation.request.bundleId == config.bundleId) {
            "Preparation belongs to another app"
        }
        val retained =
            requireNotNull(artifacts.retained(preparation.number)) {
                "Prepared package is missing; do not rebuild this identity, request a new build"
            }
        require(!retained.expired) {
            "Prepared package expired; request a new build rather than reusing its number"
        }
        require(
            retained.name == expected.artifactName &&
                retained.run == preparation.request.producer.runId
        ) {
            "Prepared package belongs to another producing run"
        }
        val downloaded = artifacts.download(retained, output)
        require(downloaded.manifest == expected) {
            "Retained package differs from the externally reserved and sealed identity"
        }
        // Inspect actual bundle/version/number, signature, entitlements and binary policy. The
        // sealed build number binds these bytes to the externally expected source SHA/run.
        val build = preparation.build()
        Signing(config, commands, env).verifyIpa(downloaded.ipa, build)
        require(build.ipaSha256 == expected.ipa.sha256 && build.ipaBytes == expected.ipa.bytes) {
            "Verified IPA differs from the sealed package"
        }
        return downloaded
    }
}
