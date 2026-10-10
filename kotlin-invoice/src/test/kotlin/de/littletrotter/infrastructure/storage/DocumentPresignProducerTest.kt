package de.littletrotter.infrastructure.storage

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Optional

class DocumentPresignProducerTest {

    @Test
    fun `producer returns unsupported presigner when backend is absent`() {
        val producer = DocumentPresignProducer(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val presigner = producer.produce()

        assertTrue(presigner is UnsupportedDocumentPresigner)
        // UnsupportedDocumentPresigner.presignGet itself: null rather than a resolvable
        // yet non-functional bean, so the caller's own "cannot hand over attachments" path fires.
        assertNull(presigner.presignGet("documents/2026/a.pdf", Duration.ofMinutes(15)))
    }

    @Test
    fun `producer returns unsupported presigner for non-s3 backend`() {
        val producer = DocumentPresignProducer(
            Optional.of("filesystem"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val presigner = producer.produce()

        assertTrue(presigner is UnsupportedDocumentPresigner)
    }

    @Test
    fun `producer returns unsupported presigner for blank backend rather than throwing`() {
        val producer = DocumentPresignProducer(
            Optional.of("   "),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val presigner = producer.produce()

        assertTrue(presigner is UnsupportedDocumentPresigner)
    }

    @Test
    fun `producer returns s3 presigner for s3 backend with defaulted fields`() {
        val producer = DocumentPresignProducer(
            Optional.of("s3"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val presigner = producer.produce()

        assertTrue(presigner is S3DocumentPresigner)
    }

    @Test
    fun `producer returns s3 presigner for s3 backend with explicit fields`() {
        val producer = DocumentPresignProducer(
            Optional.of("s3"),
            Optional.of("http://localhost:9000"),
            Optional.of("rustfsadmin"),
            Optional.of("rustfsadmin"),
            Optional.of("invoice-documents")
        )

        val presigner = producer.produce()

        assertTrue(presigner is S3DocumentPresigner)
    }

    @Test
    fun `producer normalizes s3 backend value with surrounding whitespace and mixed case`() {
        val producer = DocumentPresignProducer(
            Optional.of("  S3  "),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )

        val presigner = producer.produce()

        assertTrue(presigner is S3DocumentPresigner)
    }

    @Test
    fun `producer fails fast when s3 credentials are present but blank rather than silently defaulting`() {
        // Unlike an absent Optional (which takes the DEFAULT_* fallback), a present-but-blank
        // Optional skips `.orElse(DEFAULT)` entirely (it only substitutes on an *empty* Optional) and
        // flows the blank string straight into AwsBasicCredentials.create(...), which validates
        // non-blank access/secret keys itself and throws. This is real, observable behavior — a blank
        // `invoice.s3.access-key`/`invoice.s3.secret-key` value crashes the producer instead of
        // silently falling back to the RustFS dev default the way an unset property would. Neither
        // producer's KDoc promises blank-string defaulting, so this isn't treated as a bug to fix here,
        // but the difference from the "absent" case is worth locking in with a test.
        val producer = DocumentPresignProducer(
            Optional.of("s3"),
            Optional.of("   "),
            Optional.of("   "),
            Optional.of("   "),
            Optional.of("   ")
        )

        assertThrows(NullPointerException::class.java) {
            producer.produce()
        }
    }
}
