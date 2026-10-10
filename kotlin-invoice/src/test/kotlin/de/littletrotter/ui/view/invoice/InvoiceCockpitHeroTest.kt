package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.export.InvoiceWorkbookExportPort
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.MultilingualTextsFeatureConfig
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [InvoiceCockpitHero], built directly with mocked ports plus [InvoiceDetailState],
 * without [InvoiceDetailView] (same idiom as other component-level tests). Covers what used to be
 * spread across `InvoiceDetailViewCockpitActionsKaribuTest`/`CockpitBoundaryKaribuTest`/
 * `CockpitNavigationKaribuTest`/`StornoActionTest`/`TaxRuleStripTest`: the hero's own composition
 * (header, parties, actions row, tax-rule strip, validation chip, back button, Nachkalkulation) —
 * status-transition/Stornieren button *behaviour* itself is [InvoiceStatusActionsTest]'s concern,
 * mail button/history is [InvoiceMailActionsTest]'s.
 */
class InvoiceCockpitHeroTest {

    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val generationJobQueryService: GenerationJobQueryService = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        clearNotifications()
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun hero(
        invoiceWorkbookExportService: InvoiceWorkbookExportPort? = null,
        mailDispatch: InvoiceMailDispatchService? = null,
        changeInvoiceStatusUseCase: ChangeInvoiceStatusUseCase = mock(),
    ): InvoiceCockpitHero = InvoiceCockpitHero(
        invoiceDetailQueryPort = invoiceDetailQueryPort,
        recipientQueryPort = recipientQueryPort,
        editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
        taxRateQueries = taxRateQueries,
        invoiceWorkbookExportService = invoiceWorkbookExportService,
        actions = InvoiceCockpitActions(
            InvoiceStatusActions(changeInvoiceStatusUseCase, generationJobQueryService, MultilingualTextsFeatureConfig(false), InvoiceMailActions(mailDispatch, null)),
            InvoiceMailActions(mailDispatch, null),
        ),
        time = FIXED_TIME_SOURCE,
    )

    private fun dto(
        id: Long = 1L,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
        typeCode: Int? = null,
        recipient: PartyDto? = null,
        grossAmount: Money? = null,
        validationStatus: Int? = null,
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-2026-03-00042", status = status.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = grossAmount,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = recipient, items = emptyList(), latestGenerationJob = null,
        typeCode = typeCode, validationStatus = validationStatus,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun hasClass(built: Component, cssClass: String): Boolean =
        flatten(built).filterIsInstance<Button>().any { it.classNames.contains(cssClass) }

    // ── Stornieren gating (InvoiceDetailQueryPort.canCreateCreditNote) ───────────────────────────

    @Test
    fun `Stornieren button is visible for a SENT invoice without an existing credit note`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(true)
        val built = hero().apply { render(dto(1L, InvoiceStatus.SENT)) }

        assertTrue(hasClass(built, "ch-btn--storno"), "SENT invoice eligible for a credit note must show the Stornieren action")
    }

    @Test
    fun `Stornieren button is absent when the facade rejects the invoice`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(5L)).thenReturn(false)
        val built = hero().apply { render(dto(5L, InvoiceStatus.SENT)) }

        assertFalse(hasClass(built, "ch-btn--storno"), "a SENT invoice the facade rejects must stay hidden")
    }

    // ── Nachkalkulation button presence (read-only vs. storno document) ──────────────────────────

    @Test
    fun `Neu berechnen button is absent for a DRAFT storno document`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(6L)).thenReturn(false)
        // DRAFT is chosen deliberately: it is the one status that would otherwise show every edit
        // affordance, so it is the strongest case for "read-only because it's a storno document".
        val built = hero().apply { render(dto(6L, InvoiceStatus.DRAFT, typeCode = InvoiceTypeCode.CORRECTED_INVOICE.ordinal)) }

        assertFalse(hasClass(built, "ch-btn--recalculate"), "a storno document must never show edit/recalculate affordances, even as DRAFT")
    }

    @Test
    fun `Neu berechnen button is present for a normal DRAFT invoice`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(7L)).thenReturn(false)
        val built = hero().apply { render(dto(7L, InvoiceStatus.DRAFT)) }

        assertTrue(hasClass(built, "ch-btn--recalculate"), "a normal draft invoice must still show Neu berechnen")
    }

    // ── recalculateAndRefresh ─────────────────────────────────────────────────────────────────────

    @Test
    fun `recalculateAndRefresh success applies the returned invoice through InvoiceDetailState`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val invoice = dto()
        whenever(editInvoiceHeaderUseCase.recalculateInvoice(1L)).thenReturn(invoice.copy(invoiceNumber = "INV-RECALCULATED"))
        val h = hero()
        var applied: InvoiceDetailDto? = null
        val state = InvoiceDetailState().also { it.onInvoiceChanged { current -> applied = current } }
        h.bind(state)

        h.recalculateAndRefresh(invoice)

        assertEquals("INV-RECALCULATED", applied?.invoiceNumber)
        assertEquals(1, getNotifications().size)
    }

    @Test
    fun `recalculateAndRefresh failure shows an error and never applies a change`() {
        whenever(editInvoiceHeaderUseCase.recalculateInvoice(1L)).doThrow(RuntimeException("boom"))
        val h = hero()
        var applyCount = 0
        val state = InvoiceDetailState().also { it.onInvoiceChanged { applyCount++ } }
        h.bind(state)

        h.recalculateAndRefresh(dto())

        assertEquals(1, getNotifications().size)
        assertEquals(0, applyCount)
    }

    // ── the invoice-workbook export button (InvoiceWorkbookExportPort?) ──────────────────────────

    @Test
    fun `a wired workbook export port adds the export button to the cockpit hero`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val built = hero(invoiceWorkbookExportService = mock()).apply { render(dto()) }

        assertEquals(1, flatten(built).count { it.id.orElse(null) == INVOICE_EXPORT_BUTTON_ID })
    }

    @Test
    fun `without the export port the cockpit hero has no export button`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val built = hero(invoiceWorkbookExportService = null).apply { render(dto()) }

        assertTrue(flatten(built).none { it.id.orElse(null) == INVOICE_EXPORT_BUTTON_ID })
    }

    @Test
    fun `the cockpit export anchor streams the workbook of exactly this invoice`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val port: InvoiceWorkbookExportPort = mock()
        val built = hero(invoiceWorkbookExportService = port).apply { render(dto()) }

        val anchor = flatten(built).filterIsInstance<com.vaadin.flow.component.html.Anchor>()
            .single { a -> a.children.anyMatch { it.id.orElse(null) == INVOICE_EXPORT_BUTTON_ID } }
        DownloadAnchorTestSupport.invoke(anchor).inputStream.close()

        org.mockito.kotlin.verify(port).writeWorkbook(
            org.mockito.kotlin.eq(1L),
            org.mockito.kotlin.eq(UiTranslations.tr("invoiceExport.export.selection.singleInvoice", "INV-2026-03-00042")),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
        )
    }

    // ── buildValidationChip (now top-level, no receiver) ──────────────────────────────────────────

    @Test
    fun `VALID chip carries no tooltip`() {
        val chip = buildValidationChip(null, ValidationStatus.VALID)
        assertEquals(UiTranslations.tr("invoice.validation.valid"), chip.text)
        assertFalse(chip.element.hasAttribute("title"))
    }

    @Test
    fun `UNAVAILABLE chip carries its own tooltip`() {
        val chip = buildValidationChip(null, ValidationStatus.UNAVAILABLE)
        assertEquals(UiTranslations.tr("invoice.validation.unavailable"), chip.text)
        assertEquals(UiTranslations.tr("invoice.validation.unavailable.tooltip"), chip.element.getAttribute("title"))
    }

    @Test
    fun `PENDING chip carries its own tooltip`() {
        val chip = buildValidationChip(null, ValidationStatus.PENDING)
        assertEquals(UiTranslations.tr("invoice.validation.pending.tooltip"), chip.element.getAttribute("title"))
    }

    // ── the INVALID-only validation chip guard in the hero itself ────────────────────────────────

    @Test
    fun `an INVALID validation status shows the validation chip in the hero`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val built = hero().apply { render(dto(validationStatus = ValidationStatus.INVALID.ordinal)) }

        assertTrue(flatten(built).filterIsInstance<Span>().any { it.classNames.contains("status-chip") && !it.classNames.contains("ch-status-badge") })
    }

    @Test
    fun `a VALID validation status shows no validation chip in the hero`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val built = hero().apply { render(dto(validationStatus = ValidationStatus.VALID.ordinal)) }

        assertFalse(flatten(built).filterIsInstance<Span>().any { it.classNames.contains("status-chip") && !it.classNames.contains("ch-status-badge") })
    }

    // ── the back button navigates to InvoiceListView, preserving query parameters ────────────────

    @Test
    fun `the back button reaches the navigate call to InvoiceListView`() {
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceListView::class.java)
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val built = hero().apply { render(dto()) }
        val ui = UI.getCurrent()!!
        ui.add(built)
        MockVaadin.clientRoundtrip()

        flatten(built).filterIsInstance<Button>().single { it.hasClassName("ch-back-btn") }._click()

        assertEquals("", ui.internals.activeViewLocation.path)
    }

    // ── the tax-rule status strip ─────────────────────────────────────────────────────────────────

    private fun recipientSummary(
        id: Long,
        customerType: CustomerType?,
        countryAlpha2: String?,
        verificationStatus: VatIdVerificationStatus? = null,
        vatId: String? = "FR12345678901",
    ) = RecipientSummaryDto(
        id = id, name = "Kunde", customerNumber = null, shortHash = null, contactPerson = null,
        routingId = null, vatId = vatId, customerType = customerType, vatIdVerificationStatus = verificationStatus,
        reverseChargePreference = ReverseChargePreference.ASK, billingCity = null, billingCountryAlpha2 = countryAlpha2,
        deliveryCountryAlpha2 = null,
    )

    private fun taxRuleStrip(built: Component): Div? =
        flatten(built).filterIsInstance<Div>().firstOrNull { it.hasClassName("ch-taxrule-strip") }

    private fun tr(key: String): String = UiTranslations.tr(key)

    @Test
    fun `tax rule hint is separated from the summary line by a middle dot`() {
        assertEquals("\u00A0·\u00A0DE 19 %", hero().separatedTaxRuleHint("DE 19 %"))
        assertEquals("", hero().separatedTaxRuleHint(""))
    }

    @Test
    fun `no strip renders when the recipient is not resolvable in master data`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(null)
        val built = hero().apply { render(dto(recipient = PartyDto(id = 3L, name = "Kunde"))) }

        assertNull(taxRuleStrip(built), "an assigned recipient the port can't resolve must not render the strip")
    }

    @Test
    fun `a BUSINESS recipient with a VERIFIED VAT id renders its labels in the summary line`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        whenever(recipientQueryPort.findRecipientById(4L)).thenReturn(
            recipientSummary(4L, CustomerType.BUSINESS, "FR", VatIdVerificationStatus.VERIFIED),
        )
        whenever(recipientQueryPort.findBillingAddress(4L)).thenReturn(
            de.littletrotter.application.dto.AddressDto(id = null, street = null, postalCode = null, city = null, countryAlpha2 = "FR"),
        )
        val built = hero().apply { render(dto(recipient = PartyDto(id = 4L, name = "Kunde FR"))) }

        val strip = taxRuleStrip(built)
        assertNotNull(strip)
        val text = (strip!!.children.toList()[0] as Span).text
        assertTrue(text.contains(tr("invoiceDetail.taxRule.customerType.business")), text)
        assertTrue(text.contains(tr("invoiceDetail.taxRule.verificationStatus.verified")), text)
        assertTrue(text.contains("FR"), text)
    }

    @Test
    fun `a recipient with no customerType on file renders the placeholder instead of business or consumer`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        whenever(recipientQueryPort.findRecipientById(7L)).thenReturn(
            recipientSummary(7L, null, null, vatId = null),
        )
        val built = hero().apply { render(dto(recipient = PartyDto(id = 7L, name = "Kunde ohne Typ"))) }

        val strip = taxRuleStrip(built)
        assertNotNull(strip)
        val text = (strip!!.children.toList()[0] as Span).text
        assertFalse(text.contains(tr("invoiceDetail.taxRule.customerType.business")), text)
        assertFalse(text.contains(tr("invoiceDetail.taxRule.customerType.consumer")), text)
        assertTrue(text.contains("—"), "no customerType on file must fall back to the placeholder: $text")
    }
}
