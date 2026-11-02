package delivery

// Incident writers own only this delimited block; human content is copied verbatim.
internal class OwnedStatusBlock(owner: String) {
    val start = "<!-- $owner-status:start -->"
    val end = "<!-- $owner-status:end -->"

    fun content(original: String): String? {
        if (start !in original && end !in original) return null
        require(
            original.split(start).size == 2 &&
                original.split(end).size == 2 &&
                original.indexOf(start) < original.indexOf(end)
        ) {
            "Owned incident block is malformed; preserve human content"
        }
        return original.substringAfter(start).substringBefore(end)
    }

    fun replace(original: String, block: String): String {
        if (content(original) == null) return original + "\n" + block
        return original.substringBefore(start) + block + original.substringAfter(end)
    }
}
