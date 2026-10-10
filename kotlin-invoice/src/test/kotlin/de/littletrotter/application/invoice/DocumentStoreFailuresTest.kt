package de.littletrotter.application.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.exception.SdkClientException
import java.io.IOException

class DocumentStoreFailuresTest {

    @Test
    fun `happy path - the block result is returned and onFailure is not called`() {
        val result = runDocumentStoreOp(onFailure = { error("must not be called") }) { "stored" }

        assertEquals("stored", result)
    }

    @Test
    fun `boundary - an IOException from the filesystem backend reaches onFailure`() {
        val failure = IOException("disk full")

        val seen = runDocumentStoreOp(onFailure = { it }) { throw failure }

        assertSame(failure, seen)
    }

    @Test
    fun `boundary - a NoSuchElementException for a missing local key reaches onFailure`() {
        val failure = NoSuchElementException("missing key")

        val seen = runDocumentStoreOp(onFailure = { it }) { throw failure }

        assertSame(failure, seen)
    }

    @Test
    fun `boundary - an SdkException from the S3 backend reaches onFailure`() {
        val failure = SdkClientException.create("connection refused")

        val seen = runDocumentStoreOp(onFailure = { it }) { throw failure }

        assertSame(failure, seen)
    }

    @Test
    fun `boundary - an unrelated programming error is not swallowed`() {
        assertThrows<IllegalStateException> {
            runDocumentStoreOp<String>(onFailure = { "swallowed" }) { throw IllegalStateException("bug") }
        }
    }
}
