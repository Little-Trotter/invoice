package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.dto.PendingVatIdVerification
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.masterdata.RecipientAddressInput
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.shared.tax.VatIdVerificationStatus
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * PO report 04.09.2026 (narrowed): "Änderung von Kundendaten wird nicht angenommen" — it is
 * specifically an EDIT of an EXISTING recipient that fails to persist, not the create path (see
 * [RecipientViewVatIdVerificationKaribuTest]'s reproduction cases for that one).
 *
 * [de.littletrotter.ui.view.AbstractCrudView.saveCurrentForm] runs separate top-level calls on the
 * injected input ports — the detail read that fills the form, then the save of the edited
 * [RecipientDraft] — from a plain Vaadin UI-thread call, never itself wrapped in a transaction.
 * Each `@Transactional` port method therefore opens and commits its OWN transaction. A
 * single-transaction test would hide any defect in that load-edit-save round trip entirely (the
 * persistence context would just hand back the very instance already in memory) — same reasoning
 * as [de.littletrotter.ui.view.invoice.InvoiceDetailWriteBackIT]'s KDoc. Each phase below is
 * therefore its own [QuarkusTransaction.requiringNew] call, matching production.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class RecipientUpdateSaveIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var manageRecipient: ManageRecipientUseCase

    @Inject
    lateinit var recipientQuery: RecipientQueryPort

    private fun billingAddress(street: String) = RecipientAddressInput(
        billingStreet = street,
        billingPostalCode = "10115",
        billingCity = "Berlin",
        billingCountry = "DE",
    )

    private fun freshRecipient(customerNumber: String, street: String) = RecipientDraft.blank().copy(
        customerNumber = customerNumber,
        name = "Bestandskunde GmbH",
        email = "kunde@example.org",
        vatId = "DE136695976",
        customerType = CustomerType.BUSINESS,
        addresses = billingAddress(street),
    )

    // Mirrors AbstractCrudView.saveCurrentForm's load in its own transaction.
    private fun loadDetail(id: Long): RecipientDetailDto = QuarkusTransaction.requiringNew().call<RecipientDetailDto> {
        recipientQuery.findRecipientDetail(id)!!
    }

    /**
     * The exact PO scenario: open an existing recipient, change a plain field (name), save. The
     * change must actually reach the database — reloading in a THIRD, independent transaction is
     * the only way to prove that rather than reading back an in-memory copy.
     */
    @Test
    fun `editing an existing recipient's name persists across separate transactions`() {
        val id = QuarkusTransaction.requiringNew().call<Long> {
            manageRecipient.saveRecipientWithAddresses(freshRecipient("KD-UPD-001", "Musterstraße 1")).id
        }

        // Mirrors RecipientView writing the edited UI field into the draft; the SAME billing
        // values the form always carries are re-submitted on every save regardless of changes.
        val edited = RecipientDraft.from(loadDetail(id)).copy(name = "Bestandskunde AG")
        QuarkusTransaction.requiringNew().run { manageRecipient.saveRecipientWithAddresses(edited) }

        val reloadedName = QuarkusTransaction.requiringNew().call<String> {
            em.find(Recipient::class.java, id).name
        }
        assertEquals("Bestandskunde AG", reloadedName, "the name change made in the dialog must reach the database")
    }

    /**
     * The other half of the PO's combined report: an existing recipient whose VAT id VIES marked
     * FAILED must still accept an unrelated field edit — the persisted `vatIdVerificationStatus`
     * (and its badge) must survive the edit unchanged (unchanged vatId, no fresh verification this
     * session → the stored status wins).
     */
    @Test
    fun `editing an existing recipient whose VAT id is FAILED still saves, keeping the FAILED status`() {
        val verifiedAt = OffsetDateTime.now().withNano(0)
        val id = QuarkusTransaction.requiringNew().call<Long> {
            val draft = freshRecipient("KD-UPD-002", "Musterstraße 2").copy(
                pendingVatIdVerification = PendingVatIdVerification("DE136695976", VatIdVerificationStatus.FAILED, verifiedAt),
            )
            manageRecipient.saveRecipientWithAddresses(draft).id
        }

        val edited = RecipientDraft.from(loadDetail(id)).copy(phone = "+49 30 987654")
        QuarkusTransaction.requiringNew().run { manageRecipient.saveRecipientWithAddresses(edited) }

        val reloaded = QuarkusTransaction.requiringNew().call<Recipient> {
            em.find(Recipient::class.java, id)
        }
        assertEquals("+49 30 987654", reloaded.phone, "the edited field must have reached the database")
        assertEquals(
            VatIdVerificationStatus.FAILED,
            reloaded.vatIdVerificationStatus,
            "a FAILED VIES status must survive an unrelated edit, never silently reset",
        )
    }

    private fun committedVersion(id: Long): Long? = QuarkusTransaction.requiringNew().call<Long?> {
        em.find(Recipient::class.java, id).version
    }

    /**
     * [RecipientView.persistForm] keeps the DTO the save hands back (`currentRecipientDetail =
     * saved`) instead of reloading, so that DTO must carry the version the commit left in the
     * row; the save path forces a version increment at commit time, after the DTO is mapped. Both
     * saves run as the UI runs them: a plain call on the port, no surrounding transaction.
     */
    @Test
    fun `the DTO a save hands back carries the committed version, so saving the same form again succeeds`() {
        val saved = manageRecipient.saveRecipientWithAddresses(freshRecipient("KD-UPD-003", "Musterstraße 3"))
        assertEquals(committedVersion(saved.id), saved.version, "the version handed to the view must be the committed one")

        val again = manageRecipient.saveRecipientWithAddresses(RecipientDraft.from(saved).copy(name = "Bestandskunde KG"))
        assertEquals(committedVersion(saved.id), again.version, "and the same holds after an edit of an existing recipient")
        assertEquals("Bestandskunde KG", again.name)
    }
}
