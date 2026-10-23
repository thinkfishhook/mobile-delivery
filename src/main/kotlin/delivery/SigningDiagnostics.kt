package delivery

internal fun distributionIdentities(output: String): Set<String> =
    Regex("\\b([0-9A-Fa-f]{40})\\s+\"(?:Apple|iPhone) Distribution:")
        .findAll(output)
        .map { it.groupValues[1].uppercase() }
        .toSet()

// Only fingerprints, counts and known diagnostic codes leave the native-tool boundary.
internal fun signingDiagnostic(
    profile: Set<String>,
    valid: Set<String>,
    certificates: String,
    identities: String,
): String {
    val imported =
        Regex("SHA-1 hash: ([0-9A-Fa-f]{40})")
            .findAll(certificates)
            .map { it.groupValues[1].uppercase() }
            .toSet()
    val pairs = distributionIdentities(identities)
    val matching = imported.intersect(profile)
    val reason =
        when {
            matching.isEmpty() ->
                "The imported P12 contains no certificate selected by the provisioning profile. Export the matching distribution identity or regenerate the profile for that certificate."
            pairs.intersect(profile).isEmpty() ->
                "The profile certificate was imported, but macOS found no matching distribution identity. Check that the P12 includes its private key and uses an Apple Distribution certificate."
            valid.intersect(profile).isEmpty() ->
                "The certificate and private key match the profile, but macOS rejects them for code signing. Check certificate expiry, revocation and the Apple WWDR trust chain on this runner."
            valid.intersect(profile).size > 1 ->
                "Multiple valid identities match the profile. Supply one distribution identity."
            else -> "Exactly one valid distribution identity matches the profile."
        }
    val codes =
        Regex("\\b(?:CSSMERR|errSec)[A-Za-z0-9_]+\\b")
            .findAll(identities)
            .map { it.value }
            .toSortedSet()
    fun Set<String>.summary() = sorted().joinToString().ifEmpty { "none" }
    return listOf(
            reason,
            "Profile certificate SHA-1: ${profile.summary()}",
            "Imported certificate SHA-1: ${imported.summary()}",
            "Distribution identities including invalid: ${pairs.summary()}",
            "Valid distribution identities: ${valid.summary()}",
            "macOS certificate status: ${codes.joinToString().ifEmpty { "no status code reported" }}",
        )
        .joinToString("\n")
}
