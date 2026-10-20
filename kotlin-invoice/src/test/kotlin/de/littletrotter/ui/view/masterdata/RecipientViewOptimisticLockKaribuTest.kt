package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.RecipientDetailDto
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
import de.littletrotter.fixture.aRecipient
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.component.AddressFormSection
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.toRecipientSummaryDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

/**
 * The recipient edit dialog must reject a save whose in-memory (dialog-open-time) version
 * no longer matches what a fresh reload finds, exactly the shape a concurrent edit by another
 * session produces once [de.littletrotter.infrastructure.persistence.PanacheRecipientRepository
 * .forceVersionIncrement] makes the aggregate's own version actually move (see
 * [de.littletrotter.infrastructure.persistence.RecipientOptimisticLockingIT] for the real-Postgres
 * proof of the underlying conflict). This test pins the UI half: [RecipientView.expectedRevision]/
 * [RecipientView.currentRevision] must surface the conflict as a notification, via
 * [de.littletrotter.ui.notification.UiNotifications] (the existing tier every other
 * [de.littletrotter.ui.view.AbstractCrudView] subclass uses), before any widget value is ever
 * copied onto the entity that would otherwise silently overwrite the other session's change — so
 * the form keeps exactly what the user typed.
 *
 * Idiom (reflection helpers, `saveCurrentForm` invocation) matches
 * [RecipientViewSilentSaveFailureKaribuTest].
 */
class RecipientViewOptimisticLockKaribuTest {

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

    /** Simulates the dialog having been opened for an existing recipient at [openedVersion] —
     *  the version [RecipientView.formDataFrom] would have captured at that moment. */
    private fun markAsExistingAtVersion(view: RecipientView, id: Long, openedVersion: Long) {
        val f = RecipientView::class.java.getDeclaredField("currentFormData")
        f.isAccessible = true
        val blank = f.get(view) as de.littletrotter.application.dto.RecipientDraft
        f.set(view, blank.copy(id = id, version = openedVersion))
    }

    private fun saveCurrentForm(view: RecipientView) {
        val m = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        m.isAccessible = true
        m.invoke(view)
    }

    /** [RecipientView.currentRevision] re-reads the version through [RecipientQueryPort
     *  .findRecipientDetail] (the grid/entity type [de.littletrotter.application.dto
     *  .RecipientSummaryDto] carries no version), so both this and [RecipientQueryPort
     *  .findRecipientById] (the existence check) must be stubbed for [id]/[version] together. */
    private fun detailAtVersion(id: Long, version: Long): RecipientDetailDto = RecipientDetailDto(
        id = id, version = version, customerNumber = null, shortHash = null, name = "Kunde GmbH", vatId = null,
        routingId = null, email = null, phone = null, contactPerson = null, iban = null, customerType = null,
        invoiceFormat = de.littletrotter.domain.shared.InvoiceFormat.GERMAN, documentLanguage = "de",
        reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
        buyerIdentifierValue = null, buyerIdentifierSchemeId = null, legalRegistrationValue = null,
        legalRegistrationSchemeId = null, b2bProfileId = null, partyPairs = emptyMap(),
        billingAddress = null, deliveryAddress = null, vatIdVerificationStatus = null, vatIdVerifiedAt = null,
        vatIdVerificationNote = null,
    )

    private fun fillValidForm(view: RecipientView) {
        textField(view, "nameField").value = "Kunde GmbH"
        textField(view, "customerNumberField").value = "KD-1001"
        billingAddressSection(view).street.value = "Hauptstr. 1"
        billingAddressSection(view).postalCode.value = "10115"
        billingAddressSection(view).city.value = "Berlin"
        textField(view, "emailField").value = "kunde@example.org"
        textField(view, "vatIdField").value = "DE136695976"
    }

    @Test
    fun `a version that moved since the dialog opened is rejected with a conflict notification`() {
        val view = newView()
        // The dialog was opened when the recipient was at version 1 …
        markAsExistingAtVersion(view, 8L, openedVersion = 1L)
        // … but by the time the user clicks save, someone else's concurrent edit already moved it
        // to version 2 (exactly what the force-increment fix makes happen for an address-only
        // change too).
        whenever(recipientQuery.findRecipientById(8L)).thenReturn(aRecipient { id = 8L; version = 2L }.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(8L)).thenReturn(detailAtVersion(8L, version = 2L))

        fillValidForm(view)
        textField(view, "nameField").value = "Mein eigener, noch ungespeicherter Name"

        saveCurrentForm(view)

        val shown = getNotifications().joinToString(" ") { n ->
            n.children.toList().filterIsInstance<com.vaadin.flow.component.html.Div>().flatMap { it.children.toList() }
                .filterIsInstance<com.vaadin.flow.component.html.Span>().joinToString(" ") { it.text ?: "" }
        }
        assertTrue(
            shown.contains(UiTranslations.tr(view, "recipient.error.optimisticLock")),
            "the recipient-specific conflict text must be shown, got: $shown",
        )
        verify(manageRecipient, never()).saveRecipientWithAddresses(any())
        // Form keeps the user's input: the conflict is thrown before applyFormData ever runs, so
        // the widget the user typed into is untouched.
        assertEquals("Mein eigener, noch ungespeicherter Name", textField(view, "nameField").value)
    }

    @Test
    fun `an unchanged version still saves normally`() {
        val view = newView()
        markAsExistingAtVersion(view, 8L, openedVersion = 1L)
        whenever(recipientQuery.findRecipientById(8L)).thenReturn(aRecipient { id = 8L; version = 1L }.toRecipientSummaryDto())
        whenever(recipientQuery.findRecipientDetail(8L)).thenReturn(detailAtVersion(8L, version = 1L))
        whenever(manageRecipient.saveRecipientWithAddresses(any())).thenReturn(detailAtVersion(8L, version = 2L))

        fillValidForm(view)

        saveCurrentForm(view)

        verify(manageRecipient).saveRecipientWithAddresses(any())
    }
}
