package delivery

import java.nio.file.Path
import kotlin.test.*
import org.junit.Test

class EngineJavaTest {
    @Test
    fun `engine JDK resolution verifies downloads and preserves caller environment`() {
        val root = Path.of(System.getProperty("delivery.root"))
        val result =
            ProcessCommands()
                .execute(
                    listOf("python3", "internal/engine/test_java_home.py"),
                    root,
                    buildEnvironment(),
                )
        assertEquals(0, result.exit, result.stdout + result.stderr)
    }
}
