package de.littletrotter.application.invoice

import org.mockito.kotlin.argThat
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import de.littletrotter.shared.DocumentStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.security.MessageDigest
import java.util.Locale

/**
 * [DocumentChecksumVerifier] against mocked
 * [InvoiceDocumentRepository]/[MessageCenterService]/[DocumentStore] collaborators, same shape as
 * [de.littletrotter.application.message.MessageCenterEventHandlerTest].
 */
class DocumentChecksumVerifierTest {

    private val invoiceDocumentRepository = mock<InvoiceDocumentRepository>()
    private val messageCenterService = mock<MessageCenterService>()
    private val documentStore = mock<DocumentStore>()
    private val verifier = DocumentChecksumVerifier(invoiceDocumentRepository, messageCenterService)

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    @Test
    fun `RS-10 - returns the fetched bytes unchanged when no row exists for the object key`() {
        val bytes = "pdf-bytes".toByteArray()
        whenever(documentStore.get("invoices/1.pdf")).thenReturn(bytes)
        whenever(invoiceDocumentRepository.findByObjectKey("invoices/1.pdf")).thenReturn(null)

        val result = verifier.verifiedGet(documentStore, "invoices/1.pdf", 1L, "RE-2026-0001")

        assertArrayEquals(bytes, result)
        verify(messageCenterService, never()).record(any(), anyOrNull())
    }

    @Test
    fun `RS-10 - returns the fetched bytes unchanged when the stored row has no checksum yet`() {
        val bytes = "pdf-bytes".toByteArray()
        whenever(documentStore.get("invoices/1.pdf")).thenReturn(bytes)
        whenever(invoiceDocumentRepository.findByObjectKey("invoices/1.pdf")).thenReturn(InvoiceDocument().apply { sha256Checksum = null })

        val result = verifier.verifiedGet(documentStore, "invoices/1.pdf", 1L, "RE-2026-0001")

        assertArrayEquals(bytes, result)
    }

    @Test
    fun `RS-10 - returns the fetched bytes unchanged when the checksum matches`() {
        val bytes = "pdf-bytes".toByteArray()
        whenever(documentStore.get("invoices/1.pdf")).thenReturn(bytes)
        whenever(invoiceDocumentRepository.findByObjectKey("invoices/1.pdf"))
            .thenReturn(InvoiceDocument().apply { sha256Checksum = sha256Hex(bytes) })

        val result = verifier.verifiedGet(documentStore, "invoices/1.pdf", 1L, "RE-2026-0001")

        assertArrayEquals(bytes, result)
        verify(messageCenterService, never()).record(any(), anyOrNull())
    }

    @Test
    fun `RS-10 - a checksum mismatch throws DocumentChecksumMismatchException and records an ERROR system message`() {
        val bytes = "tampered-bytes".toByteArray()
        whenever(documentStore.get("invoices/1.pdf")).thenReturn(bytes)
        whenever(invoiceDocumentRepository.findByObjectKey("invoices/1.pdf"))
            .thenReturn(InvoiceDocument().apply { sha256Checksum = "0".repeat(64) })

        val ex = assertThrows(DocumentChecksumMismatchException::class.java) {
            verifier.verifiedGet(documentStore, "invoices/1.pdf", 1L, "RE-2026-0001")
        }
        assertEquals("invoices/1.pdf", ex.objectKey)

        verify(messageCenterService).record(
            argThat {
                severity == SystemMessageSeverity.ERROR &&
                source == SystemMessageSource.EXPORT &&
                messageKey == "message.center.document.checksumMismatch" &&
                refType == "INVOICE" &&
                refId == "1"
            },
            recipientUserId = anyOrNull(),
        )
    }

    @Test
    fun `boundary - returns the fetched bytes unchanged when the stored checksum is blank rather than null`() {
        val bytes = "pdf-bytes".toByteArray()
        whenever(documentStore.get("invoices/1.pdf")).thenReturn(bytes)
        whenever(invoiceDocumentRepository.findByObjectKey("invoices/1.pdf")).thenReturn(InvoiceDocument().apply { sha256Checksum = "   " })

        val result = verifier.verifiedGet(documentStore, "invoices/1.pdf", 1L, "RE-2026-0001")

        assertArrayEquals(bytes, result)
    }

    @Test
    fun `boundary - a mismatch without an invoice number falls back to the invoice id in the message and refId`() {
        val bytes = "tampered-bytes".toByteArray()
        whenever(documentStore.get("invoices/1.pdf")).thenReturn(bytes)
        whenever(invoiceDocumentRepository.findByObjectKey("invoices/1.pdf"))
            .thenReturn(InvoiceDocument().apply { sha256Checksum = "0".repeat(64) })

        assertThrows(DocumentChecksumMismatchException::class.java) {
            verifier.verifiedGet(documentStore, "invoices/1.pdf", 42L, null)
        }

        verify(messageCenterService).record(
            argThat {
                severity == SystemMessageSeverity.ERROR &&
                source == SystemMessageSource.EXPORT &&
                messageKey == "message.center.document.checksumMismatch" &&
                refType == "INVOICE" &&
                refId == "42"
            },
            recipientUserId = anyOrNull(),
        )
    }

    @Test
    fun `boundary - a mismatch without an invoice number or id falls back to a literal question mark and a null refId`() {
        val bytes = "tampered-bytes".toByteArray()
        whenever(documentStore.get("invoices/1.pdf")).thenReturn(bytes)
        whenever(invoiceDocumentRepository.findByObjectKey("invoices/1.pdf"))
            .thenReturn(InvoiceDocument().apply { sha256Checksum = "0".repeat(64) })

        assertThrows(DocumentChecksumMismatchException::class.java) {
            verifier.verifiedGet(documentStore, "invoices/1.pdf", null, null)
        }

        verify(messageCenterService).record(
            argThat {
                severity == SystemMessageSeverity.ERROR &&
                source == SystemMessageSource.EXPORT &&
                messageKey == "message.center.document.checksumMismatch" &&
                refType == "INVOICE" &&
                refId == null
            },
            recipientUserId = anyOrNull(),
        )
    }

    @Test
    fun `RS-10 - a differently-cased but otherwise equal stored hex still matches (case-insensitive compare)`() {
        val bytes = "pdf-bytes".toByteArray()
        val hex = sha256Hex(bytes)
        whenever(documentStore.get("invoices/1.pdf")).thenReturn(bytes)
        whenever(invoiceDocumentRepository.findByObjectKey("invoices/1.pdf"))
            .thenReturn(InvoiceDocument().apply { sha256Checksum = hex.uppercase() })

        val result = verifier.verifiedGet(documentStore, "invoices/1.pdf", 1L, "RE-2026-0001")

        assertArrayEquals(bytes, result)
    }
}
