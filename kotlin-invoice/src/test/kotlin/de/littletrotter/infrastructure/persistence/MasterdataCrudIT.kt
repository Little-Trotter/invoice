package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.shared.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import de.littletrotter.domain.value.CustomerNumber

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class MasterdataCrudIT {

    @Inject
    lateinit var em: EntityManager

    @Test
    @Transactional
    fun `SenderProfile - insert update delete`() {
        val sp = SenderProfile().apply {
            name = "Musterfirma GmbH"
            invoicePrefix = "MF-"
            registerCourt = ""
        }
        em.persist(sp)
        em.flush()

        assertNotNull(sp.id)
        assertEquals(0L, sp.version)

        sp.name = "Musterfirma GmbH (renamed)"
        em.merge(sp)
        em.flush()
        em.clear()

        val reloaded = em.find(SenderProfile::class.java, sp.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals("Musterfirma GmbH (renamed)", reloaded.name)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(SenderProfile::class.java, sp.id))
    }

    @Test
    @Transactional
    fun `Recipient - insert update delete`() {
        val r = Recipient().apply {
            customerNumber = CustomerNumber("KD-0001")
            name = "Acme Corp"
        }
        em.persist(r)
        em.flush()

        assertNotNull(r.id)
        assertEquals(0L, r.version)

        r.name = "Acme Corp (updated)"
        em.merge(r)
        em.flush()
        em.clear()

        val reloaded = em.find(Recipient::class.java, r.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals("Acme Corp (updated)", reloaded.name)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(Recipient::class.java, r.id))
    }

    @Test
    @Transactional
    fun `Address for Recipient - insert update delete`() {
        val recipient = Recipient().apply { customerNumber = CustomerNumber("KD-ADDR-R"); name = "Addr Recipient" }
        em.persist(recipient)
        em.flush()

        val addr = Address.forRecipient(recipient, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin")
        em.persist(addr)
        em.flush()

        assertNotNull(addr.id)
        assertEquals(0L, addr.version)

        addr.city = "Hamburg"
        em.merge(addr)
        em.flush()
        em.clear()

        val reloaded = em.find(Address::class.java, addr.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals("Hamburg", reloaded.city)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(Address::class.java, addr.id))
    }

    @Test
    @Transactional
    fun `Address for SenderProfile - insert update delete`() {
        val sp = SenderProfile().apply { name = "SP for Addr"; invoicePrefix = "SP-"; registerCourt = "" }
        em.persist(sp)
        em.flush()

        val addr = Address.forSender(sp, Address.AddressType.BILLING, "Musterweg 5", "80331", "München")
        em.persist(addr)
        em.flush()

        assertNotNull(addr.id)
        assertEquals(0L, addr.version)

        addr.street = "Musterweg 99"
        em.merge(addr)
        em.flush()
        em.clear()

        val reloaded = em.find(Address::class.java, addr.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals("Musterweg 99", reloaded.street)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(Address::class.java, addr.id))
    }

    @Test
    @Transactional
    fun `PaymentTerm - insert update delete`() {
        val pt = PaymentTerm().apply {
            setName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "30 Tage netto")
            dueInDays = 30
        }
        em.persist(pt)
        em.flush()

        assertNotNull(pt.id)
        assertEquals(0L, pt.version)

        pt.dueInDays = 14
        em.merge(pt)
        em.flush()
        em.clear()

        val reloaded = em.find(PaymentTerm::class.java, pt.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals(14, reloaded.dueInDays)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(PaymentTerm::class.java, pt.id))
    }

    @Test
    @Transactional
    fun `Article - insert update delete`() {
        val article = Article().apply {
            name = "Softwareentwicklung"
            defaultUnitPriceNet = Money.of("95.00")
        }
        em.persist(article)
        em.flush()

        assertNotNull(article.id)
        assertEquals(0L, article.version)

        article.defaultUnitPriceNet = Money.of("100.00")
        em.merge(article)
        em.flush()
        em.clear()

        val reloaded = em.find(Article::class.java, article.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals(Money.of("100.00"), reloaded.defaultUnitPriceNet)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(Article::class.java, article.id))
    }

    @Test
    @Transactional
    fun `Receipt - insert update delete`() {
        val receipt = Receipt().apply {
            fileName = "beleg-001.pdf"
            objectKey = "beleg-pool/${UUID.randomUUID()}.pdf"
            description = "Test-Beleg"
            sizeBytes = 12_345L
            // checksums are globally unique (uq_receipt_sha256_checksum, V002) —
            // fixtures must not share a literal value across test classes.
            sha256Checksum = UUID.randomUUID().toString().replace("-", "").repeat(2)
            uploadedAt = OffsetDateTime.now()
            documentDate = LocalDate.of(2026, 3, 14)
        }
        em.persist(receipt)
        em.flush()

        assertNotNull(receipt.id)
        assertEquals(0L, receipt.version)

        receipt.fileName = "beleg-001-renamed.pdf"
        em.merge(receipt)
        em.flush()
        em.clear()

        val reloaded = em.find(Receipt::class.java, receipt.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals("beleg-001-renamed.pdf", reloaded.fileName)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(Receipt::class.java, receipt.id))
    }
}
