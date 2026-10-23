package delivery

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.junit.Test

class PreconditionMessageTest {
    // Parse Kotlin rather than matching text: comments, multiline calls, nested lambdas and
    // string templates must not let a bare precondition escape the guard.
    @OptIn(
        org.jetbrains.kotlin.CoreEnvironmentDeprecation::class,
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    private fun <T> withParser(action: (KtPsiFactory) -> T): T {
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForProduction(
                    disposable,
                    CompilerConfiguration().apply {
                        extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
                    },
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            return action(KtPsiFactory(environment.project))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun missingMessages(parser: KtPsiFactory, source: String): List<Int> =
        parser
            .createFile(source)
            .collectDescendantsOfType<KtCallExpression> {
                it.calleeExpression?.text in
                    setOf("require", "check", "requireNotNull", "checkNotNull") &&
                    it.lambdaArguments.isEmpty() &&
                    it.valueArguments.size < 2
            }
            .map { call -> source.take(call.textOffset).count { it == '\n' } + 1 }
            .sorted()

    @Test
    fun `guard finds nested bare preconditions and ignores messages and quoted examples`() =
        withParser { parser ->
            val source =
                listOf(
                        "// require(false)",
                        "val example = \"check(false)\"",
                        "fun demo(value: String?) {",
                        "    require(listOf(1).any { it > 0 })",
                        "    check(value != null) { \"Expected a value\" }",
                        "    requireNotNull(value, { \"Expected a value\" })",
                        "    println(\"value: \${checkNotNull(value)}\")",
                        "}",
                    )
                    .joinToString("\n")
            assertEquals(listOf(4, 7), missingMessages(parser, source))
        }

    @Test
    fun `every engine precondition explains what was expected`() = withParser { parser ->
        val root = Path.of(System.getProperty("delivery.root"))
        val missing =
            root
                .resolve("src/main/kotlin")
                .walk()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    missingMessages(parser, file.readText()).map { "${root.relativize(file)}:$it" }
                }
                .sorted()
                .toList()
        assertTrue(
            missing.isEmpty(),
            "Preconditions need actionable messages:\n${missing.joinToString("\n")}",
        )
    }
}
