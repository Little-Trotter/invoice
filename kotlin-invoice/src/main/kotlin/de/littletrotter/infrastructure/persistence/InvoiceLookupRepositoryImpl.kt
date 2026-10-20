package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.country.Country
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager

@ApplicationScoped
class InvoiceLookupRepositoryImpl @Inject constructor(
    private val em: EntityManager,
) : InvoiceLookupRepository {

    /**
     * IMP-5 — the `WHERE` filter must not use the same alias as `JOIN FETCH`.
     * `JOIN FETCH i.items it WHERE it.id = :itemId` looks like it loads "the invoice of the
     * item", but it actually restricts `items` to just that one matching row — every other line
     * item of the same invoice was left out of the loaded collection. Within the same
     * persistence session, Hibernate does not initialize an already-loaded collection a second
     * time: a second call for a DIFFERENT item of the same invoice saw the same, incomplete
     * collection again and did not find its own item in it — even though it had long existed in
     * the database (`ReceiptAssignmentIT`, where exactly two items of the same invoice
     * are looked up one after another).
     *
     * The subquery decides **which** invoice is loaded without touching the `JOIN FETCH`
     * alias — the collection is therefore always populated completely.
     */
    override fun findInvoiceByItemId(itemId: Long): Invoice? =
        em.createQuery(
            """SELECT DISTINCT i FROM Invoice i JOIN FETCH i.items
               WHERE i.id = (SELECT it.invoice.id FROM InvoiceItem it WHERE it.id = :itemId)""",
            Invoice::class.java
        ).setParameter("itemId", itemId).resultList.firstOrNull()

    /** One query for all ids, same idiom as [PanacheReceiptRepository.findFileNames]. */
    override fun findItemPositions(itemIds: Collection<Long>): Map<Long, String> {
        if (itemIds.isEmpty()) return emptyMap()
        return em.createQuery("SELECT it.id, it.positionId FROM InvoiceItem it WHERE it.id IN :ids")
            .setParameter("ids", itemIds).resultList.associate { row ->
                val arr = row as Array<*>
                (arr[0] as Number).toLong() to (arr[1] as String)
            }
    }

    override fun findByOriginalInvoiceNumber(number: String): List<Invoice> =
        em.createQuery("SELECT i FROM Invoice i WHERE i.originalInvoiceNumber = :n", Invoice::class.java)
            .setParameter("n", InvoiceNumber(number)).resultList

    override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int): List<Invoice> =
        em.createQuery("SELECT i FROM Invoice i WHERE i.recipient.id = :rid ORDER BY i.invoiceDate DESC, i.id DESC", Invoice::class.java)
            .setParameter("rid", recipientId)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int): List<Invoice> =
        em.createQuery("SELECT i FROM Invoice i WHERE i.senderProfile.id = :sid ORDER BY i.invoiceDate DESC, i.id DESC", Invoice::class.java)
            .setParameter("sid", senderProfileId)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    override fun countOpenInvoicesForSellerVatCountry(senderProfileId: Long, country: Country): Long =
        em.createQuery(
            "SELECT COUNT(i) FROM Invoice i WHERE i.senderProfile.id = :sid AND i.sellerVatRegistrationCountry = :country" +
                " AND i.status NOT IN (:sent, :cancelled, :voided)",
            Long::class.javaObjectType,
        ).setParameter("sid", senderProfileId)
            .setParameter("country", country)
            .setParameter("sent", InvoiceStatus.ISSUED)
            .setParameter("cancelled", InvoiceStatus.CANCELLED)
            .setParameter("voided", InvoiceStatus.VOIDED)
            .singleResult

    override fun existsByBillingAddressId(addressId: Long): Boolean =
        em.createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.billingAddress.id = :addressId", Long::class.javaObjectType)
            .setParameter("addressId", addressId)
            .singleResult > 0

    override fun existsByDeliveryAddressId(addressId: Long): Boolean =
        em.createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.deliveryAddress.id = :addressId", Long::class.javaObjectType)
            .setParameter("addressId", addressId)
            .singleResult > 0

    override fun existsByB2bProfileId(b2bProfileId: Long): Boolean =
        em.createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.b2bProfile.id = :id", Long::class.javaObjectType)
            .setParameter("id", b2bProfileId)
            .singleResult > 0
}
