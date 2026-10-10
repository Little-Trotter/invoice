package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.MultilingualTextsFeatureConfig
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.domain.compliance.GateBlockReason
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.exception.InvoiceStornoNotAllowedException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.StornoRejection
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [InvoiceStatusActions], built directly with a mocked [ChangeInvoiceStatusUseCase]/
 * [GenerationJobQueryService], without [InvoiceDetailView] or [InvoiceCockpitHero] (same idiom
 * other component tests use). Formerly spread across
 * `InvoiceDetailViewCockpitActionsKaribuTest`/`CockpitBoundaryKaribuTest`/`StornoActionTest`/
 * `StornoDialogKaribuTest`/`StatusTransitionsBoundaryKaribuTest`.
 */
class InvoiceStatusActionsTest {

    private val changeInvoiceStatusUseCase: ChangeInvoiceStatusUseCase = mock()
    private val generationJobQueryService: GenerationJobQueryService = mock()
    private val mailActions: InvoiceMailActions = mock()
    private val host = Div()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        clearNotifications()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun actions(multilingualTextsEnabled: Boolean = false): InvoiceStatusActions =
        InvoiceStatusActions(changeInvoiceStatusUseCase, generationJobQueryService, MultilingualTextsFeatureConfig(multilingualTextsEnabled), mailActions)

    private fun dto(
        id: Long = 1L,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
        typeCode: Int? = null,
        documentLanguage: String = "de",
        validationStatus: Int? = null,
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-2026-03-00042", status = status.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = null, items = emptyList(), latestGenerationJob = null,
        typeCode = typeCode, documentLanguage = documentLanguage, validationStatus = validationStatus,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun notificationText(notification: Notification): String =
        de.littletrotter.ui.toastText(notification)

    private fun boundState(applied: MutableList<InvoiceDetailDto> = mutableListOf()): InvoiceDetailState =
        InvoiceDetailState().also { it.onInvoiceChanged { current -> applied.add(current) } }

    // ── transitionAndRefresh: illegal transition ─────────────────────────────

    @Test
    fun `an illegal transition shows an error and never calls the use case`() {
        actions().transitionAndRefresh(host, dto(status = InvoiceStatus.DRAFT), InvoiceStatus.SENT)

        verify(changeInvoiceStatusUseCase, never()).transitionInvoice(any(), any(), anyOrNull())
        assertEquals(1, getNotifications().size)
    }

    // ── transitionAndRefresh: legal transition, success ──────────────────────

    @Test
    fun `a legal transition applies the result through InvoiceDetailState and shows a success notification`() {
        whenever(changeInvoiceStatusUseCase.transitionInvoice(1L, InvoiceStatus.IN_REVIEW, null))
            .thenReturn(dto(status = InvoiceStatus.IN_REVIEW))
        val applied = mutableListOf<InvoiceDetailDto>()
        val a = actions().also { it.bind(boundState(applied)) }

        a.transitionAndRefresh(host, dto(status = InvoiceStatus.DRAFT), InvoiceStatus.IN_REVIEW)

        assertEquals(InvoiceStatus.IN_REVIEW, applied.single().statusEnum())
        MockVaadin.clientRoundtrip()
        val toast = getNotifications().single()
        assertEquals(Notification.Position.BOTTOM_START, toast.position)
        assertTrue(toast.themeNames.contains("success"))
        assertEquals(
            UiTranslations.tr("invoiceDetail.statusChanged", UiTranslations.tr(InvoiceStatus.IN_REVIEW.labelKey)),
            de.littletrotter.ui.toastText(toast),
        )
    }

    // ── transitionAndRefresh: InvoiceFinalizationBlockedException dispatch ───

    @Test
    fun `VALIDATION_UNAVAILABLE opens the override dialog instead of a plain error`() {
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull()))
            .thenThrow(InvoiceFinalizationBlockedException(GateBlockReason.VALIDATION_UNAVAILABLE))

        actions().transitionAndRefresh(host, dto(status = InvoiceStatus.IN_REVIEW), InvoiceStatus.SENT)

        val dialog = _get<Dialog>()
        assertEquals(UiTranslations.tr("invoiceDetail.finalize.override.title"), dialog.headerTitle)
        assertTrue(getNotifications().isEmpty(), "the override dialog replaces the plain error toast for this one reason")
    }

    @Test
    fun `VALIDATION_INVALID with no findings shows the plain blocked key with no trailing findings text`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull()))
            .thenThrow(InvoiceFinalizationBlockedException(GateBlockReason.VALIDATION_INVALID, emptyList()))

        actions().transitionAndRefresh(host, invoice, InvoiceStatus.SENT)

        val text = notificationText(getNotifications().single())
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.finalize.blocked.invalid")), text)
        assertEquals(InvoiceStatus.IN_REVIEW, invoice.statusEnum(), "a blocked transition must not mutate the invoice")
    }

    @Test
    fun `VALIDATION_PENDING with a compliance-rules finding resolves message through tr()`() {
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull())).thenThrow(
            InvoiceFinalizationBlockedException(
                GateBlockReason.VALIDATION_PENDING,
                listOf(ValidationMessage(severity = "ERROR", message = "invoiceDetail.recalculate.success", ruleId = "R1", validator = "compliance-rules")),
            ),
        )

        actions().transitionAndRefresh(host, dto(status = InvoiceStatus.IN_REVIEW), InvoiceStatus.SENT)

        val text = notificationText(getNotifications().single())
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.finalize.blocked.pending")), text)
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.recalculate.success")), "a compliance-rules finding's message must be resolved as an i18n key: $text")
    }

    @Test
    fun `PROFILE_BLOCKED_OR_UNKNOWN with a non-compliance-rules finding uses the raw message`() {
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull())).thenThrow(
            InvoiceFinalizationBlockedException(
                GateBlockReason.PROFILE_BLOCKED_OR_UNKNOWN,
                listOf(ValidationMessage(severity = "ERROR", message = "Raw KoSIT text, not a key", ruleId = "R2", validator = "kosit")),
            ),
        )

        actions().transitionAndRefresh(host, dto(status = InvoiceStatus.IN_REVIEW), InvoiceStatus.SENT)

        val text = notificationText(getNotifications().single())
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.finalize.blocked.profile")), text)
        assertTrue(text.contains("Raw KoSIT text, not a key"), "a non-compliance-rules finding must render its raw message verbatim: $text")
    }

    @Test
    fun `COMPLIANCE_RULE_VIOLATION renders its own blocked key`() {
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull()))
            .thenThrow(InvoiceFinalizationBlockedException(GateBlockReason.COMPLIANCE_RULE_VIOLATION, emptyList()))

        actions().transitionAndRefresh(host, dto(status = InvoiceStatus.IN_REVIEW), InvoiceStatus.SENT)

        val text = notificationText(getNotifications().single())
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.finalize.blocked.complianceRule")), text)
    }

    @Test
    fun `more than three findings are cut to the first three`() {
        val findings = (1..4).map { ValidationMessage(severity = "ERROR", message = "Befund $it", ruleId = "R$it", validator = "kosit") }
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull()))
            .thenThrow(InvoiceFinalizationBlockedException(GateBlockReason.VALIDATION_INVALID, findings))

        actions().transitionAndRefresh(host, dto(status = InvoiceStatus.IN_REVIEW), InvoiceStatus.SENT)

        val text = notificationText(getNotifications().single())
        assertTrue(text.contains("Befund 3"), text)
        assertFalse(text.contains("Befund 4"), "only the first three findings are listed: $text")
    }

    @Test
    fun `VALIDATION_NOT_RUN shows the not-run blocked key and leaves the invoice in review`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull()))
            .thenThrow(InvoiceFinalizationBlockedException(GateBlockReason.VALIDATION_NOT_RUN, emptyList()))

        actions().transitionAndRefresh(host, invoice, InvoiceStatus.SENT)

        assertTrue(notificationText(getNotifications().single()).contains(UiTranslations.tr("invoiceDetail.finalize.blocked.notRun")))
        assertEquals(InvoiceStatus.IN_REVIEW, invoice.statusEnum())
    }

    @Test
    fun `a compliance unit finding renders its unit argument as a label, not the enum name`() {
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull())).thenThrow(
            InvoiceFinalizationBlockedException(
                GateBlockReason.COMPLIANCE_RULE_VIOLATION,
                listOf(
                    ValidationMessage(
                        severity = "ERROR", message = "compliance.unit.quantityNotPositive", ruleId = "UNIT-1",
                        validator = "compliance-rules", args = listOf("0", "PIECE"),
                    ),
                ),
            ),
        )

        actions().transitionAndRefresh(host, dto(status = InvoiceStatus.IN_REVIEW), InvoiceStatus.SENT)

        val text = notificationText(getNotifications().single())
        assertTrue(text.contains(InvoiceUnitLabels.label(null, "PIECE")), text)
        assertFalse(text.contains("(PIECE)"), "the raw enum name must never reach the user: $text")
    }

    // ── transitionAndRefresh: generic exception catch-all ────────────────────

    @Test
    fun `a generic exception shows an error and leaves the invoice untouched`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull())).doThrow(RuntimeException("boom"))

        actions().transitionAndRefresh(host, invoice, InvoiceStatus.SENT)

        assertEquals(1, getNotifications().size)
        assertEquals(InvoiceStatus.IN_REVIEW, invoice.statusEnum())
    }

    @Test
    fun `boundary - a transition failing without a message shows the status-change text with an empty detail`() {
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.IN_REVIEW), anyOrNull())).thenThrow(RuntimeException())

        actions().transitionAndRefresh(host, dto(status = InvoiceStatus.DRAFT), InvoiceStatus.IN_REVIEW)

        assertTrue(notificationText(getNotifications().single()).contains(UiTranslations.tr("invoiceDetail.error.statusChangeFailed", "")))
    }

    // ── the "Senden" button: buildButtons/requestSendAndRefresh/hasInFlightSendJob ───────────────

    private fun sendJob(status: GenerationJobStatus): GenerationJob = GenerationJob().apply {
        id = 1L; this.status = status; trigger = GenerationJobTrigger.SEND
    }

    private fun findSendButton(buttons: List<Component>): Button =
        buttons.filterIsInstance<Button>().single { it.hasClassName("ch-btn--finalize") }

    private fun buildFor(invoice: InvoiceDetailDto, a: InvoiceStatusActions = actions()): List<Component> =
        a.buildButtons(host, invoice, canCreateCreditNote = false)

    @Test
    fun `clicking Senden requests a SEND job and never calls transitionInvoice directly`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.requestSend(1L)).thenReturn(invoice)

        findSendButton(buildFor(invoice))._click()

        verify(changeInvoiceStatusUseCase).requestSend(1L)
        verify(changeInvoiceStatusUseCase, never()).transitionInvoice(any(), any(), anyOrNull())
        assertEquals(1, getNotifications().size)
    }

    @Test
    fun `with the mail module available the review send button is E-Mail senden and no Versenden exists`() {
        whenever(mailActions.isMailAvailable()).thenReturn(true)
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)

        val texts = buildFor(invoice).filterIsInstance<Button>().map { it.text }

        assertTrue(texts.contains(UiTranslations.tr("invoiceDetail.mail.send")), texts.toString())
        assertFalse(texts.contains(UiTranslations.tr("invoiceDetail.finalize")), texts.toString())
        assertEquals(1, buildFor(invoice).filterIsInstance<Button>().count { it.hasClassName("ch-btn--mail") })
    }

    @Test
    fun `without the mail module the review send button stays Versenden`() {
        val texts = buildFor(dto(status = InvoiceStatus.IN_REVIEW)).filterIsInstance<Button>().map { it.text }

        assertTrue(texts.contains(UiTranslations.tr("invoiceDetail.finalize")), texts.toString())
        assertFalse(texts.contains(UiTranslations.tr("invoiceDetail.mail.send")), texts.toString())
    }

    @Test
    fun `a SENT invoice offers no mail action even with the mail module available`() {
        whenever(mailActions.isMailAvailable()).thenReturn(true)

        val buttons = buildFor(dto(status = InvoiceStatus.SENT)).filterIsInstance<Button>()

        assertTrue(buttons.none { it.hasClassName("ch-btn--mail") })
    }

    @Test
    fun `clicking E-Mail senden requests the send job and remembers to open the mail draft`() {
        whenever(mailActions.isMailAvailable()).thenReturn(true)
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.requestSend(1L)).thenReturn(invoice)
        val state = boundState()
        val a = actions().also { it.bind(state) }

        buildFor(invoice, a).filterIsInstance<Button>().single { it.hasClassName("ch-btn--mail") }._click()

        verify(changeInvoiceStatusUseCase).requestSend(1L)
        assertEquals(1L, state.pendingMailComposeInvoiceId)
    }

    @Test
    fun `clicking Versenden does not remember a mail draft`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.requestSend(1L)).thenReturn(invoice)
        val state = boundState()
        val a = actions().also { it.bind(state) }

        findSendButton(buildFor(invoice, a))._click()

        assertNull(state.pendingMailComposeInvoiceId)
    }

    @Test
    fun `a refused E-Mail senden request leaves no mail draft pending`() {
        whenever(mailActions.isMailAvailable()).thenReturn(true)
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.requestSend(1L)).doThrow(RuntimeException("boom"))
        val state = boundState()
        val a = actions().also { it.bind(state) }

        buildFor(invoice, a).filterIsInstance<Button>().single { it.hasClassName("ch-btn--mail") }._click()

        assertNull(state.pendingMailComposeInvoiceId)
    }

    @Test
    fun `the Senden button is disabled with a tooltip while a SEND job is already in flight`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(generationJobQueryService.findByInvoiceId(1L)).thenReturn(listOf(sendJob(GenerationJobStatus.PENDING)))

        val button = findSendButton(buildFor(invoice))

        assertFalse(button.isEnabled)
        assertEquals(UiTranslations.tr("invoiceDetail.finalize.inProgress.tooltip"), button.element.getAttribute("title"))
    }

    @Test
    fun `the Senden button stays enabled while only an unrelated MANUAL job is in flight`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        val manualJob = GenerationJob().apply { id = 2L; status = GenerationJobStatus.PENDING; trigger = GenerationJobTrigger.MANUAL }
        whenever(generationJobQueryService.findByInvoiceId(1L)).thenReturn(listOf(manualJob))

        assertTrue(findSendButton(buildFor(invoice)).isEnabled)
    }

    @Test
    fun `the Senden button re-enables once the SEND job reaches a terminal state`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(generationJobQueryService.findByInvoiceId(1L)).thenReturn(listOf(sendJob(GenerationJobStatus.COMPLETED)))

        assertTrue(findSendButton(buildFor(invoice)).isEnabled)
    }

    @Test
    fun `a failed send request shows an error and never applies a change`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.requestSend(1L)).doThrow(RuntimeException("boom"))
        val applied = mutableListOf<InvoiceDetailDto>()
        val a = actions().also { it.bind(boundState(applied)) }

        findSendButton(buildFor(invoice, a))._click()

        assertEquals(1, getNotifications().size)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a send request without positions shows the resolved NO_ITEMS message, not the error code`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.requestSend(1L)).thenThrow(InvoiceValidationException(InvoiceValidationError.NO_ITEMS))

        findSendButton(buildFor(invoice))._click()

        assertEquals(UiTranslations.tr("domain.validation.noItems"), notificationText(getNotifications().single()))
    }

    @Test
    fun `a send request blocked by the document size budget names the estimated and allowed size`() {
        // PO decision — GenerationJobCreationService now owns this hard stop (linking a receipt no
        // longer refuses on size): the "Senden" click reaches it via requestSend -> createJob, and
        // must show the same wording as the editor's own too-large banner, not the generic
        // status-change-failed fallback.
        val estimated = de.littletrotter.shared.receipt.DocumentSize.ofMebiBytes(25L)
        val limit = de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE
        whenever(changeInvoiceStatusUseCase.requestSend(1L))
            .thenThrow(de.littletrotter.application.invoice.InvoiceDocumentSizeExceededException(limit, estimated))

        findSendButton(buildFor(dto(status = InvoiceStatus.IN_REVIEW)))._click()

        assertEquals(
            UiTranslations.tr(
                "receipt.budget.tooLarge.blocked",
                de.littletrotter.ui.format.DocumentSizeFormatter.format(estimated),
                de.littletrotter.ui.format.DocumentSizeFormatter.format(limit),
            ),
            notificationText(getNotifications().single()),
        )
    }

    @Test
    fun `boundary - a send request failing without a message falls back to the status-change text`() {
        whenever(changeInvoiceStatusUseCase.requestSend(1L)).thenThrow(RuntimeException())

        findSendButton(buildFor(dto(status = InvoiceStatus.IN_REVIEW)))._click()

        assertTrue(notificationText(getNotifications().single()).contains(UiTranslations.tr("invoiceDetail.error.statusChangeFailed", "")))
    }

    // ── the UNAVAILABLE finalize-override action ──────────────────────────────────────────────────

    private fun findOverrideButton(buttons: List<Component>): Button? =
        buttons.filterIsInstance<Button>().singleOrNull { it.hasClassName("ch-btn--override") }

    @Test
    fun `the override action appears when validationStatus is UNAVAILABLE and no SEND job is in flight`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW, validationStatus = ValidationStatus.UNAVAILABLE.ordinal)

        val button = findOverrideButton(buildFor(invoice))

        assertTrue(button != null && button.isEnabled, "the override action must be offered for UNAVAILABLE")
    }

    @Test
    fun `the override action is not offered for INVALID -- unchanged dead end`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW, validationStatus = ValidationStatus.INVALID.ordinal)

        assertNull(findOverrideButton(buildFor(invoice)))
    }

    @Test
    fun `the override action is hidden while a SEND job is still in flight, even when UNAVAILABLE`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW, validationStatus = ValidationStatus.UNAVAILABLE.ordinal)
        whenever(generationJobQueryService.findByInvoiceId(1L)).thenReturn(listOf(sendJob(GenerationJobStatus.PENDING)))

        assertNull(findOverrideButton(buildFor(invoice)))
    }

    @Test
    fun `clicking the override action, entering a reason and confirming transitions to SENT via transitionInvoice directly`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW, validationStatus = ValidationStatus.UNAVAILABLE.ordinal)
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), eq("Validator war nicht erreichbar")))
            .thenReturn(dto(status = InvoiceStatus.SENT))
        val applied = mutableListOf<InvoiceDetailDto>()
        val a = actions().also { it.bind(boundState(applied)) }

        findOverrideButton(buildFor(invoice, a))!!._click()
        val dialog = _get<Dialog>()
        dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.finalize.override.reasonLabel") }.value = "Validator war nicht erreichbar"
        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.finalize.override.confirm") }._click()

        verify(changeInvoiceStatusUseCase).transitionInvoice(1L, InvoiceStatus.SENT, "Validator war nicht erreichbar")
        verify(changeInvoiceStatusUseCase, never()).requestSend(any())
        assertEquals(InvoiceStatus.SENT, applied.last().statusEnum())
    }

    @Test
    fun `confirming the override dialog with a blank reason shows an error and never transitions again`() {
        val invoice = dto(status = InvoiceStatus.IN_REVIEW)
        whenever(changeInvoiceStatusUseCase.transitionInvoice(eq(1L), eq(InvoiceStatus.SENT), anyOrNull()))
            .thenThrow(InvoiceFinalizationBlockedException(GateBlockReason.VALIDATION_UNAVAILABLE))
        actions().transitionAndRefresh(host, invoice, InvoiceStatus.SENT)
        val dialog = _get<Dialog>()

        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.finalize.override.confirm") }._click()

        verify(changeInvoiceStatusUseCase, times(1)).transitionInvoice(any(), any(), anyOrNull())
        val reasonField = dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.finalize.override.reasonLabel") }
        assertTrue(reasonField.isInvalid, "a blank override reason must be flagged on the field")
        assertEquals(UiTranslations.tr("invoiceDetail.finalize.override.reasonRequired"), reasonField.errorMessage)
    }

    // ── Stornieren gate + navigate ─────────────────────────────────────────────────────────────────

    @Test
    fun `Stornieren is not one of the STATUS_ACTIONS entries`() {
        // Storno creates a NEW invoice; it must never be reachable through
        // visibleStatusActionsFor/transitionAndRefresh like a real status transition.
        assertFalse(STATUS_ACTIONS.any { it.cssClass == "ch-btn--storno" })
    }

    @Test
    fun `buildButtons includes the Stornieren button only when canCreateCreditNote is true`() {
        val invoice = dto(status = InvoiceStatus.SENT)
        val withCreditNote = actions().buildButtons(host, invoice, canCreateCreditNote = true)
        val without = actions().buildButtons(host, invoice, canCreateCreditNote = false)

        assertTrue(withCreditNote.filterIsInstance<Button>().any { it.hasClassName("ch-btn--storno") })
        assertFalse(without.filterIsInstance<Button>().any { it.hasClassName("ch-btn--storno") })
    }

    @Test
    fun `confirming the Storno dialog reaches the navigate call to the new credit note's detail view`() {
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceDetailView::class.java)
        val invoice = dto(id = 7L, status = InvoiceStatus.SENT)
        val creditNote = dto(id = 8L, status = InvoiceStatus.CANCELLED)
        whenever(changeInvoiceStatusUseCase.createCreditNote(eq(7L), any(), anyOrNull())).thenReturn(creditNote)
        val ui = UI.getCurrent()!!

        actions().createCreditNoteAndNavigate(host, invoice)
        val dialog = _get<Dialog>()
        dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }.value = "Falsch berechnet"
        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        assertEquals("invoice/8", ui.internals.activeViewLocation.path)
    }

    @Test
    fun `confirming the storno dialog with a blank reason shows an error and never calls createCreditNote`() {
        val invoice = dto(status = InvoiceStatus.SENT)
        actions().createCreditNoteAndNavigate(host, invoice)
        val dialog = _get<Dialog>()

        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        verify(changeInvoiceStatusUseCase, never()).createCreditNote(any(), any(), anyOrNull())
        val reasonField = dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }
        assertTrue(reasonField.isInvalid, "a blank storno reason must be flagged on the field")
        assertEquals(UiTranslations.tr("invoiceDetail.createCreditNote.reasonRequired"), reasonField.errorMessage)
    }

    @Test
    fun `a storno reason with emoji or CJK is flagged in the dialog and never reaches createCreditNote`() {
        actions().createCreditNoteAndNavigate(host, dto(status = InvoiceStatus.SENT))
        val dialog = _get<Dialog>()
        val reasonField = dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }
        reasonField.value = "🐴 🔥 日 本 語"

        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        assertTrue(reasonField.isInvalid)
        assertTrue(reasonField.errorMessage.startsWith(UiTranslations.tr("validation.pdfText.unrenderable")), reasonField.errorMessage)
        assertTrue(dialog.isOpened, "the dialog stays open so the user can correct the text")
        verify(changeInvoiceStatusUseCase, never()).createCreditNote(any(), any(), anyOrNull())
    }

    @Test
    fun `an unrenderable English storno reason is flagged and never reaches createCreditNote`() {
        actions(multilingualTextsEnabled = true).createCreditNoteAndNavigate(host, dto(status = InvoiceStatus.SENT, documentLanguage = "en"))
        val dialog = _get<Dialog>()
        dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }.value = "Falsch berechnet"
        val reasonEnField = dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonEnLabel") }
        reasonEnField.value = "wrong 🔥"

        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        assertTrue(reasonEnField.isInvalid)
        verify(changeInvoiceStatusUseCase, never()).createCreditNote(any(), any(), anyOrNull())
    }

    @Test
    fun `cancelling the dialog calls neither createCreditNote nor changes the invoice status`() {
        val invoice = dto(status = InvoiceStatus.SENT)
        actions().createCreditNoteAndNavigate(host, invoice)
        val dialog = _get<Dialog>()

        dialog._get<Button> { text = UiTranslations.tr("app.cancel") }._click()

        verify(changeInvoiceStatusUseCase, never()).createCreditNote(any(), any(), anyOrNull())
        assertEquals(InvoiceStatus.SENT, invoice.statusEnum())
    }

    @Test
    fun `a StornoNotAllowed rejection shows the rejection's own message`() {
        val invoice = dto(status = InvoiceStatus.SENT)
        whenever(changeInvoiceStatusUseCase.createCreditNote(eq(1L), any(), anyOrNull()))
            .thenThrow(InvoiceStornoNotAllowedException(StornoRejection.ALREADY_CANCELLED, "INV-1"))
        actions().createCreditNoteAndNavigate(host, invoice)
        val dialog = _get<Dialog>()
        dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }.value = "Fehlerhafte Rechnung"

        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        assertTrue(getNotifications().isNotEmpty())
    }

    @Test
    fun `a generic exception from createCreditNote shows the generic save-failed error`() {
        whenever(changeInvoiceStatusUseCase.createCreditNote(eq(1L), any(), anyOrNull())).doThrow(RuntimeException("boom"))
        actions().createCreditNoteAndNavigate(host, dto(status = InvoiceStatus.SENT))
        val dialog = _get<Dialog>()
        dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }.value = "Fehlerhafte Rechnung"

        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        val text = notificationText(getNotifications().single())
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.error.saveFailed", UiTranslations.tr("message.error.generic"))), text)
        assertFalse(text.contains("boom"), "an unexpected exception's own text never reaches the toast: $text")
    }

    @Test
    fun `boundary - a storno failing without a message shows the save-failed text and closes the dialog`() {
        whenever(changeInvoiceStatusUseCase.createCreditNote(eq(1L), eq("Doppelt gebucht"), anyOrNull())).thenThrow(RuntimeException())

        actions().createCreditNoteAndNavigate(host, dto(status = InvoiceStatus.SENT))
        _get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }.value = "Doppelt gebucht"
        _get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        assertTrue(notificationText(getNotifications().single()).contains(UiTranslations.tr("invoiceDetail.error.saveFailed", "")))
    }

    @Test
    fun `confirming with a reason calls createCreditNote with that reason`() {
        val invoice = dto(id = 7L)
        val creditNote = dto(id = 8L, status = InvoiceStatus.CANCELLED)
        whenever(changeInvoiceStatusUseCase.createCreditNote(eq(7L), any(), anyOrNull())).thenReturn(creditNote)
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceDetailView::class.java)

        actions().createCreditNoteAndNavigate(host, invoice)
        val dialog = _get<Dialog>()
        dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }.value = "Falsch berechnet"
        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        verify(changeInvoiceStatusUseCase).createCreditNote(7L, "Falsch berechnet")
    }

    // ── The English reason field, gated by flag AND document_language == "en" ────

    private fun englishReasonFields(dialog: Dialog): List<TextArea> =
        flatten(dialog).filterIsInstance<TextArea>()
            .filter { it.label == UiTranslations.tr("invoiceDetail.createCreditNote.reasonEnLabel") }

    @Test
    fun `the English reason field is present when the flag is on and the document is English`() {
        actions(multilingualTextsEnabled = true).createCreditNoteAndNavigate(host, dto(documentLanguage = "en"))

        assertEquals(1, englishReasonFields(_get<Dialog>()).size, "the field must exist, exactly once")
    }

    @Test
    fun `the English reason field is absent, not merely hidden, when the flag is off`() {
        actions(multilingualTextsEnabled = false).createCreditNoteAndNavigate(host, dto(documentLanguage = "en"))

        assertTrue(englishReasonFields(_get<Dialog>()).isEmpty(), "flag off: no field, not just an invisible one")
    }

    @Test
    fun `the English reason field is absent, not merely hidden, for a German document even with the flag on`() {
        actions(multilingualTextsEnabled = true).createCreditNoteAndNavigate(host, dto(documentLanguage = "de"))

        assertTrue(englishReasonFields(_get<Dialog>()).isEmpty(), "a German document has no use for the English field")
    }

    @Test
    fun `confirming with both reasons filled calls createCreditNote with the English reason too`() {
        val invoice = dto(id = 9L, documentLanguage = "en")
        val creditNote = dto(id = 10L, status = InvoiceStatus.CANCELLED)
        whenever(changeInvoiceStatusUseCase.createCreditNote(eq(9L), any(), anyOrNull())).thenReturn(creditNote)
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceDetailView::class.java)

        actions(multilingualTextsEnabled = true).createCreditNoteAndNavigate(host, invoice)
        val dialog = _get<Dialog>()
        dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }.value = "Kunde storniert."
        englishReasonFields(dialog).single().value = "Customer cancelled."
        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        verify(changeInvoiceStatusUseCase).createCreditNote(9L, "Kunde storniert.", "Customer cancelled.")
    }

    @Test
    fun `confirming with the English reason left blank calls createCreditNote with null, not an empty string`() {
        val invoice = dto(id = 11L, documentLanguage = "en")
        val creditNote = dto(id = 12L, status = InvoiceStatus.CANCELLED)
        whenever(changeInvoiceStatusUseCase.createCreditNote(eq(11L), any(), anyOrNull())).thenReturn(creditNote)
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceDetailView::class.java)

        actions(multilingualTextsEnabled = true).createCreditNoteAndNavigate(host, invoice)
        val dialog = _get<Dialog>()
        dialog._get<TextArea> { label = UiTranslations.tr("invoiceDetail.createCreditNote.reasonLabel") }.value = "Kunde storniert."
        // The English field exists but is left empty — testmatrix.md's third column.
        dialog._get<Button> { text = UiTranslations.tr("invoiceDetail.createCreditNote.confirm") }._click()

        verify(changeInvoiceStatusUseCase).createCreditNote(11L, "Kunde storniert.", null)
    }
}
