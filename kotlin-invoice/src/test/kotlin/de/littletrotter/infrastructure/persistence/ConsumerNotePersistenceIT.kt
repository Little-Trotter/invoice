package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNoteOrigin
import de.littletrotter.domain.invoice.InvoiceNoteSnapshot
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.consumerNoteFor
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The private-customer notice against a real Postgres: the sender's text group and switch, and
 * the invoice-owned copy with its provenance columns.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ConsumerNotePersistenceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceReadRepository: InvoiceReadRepository

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    private fun german(): Language = em.find(Language::class.java, 1)
    private fun english(): Language = em.find(Language::class.java, 2)

    private fun persistedSender(prefix: String): SenderProfile {
        val profile = aSenderProfile {
            name = "Testanbieter $prefix"
            invoicePrefix = prefix
        }
        em.persist(profile)
        em.flush()
        return profile
    }

    @Test
    @TestTransaction
    fun `consumer note group, entries and switch persist and survive a reload`() {
        val sender = persistedSender("CN-1")
        sender.changeConsumerNote(german(), "Bitte aufbewahren.")
        sender.changeConsumerNote(english(), "Please keep.")
        sender.edit { consumerNoteEnabled = false }
        em.flush()
        val groupId = sender.consumerNote!!.id!!
        em.clear()

        val reloaded = em.find(SenderProfile::class.java, sender.id)
        assertEquals(groupId, reloaded.consumerNote?.id)
        assertEquals("Bitte aufbewahren.", reloaded.consumerNoteFor("de"))
        assertEquals("Please keep.", reloaded.consumerNoteFor("en"))
        assertFalse(reloaded.consumerNoteEnabled)
        assertEquals(2L, rowCount("SELECT COUNT(*) FROM localized_text WHERE group_id = $groupId"))
        assertNull(reloaded.invoiceNote, "the invoice note keeps its own group")
    }

    @Test
    @TestTransaction
    fun `a new sender defaults to enabled without a group`() {
        val sender = persistedSender("CN-2")
        em.clear()

        val reloaded = em.find(SenderProfile::class.java, sender.id)
        assertTrue(reloaded.consumerNoteEnabled)
        assertNull(reloaded.consumerNote)
    }

    @Test
    @TestTransaction
    fun `changing an entry updates in place and clearing the last entry removes the group row`() {
        val sender = persistedSender("CN-3")
        sender.changeConsumerNote(german(), "Erster")
        em.flush()
        val groupId = sender.consumerNote!!.id!!

        sender.changeConsumerNote(german(), "Zweiter")
        em.flush()
        em.clear()
        assertEquals("Zweiter", em.find(SenderProfile::class.java, sender.id).consumerNoteFor("de"))
        assertEquals(1L, rowCount("SELECT COUNT(*) FROM localized_text WHERE group_id = $groupId"))

        val managed = em.find(SenderProfile::class.java, sender.id)
        managed.changeConsumerNote(german(), "")
        em.flush()
        em.clear()

        assertEquals(0L, rowCount("SELECT COUNT(*) FROM localized_text_group WHERE id = $groupId"))
        assertNull(em.find(SenderProfile::class.java, sender.id).consumerNote)
    }

    @Test
    @TestTransaction
    fun `deleting the sender removes the consumer note group`() {
        val sender = persistedSender("CN-4")
        sender.changeConsumerNote(german(), "Text")
        em.flush()
        val groupId = sender.consumerNote!!.id!!

        em.remove(sender)
        em.flush()

        assertEquals(0L, rowCount("SELECT COUNT(*) FROM localized_text_group WHERE id = $groupId"))
    }

    @Test
    @TestTransaction
    fun `invoice consumer note columns round-trip and reach the detail read model`() {
        val sender = persistedSender("CN-5")
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-CN-5")
            name = "Erika Musterfrau"
        }
        em.persist(recipient)
        val invoice = Invoice().edit {
            invoiceNumber = InvoiceNumber("CN-2026-10-00001")
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 10, 1)
            dueDate = LocalDate.of(2026, 10, 15)
            texts.applyConsumerNote(
                InvoiceNoteSnapshot(
                    value = "Bitte aufbewahren.",
                    origin = InvoiceNoteOrigin.MANUAL,
                    sourceSenderProfileId = sender.id,
                    sourceLanguage = "en",
                    sourceDefaultValue = "Standard",
                    sourceContext = "CONSUMER",
                ),
            )
        }
        em.persist(invoice)
        em.flush()
        em.clear()

        val reloaded = em.find(Invoice::class.java, invoice.id)
        assertEquals(
            InvoiceNoteSnapshot("Bitte aufbewahren.", InvoiceNoteOrigin.MANUAL, sender.id, "en", "Standard", "CONSUMER"),
            reloaded.texts.consumerNoteSnapshot(),
        )
        val dto = invoiceReadRepository.findInvoiceDetailDto(invoice.id!!)
        assertEquals("Bitte aufbewahren.", dto?.consumerNote)
        assertEquals("MANUAL", dto?.consumerNoteOrigin)
    }

    @Test
    @TestTransaction
    fun `a fresh invoice carries the column defaults`() {
        val sender = persistedSender("CN-6")
        val invoice = Invoice().edit {
            invoiceNumber = InvoiceNumber("CN-2026-10-00002")
            senderProfile = sender
            invoiceDate = LocalDate.of(2026, 10, 1)
            dueDate = LocalDate.of(2026, 10, 15)
        }
        em.persist(invoice)
        em.flush()
        em.clear()

        val snapshot = em.find(Invoice::class.java, invoice.id).texts.consumerNoteSnapshot()
        assertNull(snapshot.value)
        assertEquals(InvoiceNoteOrigin.SELLER_DEFAULT, snapshot.origin)
        assertEquals("de", snapshot.sourceLanguage)
        assertEquals("", snapshot.sourceContext)
    }
}
