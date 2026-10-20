package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Focusable
import com.vaadin.flow.component.HasLabel
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.InvoiceHeaderFormState
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.B2bProfileFieldsSection
import de.littletrotter.ui.components.freetext.HtmlFreeTextEditor
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.isConcurrencyConflict
import de.littletrotter.ui.view.logRejection
import org.jboss.logging.Logger

private val log = Logger.getLogger(InvoiceEntrySaveFlow::class.java)

/** Focuses through the component's own focus contract where it has one (the free-text editor
 *  focuses its inner surface), else through the element's client-side focus. */
private fun focusComponent(component: Component) {
    (component as? Focusable<*>)?.focus() ?: component.element.callJsFunction("focus")
}

/**
 * The entry pane's validate → write bean → persist → report flow, extracted from `buildEntryPane`.
 * Writes header changes via [EditInvoiceHeaderUseCase], its only application dependency.
 */
internal class InvoiceEntrySaveFlow(private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase) {

    // ─── Validation-issue display ───

    private fun renderValidationIssues(host: Component, nav: EntryTabNavigation, issues: List<HeaderIssue>) {
        fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)
        val validationSummary = nav.validationSummary
        validationSummary.removeAll()
        validationSummary.isVisible = issues.isNotEmpty()
        if (issues.isEmpty()) return
        validationSummary.add(
            Span(tr("invoiceDetail.entry.validation.summary", issues.size.toString())).apply {
                addClassNames("font-weight-semibold")
            },
        )
        issues.forEach { issue -> validationSummary.add(buildValidationIssueButton(issue, nav)) }
    }

    private fun buildValidationIssueButton(issue: HeaderIssue, nav: EntryTabNavigation): Button {
        val text = "${issue.section} · ${issue.fieldLabel}: ${issue.reason}"
        return AppButtons.tertiary(text) {
            issue.tab?.let { nav.tabSheet.selectedTab = it }
            issue.field?.let { field ->
                nav.reveal(field)
                focusComponent(field)
            }
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            setWidthFull()
            addClassName("lt-justify-start")
        }
    }

    private fun flashClientSideIssueFields(issues: List<ClientHeaderIssue>) {
        issues.map { it.field }.distinct().forEach { field ->
            when (field) {
                is DatePicker -> UiNotifications.errorOn(field, null)
                is ComboBox<*> -> UiNotifications.errorOn(field, null)
            }
        }
    }

    /** [ClientHeaderIssue.field]/[ClientHeaderIssue.tab] are never null — every client-side issue
     *  producer already points at exactly one field and tab, unlike a server-side [HeaderIssue]
     *  (a generic rejection may have neither) — so there is nothing left to null-check here. */
    private fun focusFirstIssue(issues: List<ClientHeaderIssue>, tabSheet: TabSheet) {
        val issue = issues.first()
        tabSheet.selectedTab = issue.tab
        issue.field.element.callJsFunction("focus")
    }

    private fun flashFields(fields: List<Component>, message: String, nav: EntryTabNavigation) =
        fields.forEach { flashField(it, message, nav) }

    // No `else` arm: a `when` used as a statement need not be exhaustive, and the only components
    // that arrive here are the date pickers and party boxes `fieldsFor` maps a backend error to,
    // plus the text inputs and the free-text editor a rejected binder field resolves to.
    private fun flashField(field: Component, message: String, nav: EntryTabNavigation) {
        nav.reveal(field)
        when (field) {
            is DatePicker -> UiNotifications.errorOn(field, message)
            is ComboBox<*> -> UiNotifications.errorOn(field, message)
            is TextField -> UiNotifications.errorOn(field, message)
            is TextArea -> UiNotifications.errorOn(field, message)
            is HtmlFreeTextEditor -> UiNotifications.errorOn(field, message)
        }
    }

    /** Which entry-pane field(s) the given backend validation error implicates, if any live here. */
    private fun fieldsFor(error: InvoiceValidationError, dateFields: EntryDateFields, partyFields: EntryPartyFields): List<Component> = when (error) {
        InvoiceValidationError.SENDER_PROFILE_MISSING -> listOf(partyFields.senderBox)
        InvoiceValidationError.RECIPIENT_MISSING -> listOf(partyFields.recipientBox)
        InvoiceValidationError.SERVICE_PERIOD_END_BEFORE_START -> listOf(dateFields.serviceFromField, dateFields.serviceToField)
        InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE -> listOf(dateFields.invoiceDateField, dateFields.dueDateField)
        InvoiceValidationError.PAYMENT_TERM_DAYS_NEGATIVE -> listOf(partyFields.paymentTermBox)
        // Thrown by InvoiceItem.applySellerCountryTaxVariant (re-derivation on a
        // seller-country switch) and surfaced here, not on the item that's actually missing a variant:
        // at this point the save has already been rolled back, so flashing the field that caused it
        // (this ComboBox) is more useful than pointing at an item list position.
        InvoiceValidationError.ITEM_ARTICLE_TAX_VARIANT_MISSING -> listOf(partyFields.sellerVatCountryBox)
        else -> emptyList()
    }

    // ─── Save flow ───

    fun buildSaveButton(host: Component, ctx: EntrySaveContext, onInvoiceUpdated: (InvoiceDetailDto) -> Unit): Button {
        lateinit var saveButton: Button
        saveButton = AppButtons.primary(UiTranslations.tr(host, "app.save")) {
            onSaveClicked(host, ctx, saveButton, onInvoiceUpdated)
        }
        return saveButton
    }

    private fun onSaveClicked(host: Component, ctx: EntrySaveContext, saveButton: Button, onInvoiceUpdated: (InvoiceDetailDto) -> Unit) {
        val hard = requiredFieldIssues(host, ctx) + serviceDateGuardIssue(host, ctx)
        if (hard.isNotEmpty()) {
            val issues = hard + missingB2bHeaderIssues(host, ctx)
            flashClientSideIssueFields(issues)
            renderValidationIssues(host, ctx.nav, issues.map { it.toHeaderIssue() })
            focusFirstIssue(issues, ctx.nav.tabSheet)
            return
        }
        // Missing mandatory E-Rechnung fields never block a save (PO 2026-10-01): warn first, persist
        // everything entered once acknowledged. Issue/send-time validation is unchanged.
        val missing = missingB2bHeaderIssues(host, ctx)
        if (missing.isEmpty()) {
            trySaveHeader(host, ctx, saveButton, onInvoiceUpdated)
            return
        }
        confirmSaveWithMissingEinvoiceFields(host, missing) {
            renderValidationIssues(host, ctx.nav, emptyList())
            trySaveHeader(host, ctx, saveButton, onInvoiceUpdated)
        }
    }

    private fun confirmSaveWithMissingEinvoiceFields(host: Component, missing: List<ClientHeaderIssue>, onConfirm: () -> Unit) {
        fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)
        val list = missing.joinToString("\n") { "• ${it.fieldLabel}" }
        ConfirmDialog(
            tr("invoiceDetail.entry.saveWarning.title"),
            tr("invoiceDetail.entry.saveWarning.text", list),
            tr("invoiceDetail.entry.saveWarning.confirm"),
        ) { onConfirm() }.apply {
            setCancelable(true)
            setCancelText(tr("app.cancel"))
            addClassName("lt-pre-line")
            open()
        }
    }

    private fun requiredFieldIssues(host: Component, ctx: EntrySaveContext): List<ClientHeaderIssue> {
        fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)
        val issues = mutableListOf<ClientHeaderIssue>()
        if (ctx.fields.dateFields.invoiceDateField.value == null) {
            issues += ClientHeaderIssue(
                tr("invoiceDetail.entry.section.dates"), tr("invoiceDetail.invoiceDate"),
                tr("validation.invoice.invoiceDate.required"), ctx.fields.dateFields.invoiceDateField, ctx.nav.invoiceTabRef,
            )
        }
        if (ctx.fields.partyFields.senderBox.value == null) {
            issues += ClientHeaderIssue(
                tr("invoiceDetail.entry.section.parties"), tr("invoiceDetail.card.sender"),
                tr("validation.invoice.senderProfileMissing"), ctx.fields.partyFields.senderBox, ctx.nav.invoiceTabRef,
            )
        }
        if (ctx.fields.partyFields.recipientBox.value == null) {
            issues += ClientHeaderIssue(
                tr("invoiceDetail.entry.section.parties"), tr("invoiceDetail.summary.name"),
                tr("validation.invoice.recipientMissing"), ctx.fields.partyFields.recipientBox, ctx.nav.invoiceTabRef,
            )
        }
        return issues
    }

    private fun serviceDateGuardIssue(host: Component, ctx: EntrySaveContext): List<ClientHeaderIssue> {
        val guardErrorKey = validateDateGuard(ctx.fields.dateFields.serviceFromField.value, ctx.fields.dateFields.serviceToField.value) ?: return emptyList()
        val message = UiTranslations.tr(host, guardErrorKey)
        return listOf(
            ClientHeaderIssue(
                UiTranslations.tr(host, "invoiceDetail.entry.section.dates"),
                "${UiTranslations.tr(host, "invoiceDetail.servicePeriodFrom")} / ${UiTranslations.tr(host, "invoiceDetail.servicePeriodTo")}",
                message, ctx.fields.dateFields.serviceFromField, ctx.nav.invoiceTabRef,
            ),
        )
    }

    private fun missingB2bHeaderIssues(host: Component, ctx: EntrySaveContext): List<ClientHeaderIssue> =
        einvoiceInputStatuses(
            EInvoiceInputStatusQuery(
                guidedModeTriggered = ctx.b2b.guidedTrigger != EInvoiceGuidedTrigger.NONE,
                buyerReferenceLabel = UiTranslations.tr(host, "invoiceDetail.buyerReference"),
                buyerReference = ctx.fields.buyerReferenceField.value,
                mandatoryB2bHeaderFields = ctx.b2b.mandatoryHeaderFields,
                b2bHeaderFieldValues = ctx.b2b.b2bHeaderSection.displayedValues(),
            ),
        ).filter { it.missing }.map { missing -> missingB2bHeaderIssue(host, missing, ctx) }

    private fun missingB2bHeaderIssue(host: Component, missing: EInvoiceInputStatus, ctx: EntrySaveContext): ClientHeaderIssue {
        val field = if (missing.id == BUYER_REFERENCE_INPUT_ID) {
            ctx.fields.buyerReferenceField
        } else {
            // mandatoryHeaderFields (the source of every non-buyer-reference `missing.id` here) is
            // built by mandatoryB2bHeaderFields() from exactly the btCodes present in this same
            // b2bHeaderSection's own currentValues() keys, i.e. the same `widgets` list componentFor
            // searches — so a match is guaranteed for the lifetime of one entry-pane build.
            checkNotNull(ctx.b2b.b2bHeaderSection.componentFor(missing.id)) {
                "No rendered B2B field widget for mandatory code ${missing.id}"
            }
        }
        return ClientHeaderIssue(
            UiTranslations.tr(host, "invoiceDetail.entry.tab.einvoice"), missing.label, UiTranslations.tr(host, "invoiceDetail.entry.validation.required"),
            field, ctx.nav.einvoiceTabRef,
        )
    }

    /** One issue per rejected binder field, marked on the field itself and listed in the summary, so
     *  a rejection is never reduced to a generic message with nothing marked. A bean-level error
     *  without any field keeps the generic issue. */
    private fun reportRejectedFields(host: Component, ctx: EntrySaveContext) {
        fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)
        val nav = ctx.nav
        val formLabel = tr("invoiceDetail.entry.validation.form")
        val correctMarked = tr("invoiceDetail.entry.validation.correctMarked")
        fun sectionOf(tab: Tab?): String = tr(
            when (tab) {
                nav.einvoiceTabRef -> "invoiceDetail.entry.tab.einvoice"
                nav.notesTabRef -> "invoiceDetail.entry.tab.notes"
                else -> "invoiceDetail.entry.tab.invoice"
            },
        )
        val issues = ctx.binder.validate().fieldValidationErrors.map { status ->
            val component = (status.field as? FreeTextEditorField)?.editor as? Component ?: status.field as? Component
            val tab = component?.let(nav::tabFor)
            HeaderIssue(
                sectionOf(tab),
                (component as? HasLabel)?.label?.takeIf { it.isNotBlank() } ?: formLabel,
                // Vaadin reports some rejections with an empty text; an issue must never end in a bare colon.
                status.message.filter { it.isNotBlank() }.orElse(correctMarked),
                component,
                tab,
            )
        }
        if (issues.isEmpty()) {
            renderValidationIssues(
                host,
                nav,
                listOf(
                    HeaderIssue(
                        tr("invoiceDetail.entry.tab.invoice"),
                        formLabel,
                        correctMarked,
                        null,
                        nav.invoiceTabRef,
                    ),
                ),
            )
            return
        }
        renderValidationIssues(host, nav, issues)
        issues.forEach { issue -> issue.field?.let { flashField(it, issue.reason, nav) } }
        issues.first().tab?.let { nav.tabSheet.selectedTab = it }
        issues.first().field?.let(::focusComponent)
    }

    private fun trySaveHeader(host: Component, ctx: EntrySaveContext, saveButton: Button, onInvoiceUpdated: (InvoiceDetailDto) -> Unit) {
        try {
            if (!ctx.binder.writeBeanIfValid(ctx.formState)) {
                reportRejectedFields(host, ctx)
                return
            }
            val savedDto = editInvoiceHeaderUseCase.updateInvoiceHeader(buildUpdateInvoiceHeaderCommand(ctx))
            onInvoiceUpdated(savedDto)
            renderValidationIssues(host, ctx.nav, emptyList())
            UiNotifications.confirmOn(saveButton)
            ctx.onDone()
        } catch (e: Exception) {
            // Save-button click listener: must not let an exception escape. Logged with the invoice
            // id so a failed header save is discoverable.
            handleSaveFailure(host, e, ctx, saveButton)
        }
    }

    private fun buildUpdateInvoiceHeaderCommand(ctx: EntrySaveContext): UpdateInvoiceHeaderCommand {
        val formState = ctx.formState
        return UpdateInvoiceHeaderCommand(
            invoiceId = ctx.current.id,
            expectedVersion = ctx.current.version,
            invoiceDate = formState.invoiceDate,
            servicePeriodFrom = formState.servicePeriodFrom,
            servicePeriodTo = formState.servicePeriodTo,
            dueDate = formState.dueDate,
            freeText = formState.freeText,
            buyerReference = formState.buyerReference,
            purchaseOrderReference = formState.purchaseOrderReference,
            orderReference = formState.orderReference,
            orderReferenceDate = formState.orderReferenceDate,
            projectNumber = formState.projectNumber,
            currency = formState.currency,
            recipientId = formState.recipient?.id,
            senderProfileId = formState.senderProfile?.id,
            paymentTermId = formState.paymentTerm?.id,
            sellerVatRegistrationCountry = formState.sellerVatRegistrationCountry?.alpha2,
            // InvoiceFormatModule's ModuleFieldSection ENUM field (always rendered,
            // disabled+helperText when isInvoiceFormatEditable() is false) already wrote this back
            // onto `formState` via binder.writeBeanIfValid above, including a disabled field's
            // untouched GERMAN value; the command carries it through explicitly, same reasoning as
            // sellerVatRegistrationCountry (UpdateInvoiceHeaderCommand only persists fields it names).
            // updateHeader itself is still the authority that forces GERMAN when the domain doesn't
            // allow the change — this pass-through never bypasses that.
            invoiceFormat = formState.invoiceFormat.name,
            // Applied only when the recipient is not also changing in this same save
            // (InvoiceLifecycleService.updateHeader re-derives from the new recipient instead);
            // `null` clears an override back to no profile.
            b2bProfileId = ctx.b2b.b2bProfileBox.value?.id,
            invoiceNoteSubmitted = true,
            invoiceNote = formState.invoiceNote,
            consumerNoteSubmitted = true,
            consumerNote = formState.consumerNote,
            resetConsumerNoteToSellerDefault = ctx.fields.consumerNote.resetRequested,
            b2bFieldValues = ctx.b2b.b2bHeaderSection.takeIf { it.hasContent }?.currentValues(),
            effectiveB2bHeaderValues = ctx.b2b.b2bHeaderSection.displayedValues(),
            reverseChargeDecision = ctx.fields.reverseChargeDecisionBox.value,
            // Codes of the FREE/HEADER B2B term chips clicked this save; see the free-text block's
            // construction above.
            manuallyImportedB2bCodes = ctx.b2b.manuallyImportedB2bCodes.toSet(),
        )
    }

    private fun handleSaveFailure(host: Component, e: Exception, ctx: EntrySaveContext, saveButton: Button) {
        logRejection(log, "Failed to save header for invoiceId=${ctx.current.id}", e)
        val message = UiNotifications.resolveMessage(saveButton, e)
        UiNotifications.error(message)
        if (isConcurrencyConflict(e)) {
            ctx.onConflict()
        } else if (e is InvoiceValidationException) {
            handleValidationException(host, e, message, ctx)
        } else {
            renderValidationIssues(
                host,
                ctx.nav,
                listOf(HeaderIssue(UiTranslations.tr(host, "invoiceDetail.entry.validation.server"), UiTranslations.tr(host, "invoiceDetail.entry.validation.general"), message, null, null)),
            )
        }
    }

    private fun handleValidationException(host: Component, e: InvoiceValidationException, message: String, ctx: EntrySaveContext) {
        val targetFields = fieldsFor(e.error, ctx.fields.dateFields, ctx.fields.partyFields)
        flashFields(targetFields, message, ctx.nav)
        renderValidationIssues(
            host,
            ctx.nav,
            listOf(
                HeaderIssue(
                    UiTranslations.tr(host, "invoiceDetail.entry.validation.server"),
                    UiTranslations.tr(host, "invoiceDetail.entry.validation.form"),
                    message,
                    targetFields.firstOrNull(),
                    if (targetFields.any { ctx.nav.einvoiceTab.revealField(it) }) ctx.nav.einvoiceTabRef else ctx.nav.invoiceTabRef,
                ),
            ),
        )
    }

    fun performRequestExit(host: Component, binder: Binder<InvoiceHeaderFormState>, onDone: () -> Unit) {
        fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)
        if (!binder.hasChanges()) {
            onDone()
            return
        }
        ConfirmDialog(
            tr("invoiceDetail.overview.discardChanges.title"),
            tr("invoiceDetail.overview.discardChanges.text"),
            tr("invoiceDetail.overview.discardChanges.confirm"),
        ) { onDone() }.apply {
            setCancelable(true)
            setCancelText(tr("app.cancel"))
            open()
        }
    }
}

/** One validation issue surfaced by the entry pane, built either from a [ClientHeaderIssue] (via
 *  [ClientHeaderIssue.toHeaderIssue]) or directly by a server-side rejection path
 *  ([InvoiceEntrySaveFlow.trySaveHeader]/`handleSaveFailure`/`handleValidationException`):
 *  [field]/[tab] are genuinely `null` there for an issue with nothing local to flash (e.g. a
 *  general server error, or a validation exception [fieldsFor] does not map to any field). */
private data class HeaderIssue(val section: String, val fieldLabel: String, val reason: String, val field: Component?, val tab: Tab?)

/** One *client-side* pre-save validation issue (produced by [InvoiceEntrySaveFlow.onSaveClicked]
 *  before any request is sent): unlike a server-side [HeaderIssue], every producer here
 *  already knows exactly which field and tab to point at, so [field]/[tab] are never absent —
 *  [InvoiceEntrySaveFlow.focusFirstIssue]/`flashClientSideIssueFields` rely on that to skip the
 *  null-check a server-side issue would still need. */
private data class ClientHeaderIssue(val section: String, val fieldLabel: String, val reason: String, val field: Component, val tab: Tab) {
    fun toHeaderIssue(): HeaderIssue = HeaderIssue(section, fieldLabel, reason, field, tab)
}

/** The B2B-profile-derived context [InvoiceEntrySaveFlow.buildSaveButton]'s save flow needs,
 *  beyond the plain header fields already in [EntrySaveFields]. */
internal class EntrySaveB2bContext(
    val guidedTrigger: EInvoiceGuidedTrigger,
    val mandatoryHeaderFields: List<MandatoryB2bHeaderField>,
    val b2bHeaderSection: B2bProfileFieldsSection,
    val b2bProfileBox: ComboBox<B2bProfileDto>,
    val manuallyImportedB2bCodes: MutableSet<String>,
)

/** The plain (non-B2B) header fields the save flow validates and persists. */
internal class EntrySaveFields(
    val dateFields: EntryDateFields,
    val partyFields: EntryPartyFields,
    val buyerReferenceField: TextField,
    val reverseChargeDecisionBox: ReverseChargeField,
    val consumerNote: EntryConsumerNote,
)

/** Everything the save button's click flow (validate → write bean → persist → report) needs. */
internal class EntrySaveContext(
    val current: InvoiceDetailDto,
    val binder: Binder<InvoiceHeaderFormState>,
    val formState: InvoiceHeaderFormState,
    val fields: EntrySaveFields,
    val nav: EntryTabNavigation,
    val b2b: EntrySaveB2bContext,
    val onDone: () -> Unit,
    val onConflict: () -> Unit = {},
)
