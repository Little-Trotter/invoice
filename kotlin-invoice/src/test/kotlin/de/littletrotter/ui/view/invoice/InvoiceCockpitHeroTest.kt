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
import de.littletrotter.application.dto.JobSummaryDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.CancellationRelationDto
import de.littletrotter.application.dto.CancellationRole
import de.littletrotter.application.export.port.inbound.InvoiceWorkbookExportQueryPort
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
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
 * `CockpitNavigationKaribuTest`/`CancellationActionTest`/`TaxRuleStripTest`: the hero's own composition
 * (header, parties, actions row, tax-rule strip, validation chip, back button, Nachkalkulation) —
 * status-transition/Stornieren button *behaviour* itself is [InvoiceStatusActionsTest]'s concern,
 * mail button/history is [InvoiceMailActionsTest]'s.
 */
class InvoiceCockpitHeroTest {

    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val generationJobQueryPort: GenerationJobQueryPort = mock()

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
        invoiceWorkbookExportService: InvoiceWorkbookExportQueryPort? = null,
        mailDispatch: InvoiceMailDispatchService? = null,
        changeInvoiceStatusUseCase: ChangeInvoiceStatusUseCase = mock(),
    ): InvoiceCockpitHero = InvoiceCockpitHero(
        invoiceDetailQueryPort = invoiceDetailQueryPort,
        recipientQueryPort = recipientQueryPort,
        editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
        taxRateQueries = taxRateQueries,
        invoiceWorkbookExportService = invoiceWorkbookExportService,
        actions = InvoiceCockpitActions(
            InvoiceStatusActions(changeInvoiceStatusUseCase, generationJobQueryPort, MultilingualTextsFeatureConfig(false), InvoiceMailActions(mailDispatch, null), FIXED_TIME_SOURCE),
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
    fun `Stornieren button is visible for an ISSUED invoice without an existing credit note`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(true)
        val built = hero().apply { render(dto(1L, InvoiceStatus.ISSUED)) }

        assertTrue(hasClass(built, "ch-btn--storno"), "ISSUED invoice eligible for a credit note must show the Stornieren action")
    }

    @Test
    fun `Stornieren button is absent when the facade rejects the invoice`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(5L)).thenReturn(false)
        val built = hero().apply { render(dto(5L, InvoiceStatus.ISSUED)) }

        assertFalse(hasClass(built, "ch-btn--storno"), "an ISSUED invoice the facade rejects must stay hidden")
    }

    // ── Nachkalkulation button presence (read-only vs. cancellation document) ──────────────────────────

    @Test
    fun `Neu berechnen button is absent for a DRAFT cancellation document`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(6L)).thenReturn(false)
        // DRAFT is chosen deliberately: it is the one status that would otherwise show every edit
        // affordance, so it is the strongest case for "read-only because it's a cancellation document".
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

    // ── the invoice-workbook export button (InvoiceWorkbookExportQueryPort?) ──────────────────────────

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
        val port: InvoiceWorkbookExportQueryPort = mock()
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

    private fun validationFact(built: Component): Span? =
        flatten(built).filterIsInstance<Span>().firstOrNull {
            it.hasClassName("ch-fact") && it.children.anyMatch { c -> c.element.text == tr("invoiceDetail.validation.label") }
        }

    @Test
    fun `an INVALID validation status renders a bad validation fact in the rail and no chip in the actions`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val built = hero().apply { render(dto(validationStatus = ValidationStatus.INVALID.ordinal)) }

        val fact = validationFact(built)
        assertNotNull(fact)
        assertTrue(fact!!.hasClassName("ch-fact--bad"))
        val actionsDiv = flatten(built).filterIsInstance<Div>().first { it.hasClassName("ch-actions") }
        assertFalse(flatten(actionsDiv).filterIsInstance<Span>().any { it.hasClassName("status-chip") })
    }

    @Test
    fun `validation fact modifiers follow the status`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        fun cls(v: ValidationStatus) =
            validationFact(hero().apply { render(dto(status = InvoiceStatus.IN_REVIEW, validationStatus = v.ordinal)) })!!
        assertTrue(cls(ValidationStatus.UNAVAILABLE).hasClassName("ch-fact--warn"))
        assertTrue(cls(ValidationStatus.PENDING).hasClassName("ch-fact--progress"))
        assertTrue(cls(ValidationStatus.VALID).hasClassName("ch-fact--ok"))
        assertFalse(cls(ValidationStatus.NOT_VALIDATED).classNames.any { it.startsWith("ch-fact--") })
    }

    @Test
    fun `an ISSUED invoice shows no validation fact`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val built = hero().apply { render(dto(status = InvoiceStatus.ISSUED, validationStatus = ValidationStatus.VALID.ordinal)) }

        assertNull(validationFact(built))
    }

    @Test
    fun `a SEND job in flight renders the progress lead fact first in the rail`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val job = GenerationJobView(
            id = 1L, invoiceId = 1L, invoiceNumber = null, jobType = "X", status = GenerationJobStatus.PENDING,
            trigger = GenerationJobTrigger.SEND, issued = false, resultRefs = null, kositReport = null, llmStatus = null,
            createdAt = FIXED_TIME_SOURCE.nowUtc(), attempts = 0, lastError = null, finishedAt = null,
        )
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenReturn(listOf(job))
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
            .copy(latestGenerationJob = JobSummaryDto(id = 1L, status = "PENDING", createdAt = null, resultRefs = null, kositReport = null, lastError = null))
        val built = hero().apply { render(invoice) }

        val rail = taxRuleStrip(built)!!
        val first = rail.children.findFirst().get() as Span
        assertTrue(first.hasClassName("ch-fact--progress") && first.hasClassName("ch-fact--lead"))
        assertTrue(flatten(first).any { it.hasClassName("ch-fact-dot") })
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
        flatten(built).filterIsInstance<Div>().firstOrNull { it.hasClassName("ch-rail") }

    private fun tr(key: String): String = UiTranslations.tr(key)

    private fun railText(rail: Div): String =
        flatten(rail).filterIsInstance<Span>().joinToString(" ") { it.text }

    @Test
    fun `no strip renders when the recipient is not resolvable in master data`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        whenever(recipientQueryPort.findRecipientById(3L)).thenReturn(null)
        val built = hero().apply { render(dto(status = InvoiceStatus.ISSUED, recipient = PartyDto(id = 3L, name = "Kunde"))) }

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
        val text = railText(strip!!)
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
        val text = railText(strip!!)
        assertFalse(text.contains(tr("invoiceDetail.taxRule.customerType.business")), text)
        assertFalse(text.contains(tr("invoiceDetail.taxRule.customerType.consumer")), text)
        assertTrue(text.contains("—"), "no customerType on file must fall back to the placeholder: $text")
    }

    @Test
    fun `no resolved proposal shows the placeholder as rate value and the hint as a separate warn fact`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        whenever(recipientQueryPort.findRecipientById(8L)).thenReturn(
            recipientSummary(8L, CustomerType.BUSINESS, "FR", VatIdVerificationStatus.UNVERIFIED),
        )
        val built = hero().apply { render(dto(recipient = PartyDto(id = 8L, name = "Kunde FR"))) }

        val facts = flatten(taxRuleStrip(built)!!).filterIsInstance<Span>()
        val rate = facts.first { f -> f.children.anyMatch { it is Span && it.text == tr("invoiceDetail.rail.rate") } }
        assertEquals("—", (rate.children.toList().last() as Span).text)
        val warn = facts.first { it.hasClassName("ch-fact--warn") }
        assertTrue(warn.text.isNotBlank() && warn.text != "—", warn.text)
        assertFalse(rate.text.contains(warn.text), "the hint must not be the rate value")
    }

    // ── HALFTER H1: header structure, action order, fact rail ─────────────────────────────────────

    private fun div(built: Component, cls: String): Div = flatten(built).filterIsInstance<Div>().first { it.hasClassName(cls) }

    @Test
    fun `status chip sits inside ch-identity and gross inside ch-kpi, not in the actions`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        val built = hero().apply { render(dto()) }

        assertTrue(flatten(div(built, "ch-identity")).any { it is Span && it.hasClassName("ch-status-badge") })
        val kpi = div(built, "ch-kpi")
        assertTrue(flatten(kpi).any { it is Span && it.hasClassName("ch-gross") })
        assertTrue(flatten(kpi).any { it is Span && it.hasClassName("ch-kpi-label") })
        assertTrue(flatten(div(built, "ch-actions")).none { it is Span && it.hasClassName("ch-gross") })
    }

    @Test
    fun `the primary button is the last action, the danger button the first`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(true)
        val built = hero().apply { render(dto(status = InvoiceStatus.ISSUED)) }

        val children = div(built, "ch-actions").children.toList()
        assertTrue(children.first().hasClassName("ch-btn--danger"), "first: ${children.first().classNames}")
        assertTrue(children.any { it.hasClassName("ch-actions-sep") })

        val inReview = hero().apply { render(dto(status = InvoiceStatus.IN_REVIEW)) }
        val reviewChildren = div(inReview, "ch-actions").children.toList()
        assertTrue(reviewChildren.first().hasClassName("ch-btn--void"))
        assertTrue(reviewChildren.last().hasClassName("ch-btn--finalize"))
        assertTrue((reviewChildren.last() as Button).element.themeList.contains("primary"))
    }

    @Test
    fun `the rail holds the mail lead fact before the tax facts for an issued invoice`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        whenever(recipientQueryPort.findRecipientById(4L)).thenReturn(recipientSummary(4L, CustomerType.BUSINESS, "FR"))
        val built = hero().apply {
            render(dto(status = InvoiceStatus.ISSUED, recipient = PartyDto(id = 4L, name = "Kunde FR")).copy(mailSent = true))
        }

        val rail = div(built, "ch-rail")
        val facts = rail.children.toList().filter { it.hasClassName("ch-fact") }
        assertTrue(facts.first().hasClassName("ch-fact--lead"))
        assertTrue(facts.size >= 4, "lead + customer type, country, VAT id: ${facts.size}")
        assertTrue(rail.children.toList().any { it.hasClassName("ch-rail-sep") })
    }

    // ── Lead facts per status (HALFTER H3) ────────────────────────────────────────────────────

    private fun railFacts(built: Component): List<Component> =
        div(built, "ch-rail").children.toList().filter { it.hasClassName("ch-fact") }

    private fun factLabel(fact: Component): String = flatten(fact).filterIsInstance<Span>().firstOrNull { it.hasClassName("ch-fact-label") }?.text.orEmpty()

    private fun railHasSeparator(built: Component): Boolean = div(built, "ch-rail").children.toList().any { it.hasClassName("ch-rail-sep") }

    private val createdAt = java.time.OffsetDateTime.parse("2026-03-05T10:15:00+01:00")

    private fun stubCancelled(id: Long, relation: CancellationRelationDto?) {
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceDetailView::class.java)
        whenever(invoiceDetailQueryPort.canCreateCreditNote(id)).thenReturn(false)
        whenever(invoiceDetailQueryPort.findCancellationRelation(id)).thenReturn(relation)
    }

    @Test
    fun `a draft shows the created fact before the validation fact`() {
        val built = hero().apply { render(dto(status = InvoiceStatus.DRAFT, validationStatus = ValidationStatus.VALID.ordinal).copy(createdAt = createdAt)) }

        val facts = railFacts(built)
        assertEquals(tr("invoiceDetail.rail.createdAt"), factLabel(facts[0]))
        assertTrue(facts[0].hasClassName("ch-fact--lead"))
        assertTrue(flatten(facts[0]).filterIsInstance<Span>().any { it.hasClassName("ch-fact-value") && it.text.contains("2026") })
        assertEquals(tr("invoiceDetail.validation.label"), factLabel(facts[1]))
    }

    @Test
    fun `a cancelled original shows Storniert durch with a link to the credit note and no tax facts`() {
        stubCancelled(20L, CancellationRelationDto(21L, "INV-2026-03-00043", CancellationRole.CREDIT_NOTE))
        val built = hero().apply { render(dto(id = 20L, status = InvoiceStatus.CANCELLED, recipient = PartyDto(id = 4L, name = "Kunde"))) }

        val facts = railFacts(built)
        assertEquals(1, facts.size)
        assertEquals(tr("invoiceDetail.rail.cancelledBy"), factLabel(facts[0]))
        val link = flatten(facts[0]).filterIsInstance<com.vaadin.flow.router.RouterLink>().single()
        assertEquals("INV-2026-03-00043", link.text)
        assertTrue(link.hasClassName("ch-fact-link"))
        assertFalse(railHasSeparator(built))
    }

    @Test
    fun `a credit note shows Cancellation zu with its original and the mail fact when sent`() {
        stubCancelled(21L, CancellationRelationDto(20L, "INV-2026-03-00042", CancellationRole.ORIGINAL))
        val built = hero().apply {
            render(dto(id = 21L, status = InvoiceStatus.CANCELLED, typeCode = InvoiceTypeCode.CORRECTED_INVOICE.ordinal).copy(mailSent = true))
        }

        val facts = railFacts(built)
        assertEquals(tr("invoiceDetail.rail.stornoOf"), factLabel(facts[0]))
        assertEquals("INV-2026-03-00042", flatten(facts[0]).filterIsInstance<com.vaadin.flow.router.RouterLink>().single().text)
        assertEquals(tr("invoiceDetail.mail.history.lead"), factLabel(facts[1]))
        assertEquals(2, facts.size)
    }

    @Test
    fun `a voided invoice shows the voided and created facts without tax facts or separator`() {
        val built = hero().apply { render(dto(status = InvoiceStatus.VOIDED, recipient = PartyDto(id = 4L, name = "Kunde")).copy(createdAt = createdAt)) }

        val facts = railFacts(built)
        assertEquals(listOf(tr("invoiceDetail.rail.voided"), tr("invoiceDetail.rail.createdAt")), facts.map { factLabel(it) })
        assertFalse(railHasSeparator(built))
    }

    @Test
    fun `an issued invoice keeps the mail fact and the tax facts`() {
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(false)
        whenever(recipientQueryPort.findRecipientById(4L)).thenReturn(recipientSummary(4L, CustomerType.BUSINESS, "FR"))
        val built = hero().apply {
            render(dto(status = InvoiceStatus.ISSUED, recipient = PartyDto(id = 4L, name = "Kunde FR")).copy(mailSent = true))
        }

        assertEquals(tr("invoiceDetail.mail.history.lead"), factLabel(railFacts(built).first()))
        assertTrue(railFacts(built).any { factLabel(it) == tr("invoiceDetail.rail.country") })
        assertTrue(railHasSeparator(built))
    }

    // ── Refresh: rebuild only on a visible change ─────────────────────────────────────────────

    private fun mailButtonOf(hero: InvoiceCockpitHero): Button =
        flatten(hero).filterIsInstance<Button>().single { it.classNames.contains("ch-btn--mail") }

    private fun issuedWithMail(): Pair<InvoiceCockpitHero, InvoiceDetailDto> {
        val dispatch = mock<InvoiceMailDispatchService>()
        whenever(dispatch.isAvailable()).thenReturn(true)
        val current = dto(status = InvoiceStatus.ISSUED)
        return hero(mailDispatch = dispatch).also { it.render(current) } to current
    }

    @Test
    fun `a refresh with the same committed row keeps the rendered action buttons`() {
        val (hero, current) = issuedWithMail()
        val before = mailButtonOf(hero)

        hero.refresh(current)

        assertTrue(before === mailButtonOf(hero), "an unchanged refresh must not replace the send button")
    }

    @Test
    fun `a refresh with a changed row rebuilds the hero`() {
        val (hero, current) = issuedWithMail()
        val before = mailButtonOf(hero)

        hero.refresh(current.copy(buyerReference = "PO-4711"))

        assertFalse(before === mailButtonOf(hero), "a changed row must rebuild the actions row")
    }

    @Test
    fun `a refresh rebuilds when only the credit-note eligibility changed`() {
        val (hero, current) = issuedWithMail()
        assertFalse(hasClass(hero, "ch-btn--storno"))
        whenever(invoiceDetailQueryPort.canCreateCreditNote(1L)).thenReturn(true)

        hero.refresh(current)

        assertTrue(hasClass(hero, "ch-btn--storno"))
    }

    @Test
    fun `a refresh always rebuilds while a generation job is running`() {
        val dispatch = mock<InvoiceMailDispatchService>()
        whenever(dispatch.isAvailable()).thenReturn(true)
        val running = dto(status = InvoiceStatus.ISSUED).copy(
            latestGenerationJob = JobSummaryDto(id = 7L, status = "RUNNING", createdAt = null, resultRefs = null, kositReport = null, lastError = null),
        )
        val hero = hero(mailDispatch = dispatch).also { it.render(running) }
        val before = mailButtonOf(hero)

        hero.refresh(running)

        assertFalse(before === mailButtonOf(hero), "the elapsed-seconds counter needs a rebuild per tick")
    }
}
