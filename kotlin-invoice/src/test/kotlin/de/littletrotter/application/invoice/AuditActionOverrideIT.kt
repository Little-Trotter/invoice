package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.compliance.ChainVerificationService
import de.littletrotter.application.shared.AuditActionOverride
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [AuditActionOverride] against a real Postgres: the capture trigger must see the override and its context. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AuditActionOverrideIT {

    @ApplicationScoped
    open class Probe @Inject constructor(
        private val em: EntityManager,
        private val override: AuditActionOverride,
    ) {
        @Transactional
        @SetsAuditGuc(action = InvoiceAuditAction.DOCUMENT_GENERATED)
        open fun run(invoiceId: Long) {
            override.withAction(
                InvoiceAuditAction.STORNO,
                mapOf("reason" to "ORIGINAL_MISSING", "hash_old" to "ab"),
            ) { touch(invoiceId, "inside") }
            touch(invoiceId, "after")
        }

        private fun touch(invoiceId: Long, note: String) {
            em.createNativeQuery("UPDATE invoice SET free_text = :n WHERE id = :id")
                .setParameter("n", note)
                .setParameter("id", invoiceId)
                .executeUpdate()
        }
    }

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var probe: Probe

    @Inject
    lateinit var chainVerificationService: ChainVerificationService

    private fun persistInvoice(): Long = QuarkusTransaction.requiringNew().call<Long> {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "AAO1"
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-AAO-1")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        em.persist(sender)
        em.persist(recipient)
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("AAO1-2026-10-01-00001")
            invoiceDate = LocalDate.now()
            senderProfile = sender
            this.recipient = recipient
        }
        em.persist(invoice)
        em.flush()
        invoice.id!!
    }

    @Test
    fun `the override journals its own action with context and the outer action returns afterwards`() {
        val invoiceId = persistInvoice()

        probe.run(invoiceId)

        val rows = QuarkusTransaction.requiringNew().call<List<Array<*>>> {
            em.createNativeQuery(
                "SELECT action, detail->'_context'->>'reason', detail->'_context'->>'hash_old' " +
                    "FROM invoice_audit_journal WHERE invoice_id = :id ORDER BY seq DESC LIMIT 2",
            ).setParameter("id", invoiceId).resultList.map { it as Array<*> }
        }
        val after = rows[0]
        val inside = rows[1]
        assertEquals(InvoiceAuditAction.DOCUMENT_GENERATED.ordinal, (after[0] as Number).toInt())
        assertNull(after[1])
        assertEquals(InvoiceAuditAction.STORNO.ordinal, (inside[0] as Number).toInt())
        assertEquals("ORIGINAL_MISSING", inside[1])
        assertEquals("ab", inside[2])
        assertFalse(chainVerificationService.verifyAllChains().hasBreak)
    }
}
