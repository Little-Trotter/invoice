package de.littletrotter.e2e.support

import de.littletrotter.application.invoice.TripEntryQueries
import de.littletrotter.application.export.InvoiceWorkbookExportPort
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.FacturXDeployment
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.InvoiceFormModuleRegistry
import de.littletrotter.application.invoice.MultilingualTextsFeatureConfig
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.view.invoice.InvoiceCockpitHero
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.InvoiceDetailTabs
import de.littletrotter.ui.view.invoice.InvoiceEinvoiceEntryTab
import de.littletrotter.ui.view.invoice.InvoiceEntryPane
import de.littletrotter.ui.view.invoice.InvoiceExportPanel
import de.littletrotter.ui.view.invoice.InvoiceGenerationJobsPanel
import de.littletrotter.ui.view.invoice.InvoiceItemDialogs
import de.littletrotter.ui.view.invoice.InvoiceItemGrid
import de.littletrotter.ui.view.invoice.InvoiceItemsTab
import de.littletrotter.ui.view.invoice.InvoiceMailActions
import de.littletrotter.ui.view.invoice.InvoiceOverviewPanel
import de.littletrotter.ui.view.invoice.InvoicePrintControls
import de.littletrotter.ui.view.invoice.InvoiceReceiptsTab
import de.littletrotter.ui.view.invoice.InvoiceStatusActions
import de.littletrotter.ui.view.invoice.LlmCheckAvailability
import de.littletrotter.ui.view.invoice.ReceiptDialogLauncher
import de.littletrotter.ui.view.invoice.TripDialogLauncher
import org.mockito.kotlin.mock

/**
 * Shared [InvoiceDetailView] construction for the E2E suites in this module that build the real
 * view by hand instead of going through CDI (Karibu-driven suites that assert against the real
 * rendered tree/save path rather than a mocked-out one). Assembles the view from its own
 * components — [InvoiceCockpitHero], [InvoiceDetailTabs] (overview panel, items tab, receipts
 * tab, export panel, optional audit journal) and [InvoiceEntryPane] — once, here, so every suite
 * in this package stays in sync with the view's own constructor instead of duplicating the wiring.
 *
 * Every parameter without a default is a collaborator a caller must decide about explicitly (real
 * CDI bean where the suite injects one and actually exercises that path, [mock] otherwise); every
 * defaulted parameter is a leaf collaborator no suite in this package exercises directly, so a
 * fresh [mock] (or the documented "feature switched off" value) is the same idiom
 * [de.littletrotter.ui.view.invoice.DetailViewTestHarness] (kotlin-invoice) uses for its
 * own optional collaborators.
 */
internal fun buildInvoiceDetailView(
    taxRateQueries: TaxRateQueryPort,
    generationJobQueryService: GenerationJobQueryService,
    invoiceDetailQueryPort: InvoiceDetailQueryPort,
    recipientQueryPort: RecipientQueryPort,
    invoiceReceiptQueryPort: InvoiceReceiptQueryPort,
    attachReceiptsUseCase: AttachReceiptsUseCase,
    senderQueryPort: SenderQueryPort = mock(),
    uiEventBus: UiEventBus = UiEventBus(),
    invoiceFormModuleRegistry: InvoiceFormModuleRegistry = mock(),
    auditJournalQueryService: AuditJournalQueryService? = null,
    paymentTermQueries: PaymentTermQueryPort = mock(),
    b2bProfileQueries: B2bProfileQueryPort = mock(),
    itemEntryQueryPort: ItemEntryQueryPort = mock(),
    b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock(),
    b2bFieldValueQueries: B2bFieldValueQueryPort = mock(),
    generationJobCreationService: GenerationJobCreationService = mock(),
    documentJobPublisher: DocumentJobPublisher = mock(),
    documentStore: DocumentStore = mock(),
    llmCheckAvailability: LlmCheckAvailability = LlmCheckAvailability(),
    facturXDeployment: FacturXDeployment? = null,
    validationSettingsGate: ValidationSettingsGate? = null,
    editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock(),
    invoiceWorkbookExportService: InvoiceWorkbookExportPort? = null,
    changeInvoiceStatusUseCase: ChangeInvoiceStatusUseCase = mock(),
    multilingualTextsFeatureConfig: MultilingualTextsFeatureConfig = MultilingualTextsFeatureConfig(false),
    mailDispatch: InvoiceMailDispatchService? = null,
    featureSettingsGate: FeatureSettingsGate? = null,
    perDiemRateQueries: PerDiemRateQueryPort = mock(),
    editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock(),
    time: TimeSource = ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
): InvoiceDetailView {
    val receiptDialogLauncher = ReceiptDialogLauncher(invoiceReceiptQueryPort, attachReceiptsUseCase, invoiceDetailQueryPort)
    val tripDialogLauncher = TripDialogLauncher(
        tripEntryQueryPort = TripEntryQueries(taxRateQueries, perDiemRateQueries),
        recipientQueryPort = recipientQueryPort,
        receiptDialogLauncher = receiptDialogLauncher,
        featureSettingsGate = featureSettingsGate,
        time = time,
    )
    return InvoiceDetailView(
        invoiceDetailQueryPort = invoiceDetailQueryPort,
        generationJobQueryService = generationJobQueryService,
        uiEventBus = uiEventBus,
        invoiceCockpitHero = InvoiceCockpitHero(
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            recipientQueryPort = recipientQueryPort,
            editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
            taxRateQueries = taxRateQueries,
            invoiceWorkbookExportService = invoiceWorkbookExportService,
            actions = run {
                val mailActions = InvoiceMailActions(mailDispatch, featureSettingsGate)
                de.littletrotter.ui.view.invoice.InvoiceCockpitActions(
                    InvoiceStatusActions(
                        changeInvoiceStatusUseCase,
                        generationJobQueryService,
                        multilingualTextsFeatureConfig,
                        mailActions,
                    ),
                    mailActions,
                )
            },
            time = time,
        ),
        detailTabs = InvoiceDetailTabs(
            overviewPanel = InvoiceOverviewPanel(invoiceDetailQueryPort, recipientQueryPort),
            invoiceItemsTab = InvoiceItemsTab(
                itemGrid = InvoiceItemGrid(taxRateQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, time),
                invoiceItemDialogs = InvoiceItemDialogs(editInvoiceItemsUseCase, itemEntryQueryPort, tripDialogLauncher, time),
            ),
            invoiceReceiptsTab = InvoiceReceiptsTab(invoiceReceiptQueryPort, attachReceiptsUseCase, receiptDialogLauncher),
            exportPanel = InvoiceExportPanel(
                printControls = InvoicePrintControls(
                    invoiceDetailQueryPort = invoiceDetailQueryPort,
                    recipientQueryPort = recipientQueryPort,
                    generationJobCreationService = generationJobCreationService,
                    documentJobPublisher = documentJobPublisher,
                    llmCheckAvailability = llmCheckAvailability,
                    facturXDeployment = facturXDeployment,
                    validationSettingsGate = validationSettingsGate,
                ),
                jobsPanel = InvoiceGenerationJobsPanel(
                    generationJobQueryService = generationJobQueryService,
                    invoiceDetailQueryPort = invoiceDetailQueryPort,
                    documentStore = documentStore,
                    time = time,
                ),
            ),
            auditJournalQueryService = auditJournalQueryService,
        ),
        invoiceEntryPane = InvoiceEntryPane(
            recipientQueryPort = recipientQueryPort,
            senderQueryPort = senderQueryPort,
            paymentTermQueries = paymentTermQueries,
            b2bProfileQueries = b2bProfileQueries,
            editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            einvoiceEntryTab = InvoiceEinvoiceEntryTab(
                invoiceFormModuleRegistry,
                invoiceDetailQueryPort,
                b2bFieldDefinitionQueries,
                b2bFieldValueQueries,
            ),
        ),
    )
}
