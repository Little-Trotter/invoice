package de.littletrotter.xpdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import software.amazon.awssdk.regions.Region

/**
 * [S3Clients] builds the two AWS SDK v2 collaborators every RustFS-backed store/presigner
 * in this module needs (`endpointOverride` + path-style addressing + a signature-filler region).
 * No network call happens on construction, so these are ordinary unit tests.
 */
class S3ClientsTest {

    @Test
    fun `happy path - build and presigner both construct against a valid RustFS endpoint`() {
        val client = S3Clients.build("http://localhost:9000", "rustfsadmin", "rustfsadmin")
        val presigner = S3Clients.presigner("http://localhost:9000", "rustfsadmin", "rustfsadmin")

        assertNotNull(client)
        assertNotNull(presigner)
    }

    @Test
    fun `boundary - a malformed endpoint URI fails fast building the S3Client`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3Clients.build("http://bad host with spaces", "key", "secret")
        }
    }

    @Test
    fun `boundary - a malformed endpoint URI fails fast building the S3Presigner`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3Clients.presigner("http://bad host with spaces", "key", "secret")
        }
    }

    @Test
    fun `boundary - credentials resolves exactly the access and secret key handed in, and the region is stable filler`() {
        val provider = S3Clients.credentials("access-123", "secret-456")

        val resolved = provider.resolveCredentials()

        assertEquals("access-123", resolved.accessKeyId())
        assertEquals("secret-456", resolved.secretAccessKey())
        assertEquals(Region.US_EAST_1, S3Clients.DEFAULT_REGION)
    }
}
