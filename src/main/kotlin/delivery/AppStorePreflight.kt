package delivery

// Read-only: reports what the next App Store release would do. It never stages, submits,
// changes a rollout or keeps symbols.
class AppStorePreflight(
    private val release: AppStoreRelease,
    private val apple: Apple,
    private val symbols: ReleaseSymbols,
) {
    fun verify(): List<String> {
        val facts = release.facts()
        val lines = mutableListOf<String>()
        if (facts.ignoredVersions.isNotEmpty())
            lines +=
                "Ignored marketing versions outside YY.MM.RR: ${facts.ignoredVersions.joinToString()}"
        facts.unresolved.forEach { lines += "Blocked: ${it.version} is ${it.state}" }
        facts.openSubmissions.forEach {
            lines +=
                "Blocked: review submission ${it.id} is ${it.state}. Submit or cancel it in App Store Connect."
        }
        facts.failedSubmissions.forEach {
            lines += "Scheduled runs skip: ${it.version} is ${it.state} with a build attached"
        }
        facts.holds
            .filter { it.open }
            .forEach {
                lines +=
                    "Hold #${it.issue}: ${if (it.malformed) "unresolvable, ${it.unresolved}" else it.build}"
            }
        facts.holds
            .filter { !it.open && it.fix == null && !it.lifted && it.commit != null }
            .forEach { lines += "Blocked: ${release.holdAction(it)}" }
        // The same selection a release makes, so each rule runs once per build.
        val selection = Selection(facts, release.rules(facts))
        val candidate = selection.selected
        lines +=
            candidate?.let { "Would select ${it.build.version}/${it.build.number}" }
                ?: "No TestFlight build is newer than the last App Store release"
        selection.candidates.take(3).forEach { option ->
            lines +=
                "${option.build.version}/${option.build.number}: " +
                    (selection.failure(option)?.let { "fails: $it" } ?: "eligible")
        }
        candidate
            ?.let { release.strayDraft(facts, it) }
            ?.let {
                lines +=
                    "Blocked: App Store version ${it.version} is a draft this pipeline did not make"
            }
        candidate?.let {
            lines +=
                symbols.problem(BuildNumber(it.build.number))?.let { problem ->
                    "Blocked: $problem"
                } ?: "Its dSYMs are retained and can be kept"
        }
        // A release copies its listing from the last one; the first ships by hand.
        val source = facts.lastRelease
        if (source == null) {
            lines +=
                "Blocked: no App Store version has been released yet; release the first by hand"
            return lines
        }
        apple.validateVersion(source.id)
        lines += "Metadata source ${source.version} passed read-only validation"
        return lines
    }
}
