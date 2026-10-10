package de.littletrotter.domain.invoice

import de.littletrotter.shared.b2b.codes.B2bCodeList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvoiceTypeCodeTest {

    @Test
    fun `fromCode returns matching value`() {
        val code = InvoiceTypeCode.fromCode("380")
        assertEquals(InvoiceTypeCode.COMMERCIAL_INVOICE, code)
    }

    @Test
    fun `fromCode throws on unknown code`() {
        assertThrows(NoSuchElementException::class.java) { InvoiceTypeCode.fromCode("999") }
    }

    /** Audit target: InvoiceTypeCode ("380"/"381") vs the UNTDID 1001 document-type
     *  list — a codedb update dropping either code would be a silent export-breaking regression. */
    @Test
    fun `every code is a member of the official UNTDID 1001 document-type list`() {
        InvoiceTypeCode.entries.forEach {
            assertTrue(
                B2bCodeList.DOCUMENT_TYPE_1001.contains(it.code),
                "${it.name}.code '${it.code}' is not a member of B2bCodeList.DOCUMENT_TYPE_1001",
            )
        }
    }
}
