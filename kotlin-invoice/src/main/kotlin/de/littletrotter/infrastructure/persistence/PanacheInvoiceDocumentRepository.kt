package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PanacheInvoiceDocumentRepository : BasePanacheRepository(), InvoiceDocumentRepository {

    override fun findByInvoiceId(invoiceId: Long, pageIndex: Int, pageSize: Int): List<InvoiceDocument> =
        em.createQuery(
            "SELECT d FROM InvoiceDocument d WHERE d.invoice.id = :id ORDER BY d.generatedAt DESC",
            InvoiceDocument::class.java
        ).setParameter("id", invoiceId)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    override fun findById(id: Long): InvoiceDocument? = findByIdInternal(id, InvoiceDocument::class.java)

    // object_key has no unique constraint (unlike receipt.object_key), so this takes the
    // most recently generated match; in practice a given key is only ever written once per row.
    override fun findByObjectKey(objectKey: String): InvoiceDocument? =
        em.createQuery(
            "SELECT d FROM InvoiceDocument d WHERE d.objectKey = :objectKey ORDER BY d.generatedAt DESC",
            InvoiceDocument::class.java,
        ).setParameter("objectKey", objectKey)
            .setMaxResults(1)
            .resultList
            .firstOrNull()

    override fun save(doc: InvoiceDocument): InvoiceDocument = saveEntity(doc) { doc.id }
}
