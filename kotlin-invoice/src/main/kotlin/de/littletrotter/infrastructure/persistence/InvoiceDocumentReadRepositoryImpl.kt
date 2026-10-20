package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.invoice.port.outbound.InvoiceDocumentReadRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager

@ApplicationScoped
class InvoiceDocumentReadRepositoryImpl @Inject constructor(
    private val em: EntityManager,
) : InvoiceDocumentReadRepository {

    // object_key has no unique constraint (unlike receipt.object_key), so this takes the
    // most recently generated match; in practice a given key is only ever written once per row.
    override fun findDocumentChecksum(objectKey: String): String? =
        em.createQuery(
            "SELECT d.sha256Checksum FROM InvoiceDocument d WHERE d.objectKey = :objectKey ORDER BY d.generatedAt DESC",
            String::class.java,
        ).setParameter("objectKey", objectKey)
            .setMaxResults(1)
            .resultList
            .firstOrNull()

    override fun findDocumentChecksumById(documentId: Long): String? =
        em.createQuery("SELECT d.sha256Checksum FROM InvoiceDocument d WHERE d.id = :id", String::class.java)
            .setParameter("id", documentId)
            .resultList
            .firstOrNull()
}
