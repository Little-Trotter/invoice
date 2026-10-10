package de.littletrotter.e2e.masterdata

import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.VatIdVerificationPort
import de.littletrotter.application.masterdata.VatIdVerificationResult
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.ui.view.masterdata.InvoiceCreationDependencies
import de.littletrotter.ui.view.masterdata.RecipientView
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `vat-id-field-ergonomics` end to end: the real [RecipientView] dialog (Karibu-driven, same idiom
 * as [SellerVatRegistrationPreselectE2EIT]/[RecipientUpdateE2EIT] in this package/module), its real
 * [de.littletrotter.ui.component.VatIdField], and the real CDI-injected [ManageRecipientUseCase]
 * against Postgres — proving the auto-prefix/normalisation the field applies on blur actually survives a
 * real save-and-reload round trip, that a foreign prefix on a domestic address is accepted (only an
 * informational helper, never blocked), and that an unrecognised prefix is rejected on the client
 * side before the facade — and therefore Postgres — is ever touched.
 *
 * [VatIdVerificationPort] is stubbed to fail the test if it is ever called: none of these three
 * scenarios click the "VIES prüfen" button, so a call would mean the wrong control fired.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class RecipientVatIdE2EIT {

    @Inject
    lateinit var manageRecipient: ManageRecipientUseCase

    @Inject
    lateinit var recipientQuery: RecipientQueryPort

    @Inject
    lateinit var b2bProfileQueries: B2bProfileQueryPort

    @Inject
    lateinit var b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort

    @Inject
    lateinit var b2bFieldValueQueries: B2bFieldValueQueryPort

    @Inject
    lateinit var manageB2bFieldValue: ManageB2bFieldValueUseCase

    @Inject
    lateinit var senderQuery: SenderQueryPort

    @Inject
    lateinit var createInvoiceUseCase: CreateInvoiceUseCase

    @Inject
    lateinit var recipientRepository: RecipientRepository

    private val neverCalledPort = object : VatIdVerificationPort {
        override fun verify(countryCode: String, vatId: String): VatIdVerificationResult =
            throw AssertionError("VIES must never be called from this suite — got $countryCode/$vatId")
    }

    private val createdRecipientIds = mutableListOf<Long>()

    @BeforeEach
    fun setupVaadin() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardownVaadin() {
        MockVaadin.tearDown()
    }

    @AfterEach
    fun cleanup() {
        createdRecipientIds.forEach { id -> QuarkusTransaction.requiringNew().run { recipientRepository.delete(id) } }
    }

    private fun newView(): RecipientView = RecipientView(
        manageRecipient,
        recipientQuery,
        PartyFormExtensionRegistry(emptyList()),
        neverCalledPort,
        RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue),
        InvoiceCreationDependencies(senderQuery, createInvoiceUseCase),
        time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
    )

    private fun textField(view: RecipientView, name: String): TextField {
        val f = RecipientView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun billingAddressSection(view: RecipientView): AddressFormSection {
        val f = RecipientView::class.java.getDeclaredField("addressFormSection")
        f.isAccessible = true
        return f.get(view) as AddressFormSection
    }

    private fun clearForm(view: RecipientView) {
        val m = RecipientView::class.java.getDeclaredMethod("clearForm")
        m.isAccessible = true
        m.invoke(view)
    }

    private fun saveCurrentForm(view: RecipientView) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        m.isAccessible = true
        m.invoke(view)
    }

    /** Reopens the dialog on an already-persisted recipient — a minimal detached stub carrying
     *  only [id], exactly what [de.littletrotter.ui.view.AbstractCrudView.wireEditorRowActions]'s
     *  grid row would hand in; [RecipientView.formDataFrom] reloads the real entity from the
     *  facade regardless of what else the stub carries. */
    private fun reopenEditor(view: RecipientView, id: Long) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(view, Recipient().also { it.id = id })
    }

    /** Fills every field [RecipientView]'s own `validateForm` requires for a BUSINESS recipient
     *  with a DE billing address, so a failing save can only be attributed to the VAT id itself —
     *  same idiom as [de.littletrotter.ui.view.masterdata.RecipientViewVatIdVerificationKaribuTest
     *  .fillMinimalValidForm] (kotlin-invoice module). */
    private fun fillMinimalValidForm(view: RecipientView, customerNumber: String) {
        clearForm(view)
        textField(view, "nameField").value = "$customerNumber GmbH"
        textField(view, "customerNumberField").value = customerNumber
        billingAddressSection(view).street.value = "Musterstraße 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "emailField").value = "$customerNumber@example.invalid".lowercase()
        // clearForm already defaults the billing country to DE (AddressFormSection.clear()).
    }

    /**
     * Happy path — a VAT id typed as bare digits ("143 454 214") on a DE-billing-address customer
     * is auto-prefixed and normalised by [de.littletrotter.ui.component.VatIdField] on blur
     * (`validateForm` forces that pass via `validateNow()`), the real "Speichern" save path
     * persists exactly `DE143454214`, and reopening the dialog on the reloaded recipient shows the
     * same normalised value — not the raw digits the user typed.
     */
    @Test
    fun `a VAT id typed as bare digits is auto-prefixed, saved and reads back normalised`() {
        val customerNumber = "VATE2E-001"
        val view = newView()
        fillMinimalValidForm(view, customerNumber)
        textField(view, "vatIdField").value = "143 454 214"

        saveCurrentForm(view)

        val saved = QuarkusTransaction.requiringNew().call<Recipient?> {
            recipientRepository.findByCustomerNumber(customerNumber)
        } ?: error("expected the recipient to have been saved")
        createdRecipientIds += saved.id!!
        assertEquals("DE143454214", saved.vatId?.value, "the bare digits must have been auto-prefixed with DE and normalised before saving")

        reopenEditor(view, saved.id!!)
        assertEquals(
            "DE143454214", textField(view, "vatIdField").value,
            "reopening the dialog on the reloaded recipient must show the normalised value, not the raw typed digits",
        )
    }

    /**
     * Border case — an Austrian VAT id on a customer with a DE billing address is legitimate (spec:
     * "German company invoicing from an Austrian registration"). [de.littletrotter.ui.component
     * .VatIdField] shows the mismatch only as an informational helper (`mismatchIsError = false`
     * for [RecipientView]) and never marks the field invalid, so the save must go through
     * unhindered and the value must reach Postgres unchanged.
     */
    @Test
    fun `an AT-prefixed VAT id on a DE billing address saves without error and reads back unchanged`() {
        val customerNumber = "VATE2E-002"
        val view = newView()
        fillMinimalValidForm(view, customerNumber)
        textField(view, "vatIdField").value = "ATU12345678"

        saveCurrentForm(view)

        assertTrue(!textField(view, "vatIdField").isInvalid, "a mismatching-but-recognised prefix must never be a validation error")

        val saved = QuarkusTransaction.requiringNew().call<Recipient?> {
            recipientRepository.findByCustomerNumber(customerNumber)
        } ?: error("expected the recipient to have been saved despite the prefix/address mismatch")
        createdRecipientIds += saved.id!!
        assertEquals("ATU12345678", saved.vatId?.value, "the AT-prefixed id must persist unchanged next to the DE billing address")
    }

    /**
     * Negative case — an unrecognised two-character prefix ("XX") is rejected by
     * [de.littletrotter.ui.component.VatIdField]'s own live validation before the save path ever
     * calls the facade: [AbstractCrudView.saveCurrentForm] returns as soon as
     * `writeValidatedForm`/`validateForm` reports invalid, so nothing reaches Postgres at all.
     */
    @Test
    fun `an unrecognised VAT id prefix is rejected and nothing is persisted`() {
        val customerNumber = "VATE2E-003"
        val view = newView()
        fillMinimalValidForm(view, customerNumber)
        textField(view, "vatIdField").value = "XX123"

        saveCurrentForm(view)

        val vatIdField = textField(view, "vatIdField")
        assertTrue(vatIdField.isInvalid, "an unrecognised prefix must mark the field invalid")
        assertEquals(
            UiTranslations.tr("validation.vatId.unknownPrefix", "XX"),
            vatIdField.errorMessage,
            "the error must name the two characters read as the (unrecognised) prefix",
        )

        val persisted = QuarkusTransaction.requiringNew().call<Recipient?> {
            recipientRepository.findByCustomerNumber(customerNumber)
        }
        assertNull(persisted, "a rejected VAT id must block the save entirely — nothing may reach the database")
    }
}
