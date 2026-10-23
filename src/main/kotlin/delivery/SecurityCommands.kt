package delivery

// security's interactive mode reads one command from stdin without exposing it in argv.
// Send exactly one command: a following successful command would mask its exit status.
fun Commands.security(args: List<String>, env: Map<String, String>): String {
    require(args.all { it.none { char -> char == '\n' || char == '\r' || char == '\u0000' } }) {
        "Security command arguments must be single-line and NUL-free"
    }
    val input =
        args.joinToString(" ") {
            "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } + "\n"
    // Apple's interactive reader has a 4096-byte buffer, including the terminator.
    require(input.toByteArray(Charsets.UTF_8).size < 4096) {
        "Security command exceeds the interactive input limit"
    }
    return run(listOf("security", "-i"), env = buildEnvironment(env), input = input)
}
