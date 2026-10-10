package de.littletrotter.xpdf

import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.net.URI
import java.time.Instant

class DocumentControllerTest {

    private val fixedTime: TimeSource = ClockTimeSource.fixedAt(Instant.parse("2026-01-15T10:30:00Z"))

    @Test
    fun `presign rejects missing params before signing`() {
        val controller = DocumentController(fixedTime)

        val ex1 = assertThrows(IllegalArgumentException::class.java) {
            controller.presign(null, "obj", 60)
        }
        assertEquals("bucket and objectKey are required", ex1.message)

        val ex2 = assertThrows(IllegalArgumentException::class.java) {
            controller.presign("bucket", "", 60)
        }
        assertEquals("bucket and objectKey are required", ex2.message)
    }

    @Test
    fun `presign rejects unsafe object keys and invalid ttl`() {
        val controller = DocumentController(fixedTime)
        assertEquals("invalid objectKey", assertThrows(IllegalArgumentException::class.java) {
            controller.presign("bucket", "../secret.pdf", 60)
        }.message)
        assertEquals("invalid objectKey", assertThrows(IllegalArgumentException::class.java) {
            controller.presign("bucket", "/absolute.pdf", 60)
        }.message)
        assertEquals("ttl must be between 1 and 900 seconds", assertThrows(IllegalArgumentException::class.java) {
            controller.presign("bucket", "invoice.pdf", 901)
        }.message)
    }

    @Test
    fun `boundary - presign rejects blank bucket`() {
        val controller = DocumentController(fixedTime)
        assertEquals("bucket and objectKey are required", assertThrows(IllegalArgumentException::class.java) {
            controller.presign("   ", "obj", 60)
        }.message)
    }

    @Test
    fun `boundary - presign rejects bucket with invalid pattern`() {
        val controller = DocumentController(fixedTime)
        assertEquals("invalid bucket", assertThrows(IllegalArgumentException::class.java) {
            controller.presign("Invalid_Bucket!", "invoice.pdf", 60)
        }.message)
    }

    @Test
    fun `boundary - presign rejects object key exceeding max length`() {
        val controller = DocumentController(fixedTime)
        val tooLong = "a".repeat(513) + ".pdf"
        assertEquals("invalid objectKey", assertThrows(IllegalArgumentException::class.java) {
            controller.presign("bucket", tooLong, 60)
        }.message)
    }

    @Test
    fun `boundary - presign rejects object key with blank path segment`() {
        val controller = DocumentController(fixedTime)
        assertEquals("invalid objectKey", assertThrows(IllegalArgumentException::class.java) {
            controller.presign("bucket", "a//b.pdf", 60)
        }.message)
    }

    @Test
    fun `boundary - presign rejects ttl below minimum`() {
        val controller = DocumentController(fixedTime)
        assertEquals("ttl must be between 1 and 900 seconds", assertThrows(IllegalArgumentException::class.java) {
            controller.presign("bucket", "invoice.pdf", 0)
        }.message)
    }

    @Test
    fun `boundary - getS3Endpoint falls back to env when property is blank`() {
        System.setProperty("s3.endpoint", "  ")
        try {
            mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
                staticEnv.`when`<String?> { readEnv("S3_ENDPOINT") }.thenReturn("http://env:9000")

                val controller = DocumentController(mock(), fixedTime)
                assertEquals("http://env:9000", controller.s3Endpoint)
            }
        } finally {
            System.clearProperty("s3.endpoint")
        }
    }

    @Test
    fun `boundary - getS3Endpoint falls back to default when env is blank`() {
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { readEnv("S3_ENDPOINT") }.thenReturn("   ")

            val controller = DocumentController(mock(), fixedTime)
            assertEquals("http://rustfs:9000", controller.s3Endpoint)
        }
    }

    @Test
    fun `presign uses system properties for s3 config`() {
        System.setProperty("s3.endpoint", "http://localhost:9000")
        System.setProperty("s3.access-key", "access")
        System.setProperty("s3.secret-key", "secret")

        try {
            val controller = DocumentController(fixedTime)
            val ex = assertThrows(IllegalArgumentException::class.java) {
                controller.presign(null, "obj", 60)
            }
            assertEquals("bucket and objectKey are required", ex.message)
        } finally {
            System.clearProperty("s3.endpoint")
            System.clearProperty("s3.access-key")
            System.clearProperty("s3.secret-key")
        }
    }

    @Test
    fun `presign uses environment values when property unset`() {
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { readEnv("S3_ENDPOINT") }.thenReturn("http://localhost:9000")
            staticEnv.`when`<String?> { readEnv("S3_ACCESS_KEY") }.thenReturn("envAccess")
            staticEnv.`when`<String?> { readEnv("S3_SECRET_KEY") }.thenReturn("envSecret")

            val controller = DocumentController(fixedTime)
            val ex = assertThrows(IllegalArgumentException::class.java) {
                controller.presign(null, "obj", 60)
            }
            assertEquals("bucket and objectKey are required", ex.message)
        }
    }

    @Test
    fun `presign uses defaults when no property and no env supplied`() {
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { readEnv("S3_ENDPOINT") }.thenReturn(null)
            staticEnv.`when`<String?> { readEnv("S3_ACCESS_KEY") }.thenReturn(null)
            staticEnv.`when`<String?> { readEnv("S3_SECRET_KEY") }.thenReturn(null)

            val controller = DocumentController(fixedTime)
            val ex = assertThrows(IllegalArgumentException::class.java) {
                controller.presign(null, "obj", 60)
            }
            assertEquals("bucket and objectKey are required", ex.message)
        }
    }

    @Test
    fun `getS3Endpoint picks system property first`() {
        System.setProperty("s3.endpoint", "http://prop:9000")
        try {
            val controller = DocumentController(mock(), fixedTime)
            assertEquals("http://prop:9000", controller.s3Endpoint)
        } finally {
            System.clearProperty("s3.endpoint")
        }
    }

    @Test
    fun `getS3Endpoint uses env when property missing`() {
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { readEnv("S3_ENDPOINT") }.thenReturn("http://env:9000")

            val controller = DocumentController(mock(), fixedTime)
            assertEquals("http://env:9000", controller.s3Endpoint)
        }
    }

    @Test
    fun `getS3Endpoint falls back to default`() {
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { readEnv("S3_ENDPOINT") }.thenReturn(null)

            val controller = DocumentController(mock(), fixedTime)
            assertEquals("http://rustfs:9000", controller.s3Endpoint)
        }
    }

    @Test
    fun `presign returns url payload for valid request`() {
        val presigner: S3Presigner = mock()
        val response = presigned("http://rustfs/doc")
        whenever(presigner.presignGetObject(any<GetObjectPresignRequest>())).thenReturn(response)

        val controller = DocumentController(presigner, fixedTime)

        val result = controller.presign("documents", "invoice.pdf", 120)

        assertEquals("http://rustfs/doc", result["url"])
        assertEquals("documents", result["bucket"])
        assertEquals("invoice.pdf", result["objectKey"])

        verify(presigner).presignGetObject(check<GetObjectPresignRequest> { req ->
            assertEquals("documents", req.getObjectRequest().bucket())
            assertEquals("invoice.pdf", req.getObjectRequest().key())
            assertEquals(120L, req.signatureDuration().seconds)
        })
    }

    @Test
    fun `presign defaults ttl when not set`() {
        val presigner: S3Presigner = mock()
        val response = presigned("http://rustfs/doc")
        whenever(presigner.presignGetObject(any<GetObjectPresignRequest>())).thenReturn(response)

        val controller = DocumentController(presigner, fixedTime)

        val result = controller.presign("documents", "invoice.pdf", null)

        assertEquals("http://rustfs/doc", result["url"])
        assertEquals("documents", result["bucket"])
        assertEquals("invoice.pdf", result["objectKey"])

        verify(presigner).presignGetObject(check<GetObjectPresignRequest> { req ->
            assertEquals(900L, req.signatureDuration().seconds)
        })
    }

    // ── PresignCache integration ──────────────────────────────────────────────

    @Test
    fun `presign returns cached url without calling presigner on second request`() {
        PresignCache.clear()
        val presigner: S3Presigner = mock()
        val response = presigned("http://rustfs/cached")
        whenever(presigner.presignGetObject(any<GetObjectPresignRequest>())).thenReturn(response)

        val controller = DocumentController(presigner, fixedTime)

        val first  = controller.presign("docs", "RE-001.pdf", 900)
        val second = controller.presign("docs", "RE-001.pdf", 900)

        assertEquals("http://rustfs/cached", first["url"])
        assertEquals("http://rustfs/cached", second["url"])
        // Presigner must only be called once — second request served from cache
        verify(presigner, org.mockito.Mockito.times(1)).presignGetObject(any<GetObjectPresignRequest>())
    }

    @Test
    fun `presign cache is scoped per bucket and objectKey`() {
        PresignCache.clear()
        val presigner: S3Presigner = mock()
        val responseA = presigned("http://rustfs/url-a")
        val responseB = presigned("http://rustfs/url-b")
        whenever(presigner.presignGetObject(any<GetObjectPresignRequest>()))
            .thenReturn(responseA)
            .thenReturn(responseB)

        val controller = DocumentController(presigner, fixedTime)

        val resultA = controller.presign("docs", "a.pdf", 900)
        val resultB = controller.presign("docs", "b.pdf", 900)

        assertEquals("http://rustfs/url-a", resultA["url"])
        assertEquals("http://rustfs/url-b", resultB["url"])
        verify(presigner, org.mockito.Mockito.times(2)).presignGetObject(any<GetObjectPresignRequest>())
    }

    @Test
    fun `PresignCache get returns null after manual eviction`() {
        PresignCache.clear()
        PresignCache.put("docs", "RE-002.pdf", "http://rustfs/url", fixedTime.instant())
        PresignCache.evict("docs", "RE-002.pdf")

        assertNull(PresignCache.get("docs", "RE-002.pdf", fixedTime.instant()))
    }

    @Test
    fun `PresignCache get returns null when entry is expired`() {
        PresignCache.clear()
        val putAt = fixedTime.instant()
        // Put with 1 ms TTL, read 5 ms later — deterministically already expired, no real sleep.
        PresignCache.put("docs", "RE-003.pdf", "http://rustfs/url", putAt, ttlMs = 1)

        assertNull(PresignCache.get("docs", "RE-003.pdf", putAt.plusMillis(5)))
    }

    private fun presigned(url: String): PresignedGetObjectRequest {
        val response: PresignedGetObjectRequest = mock()
        whenever(response.url()).thenReturn(URI.create(url).toURL())
        return response
    }
}
