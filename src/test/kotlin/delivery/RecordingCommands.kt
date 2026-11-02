package delivery

import java.nio.file.Path

class RecordingCommands : Commands {
    data class Call(
        val args: List<String>,
        val env: Map<String, String>,
        val input: String?,
        val cwd: Path?,
        val log: Path?,
        val timeoutSeconds: Long = 21600,
    )

    val calls = mutableListOf<Call>()
    var responses = ArrayDeque<CommandResult>()
    var respond: ((Call) -> CommandResult)? = null

    override fun execute(
        args: List<String>,
        cwd: Path?,
        env: Map<String, String>,
        input: String?,
        log: Path?,
        timeoutSeconds: Long,
    ): CommandResult {
        val call = Call(args, env, input, cwd, log, timeoutSeconds)
        calls += call
        return respond?.invoke(call) ?: responses.removeFirstOrNull() ?: CommandResult(0, "{}", "")
    }

    fun response(text: String) {
        responses.add(CommandResult(0, text, ""))
    }
}
