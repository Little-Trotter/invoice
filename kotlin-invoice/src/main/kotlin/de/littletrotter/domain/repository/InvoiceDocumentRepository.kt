package de.littletrotter.domain.repository

import de.littletrotter.domain.invoice.InvoiceDocument

interface InvoiceDocumentRepository {
    fun findByInvoiceId(invoiceId: Long, pageIndex: Int = 0, pageSize: Int = 60): List<InvoiceDocument>
    fun findById(id: Long): InvoiceDocument?
    fun save(doc: InvoiceDocument): InvoiceDocument

    /** Looks up the archived-artifact row by its object store key, the identity the
     *  download/verify path (which only ever has an `objectKey`, never an [InvoiceDocument] id)
     *  actually has on hand. `null` when no row was ever persisted for this key — e.g. a legacy
     *  job that predates this table, or an object this table never tracks (a receipt). */
    fun findByObjectKey(objectKey: String): InvoiceDocument?
}
