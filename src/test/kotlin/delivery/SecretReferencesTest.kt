package delivery

import kotlin.test.*
import org.junit.Test

class SecretReferencesTest {
    @Test
    fun `literal braces escaped quotes and context words do not hide named sources`() {
        assertEquals(
            setOf("ASC_PRIVATE_KEY"),
            secretReferences("\${{ format('secrets }} it''s', SeCrEtS.asc_private_key) }}"),
        )
        assertTrue(secretReferences("No secrets are bound here").isEmpty())
        assertTrue(secretReferences("\${{ 'secrets.ASC_PRIVATE_KEY' }}").isEmpty())
    }
}
