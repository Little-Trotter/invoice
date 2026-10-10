package de.littletrotter.e2e.masterdata

import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ListSortingE2EIT {
    @Inject lateinit var em: EntityManager
    @Inject lateinit var senders: SellerProfileService
    @Inject lateinit var receipts: ReceiptPoolService

    @Test
    @TestTransaction
    fun `sender sort runs before paging and respects billing address, filter and missing address`() {
        val tag = "sort-${UUID.randomUUID()}"
        fun sender(suffix: String, city: String?, prefix: String): SenderProfile = SenderProfile().apply {
            name = "$tag $suffix"
            invoicePrefix = prefix
            registerCourt = ""
            if (city != null) addAddress(Address.forSender(this, Address.AddressType.BILLING, "Street", "12345", city, Country.DE))
            addAddress(Address.forSender(this, Address.AddressType.DELIVERY, "Other", "12345", "ZZZZ", Country.DE))
            em.persist(this)
        }
        val prefix = UUID.randomUUID().toString().take(6)
        val zulu = sender("Zulu", "Berlin", "${prefix}3")
        val alpha = sender("alpha", "Zurich", "${prefix}1")
        val missing = sender("Middle", null, "${prefix}2")
        em.flush()
        for ((field, ascending) in listOf(
            "name" to listOf(alpha, missing, zulu),
            "invoicePrefix" to listOf(alpha, missing, zulu),
            "cityCountry" to listOf(zulu, alpha, missing),
        )) {
            val descending = if (field == "cityCountry") listOf(alpha, zulu, missing) else ascending.reversed()
            for ((direction, expected) in listOf(SortDirection.ASC to ascending, SortDirection.DESC to descending)) {
                val pages = (0..2).flatMap { senders.findAllSorted(it, 1, tag, GridSort(field, direction)) }
                assertEquals(expected.map { it.id }, pages.map { it.id }, "$field $direction")
                assertTrue(senders.findAllSorted(3, 1, tag, GridSort(field, direction)).isEmpty())
            }
        }
        assertEquals(3, senders.count(tag))
        assertTrue(senders.findAllSorted(0, 50, "$tag absent", GridSort("name", SortDirection.ASC)).isEmpty())
    }

    @Test
    @TestTransaction
    fun `receipt sorting uses typed dates and sizes and has stable ties across pages`() {
        val tag = "sort-${UUID.randomUUID()}"
        fun receipt(suffix: String, descriptionSuffix: String, size: Long, date: String): Receipt = Receipt().apply {
            fileName = "$tag $suffix.pdf"
            description = "$tag $descriptionSuffix"
            objectKey = UUID.randomUUID().toString()
            sizeBytes = size
            documentDate = LocalDate.parse(date)
            uploadedAt = OffsetDateTime.parse("${date}T12:00:00Z")
            em.persist(this)
        }
        val zulu = receipt("Zulu", "alpha", 2, "2025-12-31")
        val alpha = receipt("alpha", "Zulu", 10, "2026-01-01")
        val tie = receipt("alpha", "Zulu", 10, "2026-01-01")
        em.flush()
        for ((field, ascending, descending) in listOf(
            Triple("fileName", listOf(alpha, tie, zulu), listOf(zulu, alpha, tie)),
            Triple("description", listOf(zulu, alpha, tie), listOf(alpha, tie, zulu)),
            Triple("sizeBytes", listOf(zulu, alpha, tie), listOf(alpha, tie, zulu)),
            Triple("documentDate", listOf(zulu, alpha, tie), listOf(alpha, tie, zulu)),
            Triple("uploadedAt", listOf(zulu, alpha, tie), listOf(alpha, tie, zulu)),
        )) {
            for ((direction, expected) in listOf(SortDirection.ASC to ascending, SortDirection.DESC to descending)) {
                val pages = (0..2).flatMap { receipts.findAllSorted(it, 1, tag, GridSort(field, direction)) }
                assertEquals(expected.map { it.id }, pages.map { it.id }, "$field $direction")
                assertTrue(receipts.findAllSorted(3, 1, tag, GridSort(field, direction)).isEmpty())
            }
        }
        assertEquals(3, receipts.countAll(tag))
        assertTrue(receipts.findAllSorted(0, 50, "$tag absent", GridSort("fileName", SortDirection.DESC)).isEmpty())
        assertEquals(listOf(tie.id, alpha.id, zulu.id), receipts.findAll(0, 50, tag).map { it.id })
        assertEquals(receipts.findAll(0, 50, tag).map { it.id }, receipts.findAllSorted(0, 50, tag, GridSort("invalid", SortDirection.ASC)).map { it.id })
    }
}
