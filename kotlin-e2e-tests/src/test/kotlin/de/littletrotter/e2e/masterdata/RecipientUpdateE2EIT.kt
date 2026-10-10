package de.littletrotter.e2e.masterdata

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.PendingVatIdVerification
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.masterdata.RecipientAddressInput
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * PO report 04.09.2026, narrowed: "Änderung von Kundendaten wird nicht angenommen" — editing an
 * EXISTING recipient through the same input ports [de.littletrotter.ui.view.masterdata.RecipientView]
 * uses ([RecipientQueryPort.findRecipientDetail] to fill the form, then
 * [ManageRecipientUseCase.saveRecipientWithAddresses] with the edited [RecipientDraft], exactly
 * what `AbstractCrudView.saveCurrentForm` does for an edit). Each phase runs in its own
 * transaction on purpose — see [de.littletrotter.ui.view.invoice.InvoiceDetailWriteBackIT]'s KDoc
 * (kotlin-invoice module) for why a single shared transaction would hide the very defect this
 * pins: only a fresh reload in a THIRD transaction proves the edit actually reached the database
 * rather than just an in-memory copy.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class RecipientUpdateE2EIT {

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var manageRecipient: ManageRecipientUseCase

    @Inject
    lateinit var recipientQuery: RecipientQueryPort

    private val createdRecipientIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        createdRecipientIds.forEach { id ->
            QuarkusTransaction.requiringNew().run { recipientRepository.delete(id) }
        }
    }

    private val berlin = RecipientAddressInput(
        billingStreet = "Musterstraße 1",
        billingPostalCode = "10115",
        billingCity = "Berlin",
        billingCountry = "DE",
    )

    private fun createRecipient(customerNumber: String, vatIdStatus: VatIdVerificationStatus? = null): Long {
        val draft = RecipientDraft.blank().copy(
            customerNumber = customerNumber,
            name = "E2E Bestandskunde GmbH",
            email = "kunde@example.org",
            vatId = "DE136695976",
            customerType = CustomerType.BUSINESS,
            addresses = berlin,
            pendingVatIdVerification = vatIdStatus?.let {
                PendingVatIdVerification("DE136695976", it, OffsetDateTime.now().withNano(0))
            },
        )
        val id = QuarkusTransaction.requiringNew().call<Long> {
            manageRecipient.saveRecipientWithAddresses(draft).id
        }
        createdRecipientIds += id
        return id
    }

    private fun load(id: Long): RecipientDetailDto = QuarkusTransaction.requiringNew().call<RecipientDetailDto> {
        recipientQuery.findRecipientDetail(id)!!
    }

    private fun save(draft: RecipientDraft) {
        QuarkusTransaction.requiringNew().run { manageRecipient.saveRecipientWithAddresses(draft) }
    }

    /** Happy path: load, edit a plain field, save — the change must reach the database. */
    @Test
    fun `editing an existing recipient's name persists`() {
        val id = createRecipient("KD-E2E-UPD-001")

        save(RecipientDraft.from(load(id)).copy(name = "E2E Bestandskunde AG", addresses = berlin))

        assertEquals("E2E Bestandskunde AG", load(id).name)
    }

    /** Border case: the billing address row itself must be updated by the same edit-and-save
     *  round trip, not just the recipient row. */
    @Test
    fun `editing an existing recipient's billing address updates the address row`() {
        val id = createRecipient("KD-E2E-UPD-002")

        save(
            RecipientDraft.from(load(id)).copy(
                addresses = RecipientAddressInput(
                    billingStreet = "Neue Straße 42",
                    billingPostalCode = "20095",
                    billingCity = "Hamburg",
                    billingCountry = "DE",
                ),
            ),
        )

        val billing = QuarkusTransaction.requiringNew().call<AddressDto?> {
            recipientQuery.findBillingAddress(id)
        }
        assertEquals("Neue Straße 42", billing?.street)
        assertEquals("20095", billing?.postalCode)
        assertEquals("Hamburg", billing?.city)
    }

    /** Border case, PO's combined report: a recipient whose VAT id VIES already marked FAILED must
     *  still accept an unrelated edit, and the FAILED status/badge must survive it unchanged. */
    @Test
    fun `editing an existing recipient with a FAILED VAT id status still saves, keeping FAILED`() {
        val id = createRecipient("KD-E2E-UPD-003", vatIdStatus = VatIdVerificationStatus.FAILED)

        save(RecipientDraft.from(load(id)).copy(phone = "+49 40 1234567", addresses = berlin))

        val reloaded = load(id)
        assertEquals("+49 40 1234567", reloaded.phone)
        assertEquals(VatIdVerificationStatus.FAILED, reloaded.vatIdVerificationStatus)
    }

    /** Border case: two edits of the same recipient in direct succession — the second save must
     *  not be rejected by a stale optimistic-lock version carried over from the first. */
    @Test
    fun `two successive edits of the same recipient both persist`() {
        val id = createRecipient("KD-E2E-UPD-004")

        save(RecipientDraft.from(load(id)).copy(name = "Erste Änderung GmbH", addresses = berlin))
        save(RecipientDraft.from(load(id)).copy(name = "Zweite Änderung GmbH", addresses = berlin))

        assertEquals("Zweite Änderung GmbH", load(id).name)
    }
}
