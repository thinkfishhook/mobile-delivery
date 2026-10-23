package delivery

import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.Test

class ReleasePackagingTest {
    @Test
    fun `release packaging protects prereleases major tags and executable archives`() {
        val root = Path.of(System.getProperty("delivery.root"))
        val result =
            ProcessCommands()
                .execute(
                    listOf("python3", "-B", "internal/test_release.py"),
                    root,
                    buildEnvironment(),
                )
        assertEquals(0, result.exit, result.stdout + result.stderr)
    }
}
