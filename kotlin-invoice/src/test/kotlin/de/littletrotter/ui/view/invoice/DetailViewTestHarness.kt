package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.TripEntryQueries
import com.vaadin.flow.component.Component
import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.dto.SellerVatRegistrationDto
import de.littletrotter.application.export.InvoiceWorkbookExportPort
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.invoice.DocumentJobPublisher
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
import de.littletrotter.application.invoice.ItemEntryQueries
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.UiEventBus
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/** One [InvoiceDetailView] with every collaborator mocked and reachable by the test. */
internal class DetailViewTestHarness(
    featureSettingsGate: FeatureSettingsGate? = null,
    auditJournalQueryService: AuditJournalQueryService? = null,
    invoiceWorkbookExportService: InvoiceWorkbookExportPort? = null,
) {
    val refs = ReferenceDataMocks()
    val taxRateQueries: TaxRateQueryPort = refs.taxRateQueries
    val paymentTermQueries: PaymentTermQueryPort = refs.paymentTermQueries
    val perDiemRateQueries: PerDiemRateQueryPort = refs.perDiemRateQueries
    val b2bProfileQueries: B2bProfileQueryPort = refs.b2bProfileQueries
    val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = refs.b2bFieldDefinitionQueries
    val b2bFieldValueQueries: B2bFieldValueQueryPort = refs.b2bFieldValueQueries
    val manageB2bFieldValue: ManageB2bFieldValueUseCase = refs.manageB2bFieldValue
    val articleQueries: ArticleQueryPort = refs.articleQueries

    // A real delegating instance over the three mocks above, so a whenever()/verify() against
    // articleQueries/b2bFieldDefinitionQueries/b2bFieldValueQueries also sees the calls the item
    // dialogs route through the item-entry port.
    val itemEntryQueryPort: ItemEntryQueryPort = ItemEntryQueries(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue, articleQueries, taxRateQueries)
    val generationJobQueryService: GenerationJobQueryService = mock()
    val generationJobCreationService: GenerationJobCreationService = mock()
    val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock<InvoiceReceiptQueryPort>().apply {
        whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }
    val attachReceiptsUseCase: AttachReceiptsUseCase = mock()
    val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
    val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    val recipientQueryPort: RecipientQueryPort = mock()
    val senderQueryPort: SenderQueryPort = mock()
    val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()
    val uiEventBus = UiEventBus()

    // Every tab and the cockpit are CDI beans of their own; each is built here from real sub-beans
    // over the mocked ports above, so a test stubs a port once and every component sees it.
    val exportPanel = testExportPanel(invoiceDetailQueryPort, recipientQueryPort, generationJobQueryService, generationJobCreationService)

    val receiptDialogLauncher = ReceiptDialogLauncher(invoiceReceiptQueryPort, attachReceiptsUseCase, invoiceDetailQueryPort)
    val invoiceReceiptsTab = InvoiceReceiptsTab(invoiceReceiptQueryPort, attachReceiptsUseCase, receiptDialogLauncher)

    val invoiceCockpitHero = testCockpitHero(
        invoiceDetailQueryPort = invoiceDetailQueryPort,
        recipientQueryPort = recipientQueryPort,
        taxRateQueries = taxRateQueries,
        generationJobQueryService = generationJobQueryService,
        invoiceWorkbookExportService = invoiceWorkbookExportService,
        featureSettingsGate = featureSettingsGate,
    )

    val invoiceItemsTab = testItemsTab(
        editInvoiceItemsUseCase = editInvoiceItemsUseCase,
        taxRateQueries = taxRateQueries,
        perDiemRateQueries = perDiemRateQueries,
        itemEntryQueryPort = itemEntryQueryPort,
        b2bFieldDefinitionQueries = b2bFieldDefinitionQueries,
        b2bFieldValueQueries = b2bFieldValueQueries,
        recipientQueryPort = recipientQueryPort,
        receiptDialogLauncher = receiptDialogLauncher,
        featureSettingsGate = featureSettingsGate,
    )

    val invoiceEntryPane = testEntryPane(
        recipientQueryPort = recipientQueryPort,
        senderQueryPort = senderQueryPort,
        refs = refs,
        editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
        invoiceDetailQueryPort = invoiceDetailQueryPort,
    )

    val detailTabs = InvoiceDetailTabs(
        overviewPanel = InvoiceOverviewPanel(invoiceDetailQueryPort, recipientQueryPort),
        invoiceItemsTab = invoiceItemsTab,
        invoiceReceiptsTab = invoiceReceiptsTab,
        exportPanel = exportPanel,
        auditJournalQueryService = auditJournalQueryService,
    )

    val view = InvoiceDetailView(
        invoiceDetailQueryPort = invoiceDetailQueryPort,
        generationJobQueryService = generationJobQueryService,
        uiEventBus = uiEventBus,
        invoiceCockpitHero = invoiceCockpitHero,
        detailTabs = detailTabs,
        invoiceEntryPane = invoiceEntryPane,
    )

    companion object {
        fun dto(status: InvoiceStatus = InvoiceStatus.DRAFT, id: Long = 1L): InvoiceDetailDto = InvoiceDetailDto(
            id = id, invoiceNumber = "INV-$id", status = status.ordinal, invoiceDate = null,
            netAmount = null, vatAmount = null, grossAmount = null,
            sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
        )

        fun flatten(component: Component): List<Component> =
            listOf(component) + component.children.toList().flatMap { flatten(it) }
    }
}

/**
 * Shared factory for tests (other than [InvoiceExportPanel]'s own, which construct its
 * sub-panels directly to exercise them) that must supply [InvoiceDetailTabs]'
 * [InvoiceExportPanel] but never exercise the Versionen tab themselves.
 * Built from real sub-panels over mocked leaf ports, same idiom as every other optional
 * collaborator in this package's test fixtures.
 */
internal fun testExportPanel(
    invoiceDetailQueryPort: InvoiceDetailQueryPort,
    recipientQueryPort: RecipientQueryPort,
    generationJobQueryService: GenerationJobQueryService,
    generationJobCreationService: GenerationJobCreationService = mock(),
): InvoiceExportPanel = InvoiceExportPanel(
    printControls = InvoicePrintControls(
        invoiceDetailQueryPort = invoiceDetailQueryPort,
        recipientQueryPort = recipientQueryPort,
        generationJobCreationService = generationJobCreationService,
        documentJobPublisher = mock<DocumentJobPublisher>(),
        llmCheckAvailability = LlmCheckAvailability(),
    ),
    jobsPanel = InvoiceGenerationJobsPanel(
        generationJobQueryService = generationJobQueryService,
        invoiceDetailQueryPort = invoiceDetailQueryPort,
        documentStore = mock<DocumentStore>(), time = FIXED_TIME_SOURCE,
    ),
)

/**
 * Shared factory for tests that must supply [InvoiceDetailView]'s
 * [InvoiceCockpitHero] but never exercise the cockpit hero itself. Built
 * from real sub-beans ([InvoiceStatusActions]/[InvoiceMailActions]) over mocked leaf ports, same
 * idiom as [testExportPanel].
 */
internal fun testCockpitHero(
    invoiceDetailQueryPort: InvoiceDetailQueryPort = mock(),
    recipientQueryPort: RecipientQueryPort = mock(),
    editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock(),
    taxRateQueries: TaxRateQueryPort = mock(),
    invoiceWorkbookExportService: InvoiceWorkbookExportPort? = null,
    changeInvoiceStatusUseCase: ChangeInvoiceStatusUseCase = mock(),
    generationJobQueryService: GenerationJobQueryService = mock(),
    multilingualTextsFeatureConfig: MultilingualTextsFeatureConfig = MultilingualTextsFeatureConfig(false),
    mailDispatch: InvoiceMailDispatchService? = null,
    featureSettingsGate: FeatureSettingsGate? = null,
    mailReadiness: de.littletrotter.application.mail.port.inbound.InvoiceMailReadinessQueryPort? = null,
): InvoiceCockpitHero = InvoiceCockpitHero(
    invoiceDetailQueryPort = invoiceDetailQueryPort,
    recipientQueryPort = recipientQueryPort,
    editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
    taxRateQueries = taxRateQueries,
    invoiceWorkbookExportService = invoiceWorkbookExportService,
    actions = InvoiceCockpitActions(
        InvoiceStatusActions(changeInvoiceStatusUseCase, generationJobQueryService, multilingualTextsFeatureConfig, InvoiceMailActions(mailDispatch, featureSettingsGate, mailReadiness)),
        InvoiceMailActions(mailDispatch, featureSettingsGate, mailReadiness),
    ),
    time = FIXED_TIME_SOURCE,
)

/**
 * Shared factory for tests that must supply [InvoiceDetailTabs]'
 * [InvoiceItemsTab] but never exercise the Positionen tab themselves. Built
 * from real sub-beans ([InvoiceItemGrid]/[InvoiceItemDialogs]/[TripDialogLauncher]) over mocked
 * leaf ports, same idiom as [testExportPanel]/[testCockpitHero].
 */
internal fun testItemsTab(
    editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock(),
    taxRateQueries: TaxRateQueryPort = mock(),
    perDiemRateQueries: PerDiemRateQueryPort = mock(),
    b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock(),
    b2bFieldValueQueries: B2bFieldValueQueryPort = mock(),
    manageB2bFieldValue: ManageB2bFieldValueUseCase = mock(),
    itemEntryQueryPort: ItemEntryQueryPort = ItemEntryQueries(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue, mock(), taxRateQueries),
    recipientQueryPort: RecipientQueryPort = mock(),
    receiptDialogLauncher: ReceiptDialogLauncher = ReceiptDialogLauncher(mock(), mock(), mock()),
    featureSettingsGate: FeatureSettingsGate? = null,
): InvoiceItemsTab {
    val tripDialogLauncher = TripDialogLauncher(
        tripEntryQueryPort = TripEntryQueries(taxRateQueries, perDiemRateQueries),
        recipientQueryPort = recipientQueryPort,
        receiptDialogLauncher = receiptDialogLauncher,
        featureSettingsGate = featureSettingsGate, time = FIXED_TIME_SOURCE,
    )
    return InvoiceItemsTab(
        itemGrid = InvoiceItemGrid(taxRateQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, time = FIXED_TIME_SOURCE),
        invoiceItemDialogs = InvoiceItemDialogs(editInvoiceItemsUseCase, itemEntryQueryPort, tripDialogLauncher, time = FIXED_TIME_SOURCE),
    )
}

/**
 * Shared factory for tests that must supply [InvoiceDetailView]'s [InvoiceEntryPane]. Built from
 * real sub-beans ([InvoiceEinvoiceEntryTab]) over mocked leaf
 * ports, same idiom as [testExportPanel]/[testCockpitHero]/[testItemsTab].
 */
internal fun testEntryPane(
    recipientQueryPort: RecipientQueryPort = mock(),
    senderQueryPort: SenderQueryPort = mock(),
    refs: ReferenceDataMocks = ReferenceDataMocks(),
    editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock(),
    invoiceDetailQueryPort: InvoiceDetailQueryPort = mock(),
    invoiceFormModuleRegistry: InvoiceFormModuleRegistry = mock(),
): InvoiceEntryPane = InvoiceEntryPane(
    recipientQueryPort = recipientQueryPort,
    senderQueryPort = senderQueryPort,
    paymentTermQueries = refs.paymentTermQueries,
    b2bProfileQueries = refs.b2bProfileQueries,
    editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
    invoiceDetailQueryPort = invoiceDetailQueryPort,
    einvoiceEntryTab = InvoiceEinvoiceEntryTab(
        invoiceFormModuleRegistry,
        invoiceDetailQueryPort,
        refs.b2bFieldDefinitionQueries,
        refs.b2bFieldValueQueries,
    ),
)

/** Test-only [RecipientSummaryDto] builder, replacing the entity builder `Recipient().apply { ... }` —
 *  the entry pane's tests stub [RecipientQueryPort] with this DTO now, never the entity. */
internal fun testRecipient(
    id: Long,
    name: String,
    customerNumber: String? = null,
    routingId: String? = null,
    vatId: String? = null,
    customerType: CustomerType? = null,
    vatIdVerificationStatus: VatIdVerificationStatus? = null,
    reverseChargePreference: ReverseChargePreference = ReverseChargePreference.NEVER,
    billingCity: String? = null,
    billingCountryAlpha2: String? = null,
    deliveryCountryAlpha2: String? = null,
): RecipientSummaryDto = RecipientSummaryDto(
    id = id, name = name, customerNumber = customerNumber, shortHash = null, contactPerson = null,
    routingId = routingId, vatId = vatId, customerType = customerType, vatIdVerificationStatus = vatIdVerificationStatus,
    reverseChargePreference = reverseChargePreference, billingCity = billingCity,
    billingCountryAlpha2 = billingCountryAlpha2, deliveryCountryAlpha2 = deliveryCountryAlpha2,
)

/** Test-only [SenderSummaryDto] builder, replacing the entity builder `SenderProfile().apply { ... }` —
 *  the entry pane's tests stub [SenderQueryPort] with this DTO now, never the entity. */
internal fun testSender(
    id: Long,
    name: String,
    vatId: String? = null,
    isDefault: Boolean = false,
    billingAddressCity: String? = null,
    additionalVatRegistrations: List<SellerVatRegistrationDto> = emptyList(),
): SenderSummaryDto = SenderSummaryDto(
    id = id, name = name, vatId = vatId, isDefault = isDefault,
    billingAddressCity = billingAddressCity, additionalVatRegistrations = additionalVatRegistrations,
)

/** Test-only [AddressDto] builder for `RecipientQueryPort.findBillingAddress` stubs — only the
 *  country ever matters to the entry pane's VAT/reverse-charge logic. */
internal fun testAddress(countryAlpha2: String?): AddressDto = AddressDto(id = null, street = null, postalCode = null, city = null, countryAlpha2 = countryAlpha2)

/** The split reference-data facades as one set of fresh mocks, for suites that build the detail
 *  view or its entry pane through [ItemDialogTestFixtures.view] and stub several catalogues. */
internal class ReferenceDataMocks {
    val taxRateQueries: TaxRateQueryPort = mock()
    val paymentTermQueries: PaymentTermQueryPort = mock()
    val perDiemRateQueries: PerDiemRateQueryPort = mock()
    val b2bProfileQueries: B2bProfileQueryPort = mock()
    val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    val articleQueries: ArticleQueryPort = mock()
}
