package delivery

internal fun annotate(level: String, message: String) {
    val escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    println("::$level::$escaped")
}
