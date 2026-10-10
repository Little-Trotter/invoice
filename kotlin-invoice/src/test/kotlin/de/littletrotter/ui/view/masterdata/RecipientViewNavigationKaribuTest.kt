package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.VatIdVerificationPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.ui.view.invoice.toRecipientSummaryDto
import de.littletrotter.ui.view.invoice.toSenderSummaryDto
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Closes [RecipientView.openInvoiceCreateDialogFor]'s real
 * `UI.getCurrent()?.navigate(InvoiceDetailView::class.java, ...)` call, never wrapped in a
 * try/catch and never reached by any other [RecipientView] test (they all stop at asserting the
 * dialog opened, never actually confirming a create). Same route-registration-inside-the-test-body
 * idiom as [de.littletrotter.ui.view.invoice.InvoiceDetailViewCockpitNavigationKaribuTest]'s own
 * KDoc explains in full: `RouteConfiguration.forSessionScope().setAnnotatedRoute(...)` only parses
 * the `@Route` annotation, it never constructs the CDI-injected [InvoiceDetailView].
 */
class RecipientViewNavigationKaribuTest {

    private val manageRecipient: ManageRecipientUseCase = mock()
    private val recipientQuery: RecipientQueryPort = mock()
    private val vatIdVerificationPort: VatIdVerificationPort = mock()
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

    // openInvoiceCreateDialogFor's logic lives in RecipientGridComposer; `internal`
    // visibility lets this same-module test call its public method directly, no method
    // reflection needed (only the field holding it is private).
    private fun openInvoiceCreateDialogFor(view: RecipientView, recipient: RecipientSummaryDto) {
        val f = RecipientView::class.java.getDeclaredField("gridComposer")
        f.isAccessible = true
        (f.get(view) as RecipientGridComposer).openInvoiceCreateDialogFor(recipient)
    }

    private fun dialogCreateButton(dialog: Dialog): Button =
        dialog._get<Button> { text = de.littletrotter.ui.i18n.UiTranslations.tr("invoice.dialog.create") }

    private fun minimalInvoiceDetailDto(id: Long) = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-$id", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = java.time.LocalDate.now(),
        netAmount = de.littletrotter.shared.Money.ZERO, vatAmount = de.littletrotter.shared.Money.ZERO,
        grossAmount = de.littletrotter.shared.Money.ZERO, sender = null, recipient = null,
        items = emptyList(), latestGenerationJob = null,
    )

    @Test
    fun `creating an invoice from a recipient row navigates to its detail view`() {
        val sender = SenderProfile().apply { id = 1L; name = "Acme"; isDefault = true }
        val recipient = Recipient().apply { id = 10L; name = "Kunde GmbH"; customerNumber = CustomerNumber("CUST-10") }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(sender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(eq(1L), eq(10L), anyOrNull())).thenReturn(minimalInvoiceDetailDto(id = 55L))
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceDetailView::class.java)

        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        openInvoiceCreateDialogFor(view, recipient.toRecipientSummaryDto())

        val dialog = _get<Dialog>()
        dialogCreateButton(dialog)._click()

        assertEquals("invoice/55", ui.internals.activeViewLocation.path)
    }
}
