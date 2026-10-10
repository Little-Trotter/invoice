package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.TripEntryQueries
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.invoice.ItemEntryQueries
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import java.time.LocalDate
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/** Shared fixtures of the goods/service item dialog branch tests. */
internal object ItemDialogTestFixtures {

    /** An [InvoiceItemGrid]/[InvoiceItemDialogs] pair over the given ports, standing in
     *  for the full [InvoiceDetailView] fixture the item dialog tests used to build (the
     *  Positionen tab no longer needs the view at all). */
    fun itemComponents(
        taxRateQueries: TaxRateQueryPort,
        editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock(),
        recipientQueryPort: RecipientQueryPort = mock(),
        b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock(),
        b2bFieldValueQueries: B2bFieldValueQueryPort = mock(),
        manageB2bFieldValue: ManageB2bFieldValueUseCase = mock(),
        itemEntryQueryPort: ItemEntryQueryPort = ItemEntryQueries(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue, mock(), taxRateQueries),
    ): Pair<InvoiceItemGrid, InvoiceItemDialogs> {
        val receiptDialogLauncher = ReceiptDialogLauncher(mock(), mock(), mock())
        val tripDialogLauncher = TripDialogLauncher(TripEntryQueries(taxRateQueries, mock()), recipientQueryPort, receiptDialogLauncher, time = FIXED_TIME_SOURCE)
        val itemDialogs = InvoiceItemDialogs(editInvoiceItemsUseCase, itemEntryQueryPort, tripDialogLauncher, time = FIXED_TIME_SOURCE)
        return InvoiceItemGrid(taxRateQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, time = FIXED_TIME_SOURCE) to itemDialogs
    }

    /** For tests unrelated to the Positionen tab that still need a whole [InvoiceDetailView]
     *  (e.g. the E-Rechnung/Übersicht entry-mode suites) — the Positionen tab itself is built
     *  from mocked leaf ports via [testItemsTab], the same idiom [DetailViewTestHarness] uses. */
    fun view(
        refs: ReferenceDataMocks = ReferenceDataMocks(),
        detailQueryPort: InvoiceDetailQueryPort = mock(),
        recipientQueryPort: RecipientQueryPort = mock(),
        senderQueryPort: de.littletrotter.application.masterdata.port.inbound.SenderQueryPort = mock(),
        editInvoiceHeaderUseCase: de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase = mock(),
        invoiceDetailQueryPort: de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort = mock(),
    ): InvoiceDetailView {
        val generationJobQueryService = mock<de.littletrotter.application.invoice.GenerationJobQueryService>()
        val invoiceReceiptQueryPort = mock<de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort>()
        val attachReceiptsUseCase = mock<de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase>()
        val receiptDialogLauncher = ReceiptDialogLauncher(invoiceReceiptQueryPort, attachReceiptsUseCase, invoiceDetailQueryPort)
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryService = generationJobQueryService,
            uiEventBus = de.littletrotter.ui.UiEventBus(),
            invoiceCockpitHero = testCockpitHero(recipientQueryPort = recipientQueryPort, taxRateQueries = refs.taxRateQueries),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(
                    taxRateQueries = refs.taxRateQueries,
                    b2bFieldDefinitionQueries = refs.b2bFieldDefinitionQueries,
                    b2bFieldValueQueries = refs.b2bFieldValueQueries,
                    recipientQueryPort = recipientQueryPort,
                ),
                invoiceReceiptsTab = InvoiceReceiptsTab(invoiceReceiptQueryPort, attachReceiptsUseCase, receiptDialogLauncher),
                exportPanel = testExportPanel(invoiceDetailQueryPort, recipientQueryPort, generationJobQueryService),
            ),
            invoiceEntryPane = testEntryPane(
                recipientQueryPort = recipientQueryPort,
                senderQueryPort = senderQueryPort,
                refs = refs,
                editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
                invoiceDetailQueryPort = invoiceDetailQueryPort,
            ),
        )
    }

    fun rate(code: String, category: String, percent: String, country: Country = Country.DE) = TaxRateDto(
        id = code.hashCode().toLong(),
        code = code,
        category = category,
        rate = TaxRatePercent(BigDecimal(percent)),
        country = country,
        name = code,
        isActive = true,
        isSelectable = true,
    )

    fun invoice(
        reverseCharge: Boolean = false,
        sellerVatRegistrationCountry: String? = null,
        invoiceDate: LocalDate? = LocalDate.of(2026, 6, 1),
        recipient: PartyDto? = null,
    ) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = invoiceDate,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = recipient, items = emptyList(), latestGenerationJob = null,
        reverseCharge = reverseCharge, sellerVatRegistrationCountry = sellerVatRegistrationCountry,
    )

    fun dialog(): Dialog = _find<Dialog>().last { it.isOpened }

    fun form(dialog: Dialog): List<Component> =
        dialog.children.toList().filterIsInstance<FormLayout>().first().children.toList()

    fun save(dialog: Dialog) = dialog._get<Button> { text = UiTranslations.tr("app.save") }.click()
}
