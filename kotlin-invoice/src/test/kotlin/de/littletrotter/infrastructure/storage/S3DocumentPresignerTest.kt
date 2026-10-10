package de.littletrotter.infrastructure.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * [S3DocumentPresigner.presignGet] HEADs the object first (so [PresignedDocument.sizeBytes]
 * reflects the object *now*, see the class's own KDoc) and only signs a GET URL when that HEAD
 * succeeds. Both AWS SDK collaborators are mocked: a real HEAD/presign round-trip against RustFS is
 * [S3DocumentStoreIT]'s concern, not this unit's.
 */
class S3DocumentPresignerTest {

    // A real (not mocked) PresignedGetObjectRequest: it's a plain AWS SDK value object built from
    // an SdkHttpRequest, and Mockito cannot intercept url()/expiration() on it the way it can on an
    // interface-backed client/presigner — building the real thing is simpler than fighting that.
    private fun presignedRequest(url: String): PresignedGetObjectRequest =
        PresignedGetObjectRequest.builder()
            .httpRequest(SdkHttpRequest.builder().uri(URI.create(url)).method(SdkHttpMethod.GET).build())
            .expiration(Instant.now().plusSeconds(900))
            .isBrowserExecutable(false)
            .signedHeaders(mapOf("host" to listOf("rustfs.example")))
            .build()

    @Test
    fun `happy path - presignGet HEADs the object then returns url and current size`() {
        val client = mock<S3Client>()
        val presigner = mock<S3Presigner>()
        whenever(client.headObject(any<software.amazon.awssdk.services.s3.model.HeadObjectRequest>()))
            .thenReturn(HeadObjectResponse.builder().contentLength(4096L).build())
        whenever(presigner.presignGetObject(any<software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest>()))
            .thenReturn(presignedRequest("https://rustfs.example/invoice-documents/documents/2026/a.pdf?sig=abc"))
        val target = S3DocumentPresigner(client, presigner, "invoice-documents")

        val result = target.presignGet("documents/2026/a.pdf", Duration.ofMinutes(15))

        requireNotNull(result)
        assertEquals("https://rustfs.example/invoice-documents/documents/2026/a.pdf?sig=abc", result.url)
        assertEquals(4096L, result.sizeBytes)
    }

    @Test
    fun `boundary - missing object returns null instead of throwing`() {
        val client = mock<S3Client>()
        val presigner = mock<S3Presigner>()
        whenever(client.headObject(any<software.amazon.awssdk.services.s3.model.HeadObjectRequest>()))
            .thenThrow(S3Exception.builder().message("Not Found").statusCode(404).build())
        val target = S3DocumentPresigner(client, presigner, "invoice-documents")

        val result = target.presignGet("documents/2026/missing.pdf", Duration.ofMinutes(15))

        assertNull(result)
    }

    @Test
    fun `boundary - a failed HEAD never reaches the presigner`() {
        val client = mock<S3Client>()
        val presigner = mock<S3Presigner>()
        whenever(client.headObject(any<software.amazon.awssdk.services.s3.model.HeadObjectRequest>()))
            .thenThrow(S3Exception.builder().message("Forbidden").statusCode(403).build())
        val target = S3DocumentPresigner(client, presigner, "invoice-documents")

        target.presignGet("documents/2026/forbidden.pdf", Duration.ofMinutes(15))

        org.mockito.kotlin.verifyNoInteractions(presigner)
    }

    @Test
    fun `boundary - signature duration is forwarded unchanged as the presign expiry`() {
        val client = mock<S3Client>()
        val presigner = mock<S3Presigner>()
        whenever(client.headObject(any<software.amazon.awssdk.services.s3.model.HeadObjectRequest>()))
            .thenReturn(HeadObjectResponse.builder().contentLength(10L).build())
        var capturedTtl: Duration? = null
        whenever(presigner.presignGetObject(any<software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest>(0)
                capturedTtl = request.signatureDuration()
                presignedRequest("https://rustfs.example/x.pdf")
            }
        val target = S3DocumentPresigner(client, presigner, "invoice-documents")

        target.presignGet("x.pdf", Duration.ofHours(2))

        assertEquals(Duration.ofHours(2), capturedTtl)
    }
}
