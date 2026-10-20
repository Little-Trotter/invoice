package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.exception.DefaultPaymentTermAlreadySetException
import de.littletrotter.domain.exception.DuplicateCustomerNumberException
import de.littletrotter.domain.exception.DuplicateInvoicePrefixException
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.reference.LocalizedTextPurpose
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import de.littletrotter.domain.value.CustomerNumber

/**
 * ST-B4 — proves each of the three named unique-constraint violations
 * (`uq_sender_profile_invoice_prefix`/`uq_recipient_customer_number`/
 * `uq_payment_term_single_default`, `V1__baseline_schema.sql`) surfaces through
 * [BasePanacheRepository.saveEntityFlushingOrThrow] as the matching typed domain exception, not a
 * raw `org.hibernate.exception.ConstraintViolationException`. Real Postgres (Testcontainers via
 * [PostgresTestResource]): a functional (`UPPER(...)`) / partial (`WHERE is_default = TRUE`)
 * unique index is DB behavior no mock can stand in for.
 *
 * Each test is `@Transactional` (mirrors [PartyIdentifierMigrationIT]'s pattern), so both saves in
 * a test run inside one already-active transaction — the same shape `saveEntityFlushingOrThrow`
 * itself uses (it deliberately flushes in the *caller's* ambient transaction, not a `REQUIRES_NEW`
 * one; see that method's own KDoc for why a `REQUIRES_NEW` attempt here originally broke a
 * different, unrelated production flow instead).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class UniqueConstraintDomainExceptionIT {

    @Inject
    lateinit var senderProfileRepository: SenderProfileRepository

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var paymentTermRepository: PaymentTermRepository

    companion object {
        // Every constraint under test is table-wide; each test brings its own unique value.
        private val counter = AtomicInteger(0)
    }

    private fun uniqueSuffix(): String = "UQ${counter.incrementAndGet()}"

    @Test
    @Transactional
    fun `a second sender profile with the same invoice prefix throws DuplicateInvoicePrefixException`() {
        val prefix = uniqueSuffix().take(10)
        senderProfileRepository.save(aSenderProfile { name = "Sender A $prefix"; invoicePrefix = prefix })

        assertThrows(DuplicateInvoicePrefixException::class.java) {
            senderProfileRepository.save(aSenderProfile { name = "Sender B $prefix"; invoicePrefix = prefix })
        }
    }

    @Test
    @Transactional
    fun `a second recipient with the same customer number in a different case throws DuplicateCustomerNumberException`() {
        val number = "KD-${uniqueSuffix()}"
        recipientRepository.save(aRecipient { customerNumber = CustomerNumber(number); name = "Recipient A" })

        assertThrows(DuplicateCustomerNumberException::class.java) {
            // uq_recipient_customer_number is on UPPER(customer_number) — a different case still collides.
            recipientRepository.save(aRecipient { customerNumber = CustomerNumber(number.lowercase()); name = "Recipient B" })
        }
    }

    private fun newPaymentTerm(isDefault: Boolean) = aPaymentTerm {
        nameGroup = LocalizedTextGroup().edit { purpose = LocalizedTextPurpose.PAYMENT_TERM_NAME }
        this.isDefault = isDefault
    }

    @Test
    @Transactional
    fun `a second default payment term throws DefaultPaymentTermAlreadySetException`() {
        // V2__baseline_seed.sql already seeds one default payment term ("14 Tage netto") — no
        // need (and no room, uq_payment_term_single_default allows exactly one) to create a first
        // one of our own; PanachePaymentTermRepositoryIT's own KDoc documents this same fixture
        // constraint for the identical reason.
        assertThrows(DefaultPaymentTermAlreadySetException::class.java) {
            paymentTermRepository.save(newPaymentTerm(isDefault = true))
        }
    }

    @Test
    @Transactional
    fun `two non-default payment terms do not collide`() {
        paymentTermRepository.save(newPaymentTerm(isDefault = false))
        paymentTermRepository.save(newPaymentTerm(isDefault = false))
    }
}
