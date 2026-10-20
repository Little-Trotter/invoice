package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

/**
 * The recipient form refuses a name the PDF fonts cannot render, so the issued invoice never
 * ends up without a document because of a customer name.
 */
class RecipientViewPdfTextKaribuTest {

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val vatIdVerificationPort: VerifyVatIdUseCase = mock()
    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()

    private fun newView(): RecipientView = RecipientView(
        manageRecipient,
        recipientQuery,
        PartyFormExtensionRegistry(emptyList()),
        vatIdVerificationPort,
        RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue),
        InvoiceCreationDependencies(senderQuery, createInvoiceUseCase), time = FIXED_TIME_SOURCE,
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

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

    /** Marks [view] as editing an existing (already-persisted) recipient — the shape
     *  [AbstractCrudView.saveCurrentForm] needs to take the `findEntityById` branch instead of
     *  `newEntity()`, matching the PO's exact "editing an existing customer" scenario.
     *  `currentFormData` stays `protected` on the override, hence reflection. */
    private fun markAsExisting(view: RecipientView, id: Long) {
        val f = RecipientView::class.java.getDeclaredField("currentFormData")
        f.isAccessible = true
        val blank = f.get(view) as de.littletrotter.application.dto.RecipientDraft
        // Recipient.version defaults to 0 (not null); match that here so the optimistic-lock
        // compare against findEntityById's freshly loaded (also default-0) entity agrees.
        f.set(view, blank.copy(id = id, version = 0L))
    }

    private fun saveCurrentForm(view: RecipientView) {
        val m = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        m.isAccessible = true
        m.invoke(view)
    }

    @Test
    fun `a recipient name with an emoji is marked invalid and nothing is saved`() {
        val view = newView()
        markAsExisting(view, 9L)

        textField(view, "nameField").value = "Müller 💩 GmbH"
        textField(view, "customerNumberField").value = "KD-PDF-1"
        billingAddressSection(view).street.value = "Musterstraße 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "emailField").value = "kunde@example.org"
        textField(view, "vatIdField").value = "DE136695976"

        saveCurrentForm(view)

        val name = textField(view, "nameField")
        assertTrue(name.isInvalid)
        assertEquals(UiTranslations.tr(view, "validation.pdfText.unrenderable") + ": 💩", name.errorMessage)
        assertFalse(textField(view, "emailField").isInvalid)
        verify(manageRecipient, never()).saveRecipientWithAddresses(any())
    }
}
