package delivery

import java.nio.file.Path
import kotlin.io.path.*

enum class AscErrorKind {
    MALFORMED_KEY,
    REJECTED_KEY,
    INFRASTRUCTURE,
    OTHER,
}

// Never retain provider output as a message or cause: it may contain credentials.
class AscError(val kind: AscErrorKind, val status: Int?, exit: Int? = null, log: Path? = null) :
    CommandError(
        "App Store Connect command failed" + (status?.let { " (HTTP $it)" } ?: ""),
        exit,
        log,
    )

internal fun AscError.credentialProblem(): CredentialProblem? {
    val description =
        when (kind) {
            AscErrorKind.MALFORMED_KEY -> "ASC_PRIVATE_KEY is not a PKCS#8 .p8 key"
            AscErrorKind.REJECTED_KEY ->
                "App Store Connect rejected the API key in ASC_KEY_ID / ASC_ISSUER_ID / ASC_PRIVATE_KEY (revoked, wrong issuer, or wrong key)"
            else -> return null
        }
    return CredentialProblem(
        "ASC_PRIVATE_KEY",
        description,
        "Replace the API key in App Store Connect → Users and Access → Integrations, then run:\n" +
            "gh secret set ASC_KEY_ID --env ios-delivery\n" +
            "gh secret set ASC_ISSUER_ID --env ios-delivery\n" +
            "gh secret set ASC_PRIVATE_KEY --env ios-delivery < AuthKey.p8",
    )
}

internal fun classifyAscError(text: String, exit: Int?, log: Path? = null): AscError {
    val status =
        Regex("(?i)(?:HTTP(?:/[0-9.]+)?\\s+|status[\\s:=]+)([45][0-9]{2})\\b")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: when {
                "Authentication credentials are missing or invalid." in text -> 401
                "The given operation is not allowed." in text -> 403
                else -> null
            }
    val kind =
        when {
            status != null && status >= 500 -> AscErrorKind.INFRASTRUCTURE
            Regex("(?i)timed out|timeout|deadline exceeded").containsMatchIn(text) ->
                AscErrorKind.INFRASTRUCTURE
            status in setOf(401, 403) -> AscErrorKind.REJECTED_KEY
            Regex(
                    "(?i)invalid PEM data|invalid private key|failed to parse private key|private key is not ECDSA|private key must use the P-256 curve"
                )
                .containsMatchIn(text) -> AscErrorKind.MALFORMED_KEY
            else -> AscErrorKind.OTHER
        }
    return AscError(kind, status, exit, log)
}

// Both JSON queries and logged mutations use this single process boundary.
internal fun executeAsc(
    commands: Commands,
    args: List<String>,
    env: Map<String, String>,
    log: Path?,
): String {
    try {
        val result = commands.execute(listOf("asc") + args, env = env, log = log)
        if (result.exit == 0) return result.stdout
        val text =
            result.stdout +
                "\n" +
                result.stderr +
                "\n" +
                (log?.takeIf { it.exists() }?.readText() ?: "")
        throw classifyAscError(text, result.exit, log)
    } catch (error: CommandError) {
        val typed = error as? AscError ?: classifyAscError(error.message.orEmpty(), error.exit, log)
        log?.writeText((typed.credentialProblem()?.message ?: typed.message).orEmpty() + "\n")
        throw typed
    }
}
