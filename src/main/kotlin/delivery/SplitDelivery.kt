package delivery

import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.*
import kotlinx.serialization.Serializable

@Serializable
private data class VerifiedSource(val request: PreparationRequest, val configuration: String)

data class PreparedResult(val record: PreparationRecord, val directory: Path, val retain: Boolean)

// The shared verification/archive implementations remain the only build engine. Split phases
// exchange an exact-source local verification receipt and the durable sealed reservation.
class SplitDelivery(
    private val config: DeliveryConfig,
    private val apple: Apple,
    private val source: Source,
    private val builds: BuildOperations,
    private val reservations: PreparationReservations,
    private val store: PreparationStore,
    private val recover: (PreparationRecord, Path) -> RetainedPackage,
    private val clock: Clock = Clock.systemUTC(),
) {
    private fun proof(root: Path) = root.resolve("build/delivery/verified-source.json")

    private fun configuration() =
        digest(json.encodeToString(DeliveryConfig.serializer(), config).toByteArray())

    fun verify(request: PreparationRequest, root: Path) {
        require(request.origin == PackageOrigin.CI_VERIFIED) {
            "Manual deployment does not invoke CI verification"
        }
        val proof = proof(root)
        proof.parent.createDirectories()
        proof.deleteIfExists()
        source.snapshot(request.sha) { checkout ->
            builds.verify(checkout, root.resolve("build/ci-verification"))
        }
        proof.writeText(
            json.encodeToString(
                VerifiedSource.serializer(),
                VerifiedSource(request, configuration()),
            )
        )
    }

    fun prepare(request: PreparationRequest, root: Path): PreparedResult {
        require(request.bundleId == config.bundleId) {
            "Preparation app differs from configuration"
        }
        if (request.origin == PackageOrigin.CI_VERIFIED) {
            val file = proof(root)
            require(file.isRegularFile()) {
                "This CI source has no successful verification receipt"
            }
            val verified = json.decodeFromString<VerifiedSource>(file.readText())
            require(verified.request == request && verified.configuration == configuration()) {
                "Verification receipt differs from this source/run/attempt/configuration"
            }
        }
        val version =
            Policy.marketingVersion(
                config.now(clock),
                apple
                    .storeVersions()
                    .filter { it.state !in StoreStates.editable }
                    .map { it.version }
                    .toSet(),
            )
        val preparation = reservations.reserve(request, version)
        val directory =
            root.resolve("build/retained-package").resolve(preparation.id).createDirectories()
        val existing = store.read().records.single { it.preparation.id == preparation.id }
        if (existing.manifest != null) {
            recover(existing, directory)
            return PreparedResult(existing, directory, false)
        }
        val output = root.resolve("build/delivery/${preparation.id}").createDirectories()
        try {
            return source.snapshot(request.sha) { checkout ->
                val build =
                    preparation
                        .build()
                        .copy(
                            workflow =
                                "${preparation.workflow}/attempts/${request.producer.attempt}",
                            xcode = config.xcode,
                            asc = config.asc,
                        )
                builds.archive(checkout, output, build)
                val manifest =
                    requireNotNull(build.artifact) { "Archive produced no package manifest" }
                val files = localPackage(build, output)
                val record = reservations.seal(preparation, manifest)
                files.forEach { (name, bytes) -> directory.resolve(name).writeBytes(bytes) }
                PreparedResult(record, directory, true)
            }
        } finally {
            output.resolve("DerivedData").toFile().deleteRecursively()
            output.resolve("${config.ios.name}.xcarchive").toFile().deleteRecursively()
        }
    }
}
