package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.Shortcuts
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.invoice.port.inbound.InvoiceFormModuleQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.spi.form.FormContext
import de.littletrotter.spi.form.FormSlot
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ModuleFieldSection
import de.littletrotter.ui.i18n.UiTranslations
import java.util.Locale

/**
 * The guided-mode step rail: rail buttons, the shared "Weiter" button (one shared button re-appended under whichever step is active, except the last;
 * Enter in a text field of the active step clicks it too), the step content container and the
 * progress bar, kept together because they update each other (active index, squares, progress)
 * whenever a step becomes active.
 */
private class GuidedEinvoiceStepRail(
    private val stepDefs: List<Pair<EInvoiceStep, Component>>,
    stepLabel: (EInvoiceStep) -> String,
    weiterLabel: String,
) {
    private var activeIndex = 0
    private val stepSquares = mutableListOf<Span>()

    /** Fires after [showStep] switches the visible step (guided PRÜFUNG needs a fresh render
     *  every time it becomes active, not just on live field changes). */
    var onStepShown: ((EInvoiceStep) -> Unit)? = null

    val stepButtons: List<NativeButton> = stepDefs.mapIndexed { index, (step, _) -> buildStepButton(index, step, stepLabel) }
    val stepRail: Component = Div(*stepButtons.toTypedArray()).apply { addClassName("einvoice-step-rail") }
    val stepContentContainer: Div = Div().apply { addClassName("einvoice-step-content") }

    private val weiterBtn: Button = AppButtons.primary(weiterLabel) {
        if (activeIndex < stepDefs.lastIndex) showStep(activeIndex + 1)
    }
    val weiterRow: Component = ActionBar(weiterBtn).apply {
        isPadding = false
        addClassName("einvoice-step-actions")
    }

    private val progressFill = Div().apply { addClassName("einvoice-progress-fill") }
    val progressTrack: Component = Div(progressFill).apply {
        addClassName("einvoice-progress-track")
        element.setAttribute("role", "progressbar")
    }
    val progressLabel: Span = Span().apply { addClassName("einvoice-progress-label") }

    private fun buildStepButton(index: Int, step: EInvoiceStep, stepLabel: (EInvoiceStep) -> String): NativeButton {
        val square = statusSquare(false).apply { addClassName("einvoice-step-square") }
        stepSquares += square
        return NativeButton().apply {
            addClassName("einvoice-step-btn")
            add(
                Span((index + 1).toString().padStart(2, '0')).apply { addClassName("einvoice-step-num") },
                Span(stepLabel(step)).apply { addClassName("bau-eyebrow") },
                square,
            )
            addClickListener { showStep(index) }
        }
    }

    fun showStep(index: Int) {
        activeIndex = index
        stepContentContainer.removeAll()
        stepContentContainer.add(stepDefs[index].second)
        if (index < stepDefs.lastIndex) stepContentContainer.add(weiterRow)
        stepButtons.forEachIndexed { i, btn ->
            btn.element.classList.set("einvoice-step-btn--active", i == index)
            if (i == index) btn.element.setAttribute("aria-current", "step") else btn.element.removeAttribute("aria-current")
        }
        onStepShown?.invoke(stepDefs[index].first)
    }

    fun stepIndexOf(step: EInvoiceStep): Int = stepDefs.indexOfFirst { it.first == step }

    fun isActiveStep(step: EInvoiceStep): Boolean = stepDefs.getOrNull(activeIndex)?.first == step

    // Bound only to TextField/DatePicker, never to a ComboBox: an open ComboBox overlay must keep
    // Vaadin's own Enter handling (select the highlighted option), not have it hijacked.
    fun bindEnter(fields: List<Component>) {
        fields.forEach { field ->
            Shortcuts.addShortcutListener(field, com.vaadin.flow.server.Command { weiterBtn.click() }, Key.ENTER).listenOn(field)
        }
    }

    fun refreshProgress(statuses: List<EInvoiceInputStatus>, progressText: (done: Int, total: Int) -> String) {
        val total = statuses.size
        val done = total - statuses.count { it.missing }
        val text = progressText(done, total)
        progressLabel.text = text
        progressTrack.element.setAttribute("aria-valuenow", done.toString())
        progressTrack.element.setAttribute("aria-valuemin", "0")
        progressTrack.element.setAttribute("aria-valuemax", total.toString())
        progressTrack.element.setAttribute("aria-label", text)
        val pct = if (total > 0) (done * 100.0 / total) else 100.0
        progressFill.element.style.set("--lt-bar-w", "$pct%")
    }

    fun refreshStepSquares(missingCountFor: (EInvoiceStep) -> Int) {
        stepDefs.forEachIndexed { index, (step, _) ->
            val filled = missingCountFor(step) == 0
            stepSquares[index].element.classList.set("bau-status-square--filled", filled)
            stepSquares[index].element.classList.set("bau-status-square--open", !filled)
        }
    }
}

private fun reviewRow(label: String, ok: Boolean): Component = HorizontalLayout(statusSquare(ok), Span(label)).apply {
    isPadding = false
    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
    addClassName("einvoice-review-row")
}

private fun buildStepDefs(
    stepList: List<EInvoiceStep>,
    referencesStepContent: Component,
    profileFieldsStepContent: Component?,
    reviewStepContent: Component,
): List<Pair<EInvoiceStep, Component>> = stepList.mapNotNull { step ->
    when (step) {
        EInvoiceStep.REFERENCES -> step to referencesStepContent
        EInvoiceStep.PROFILE_FIELDS -> profileFieldsStepContent?.let { step to it }
        EInvoiceStep.REVIEW -> step to reviewStepContent
    }
}

private fun stepLabelKey(step: EInvoiceStep): String = when (step) {
    EInvoiceStep.REFERENCES -> "invoiceDetail.entry.einvoice.step.references"
    EInvoiceStep.PROFILE_FIELDS -> "invoiceDetail.entry.einvoice.step.profileFields"
    EInvoiceStep.REVIEW -> "invoiceDetail.entry.einvoice.step.review"
}

private fun stepMissingCount(step: EInvoiceStep, statuses: List<EInvoiceInputStatus>): Int = when (step) {
    EInvoiceStep.REFERENCES -> statuses.count { it.id == BUYER_REFERENCE_INPUT_ID && it.missing }
    EInvoiceStep.PROFILE_FIELDS -> statuses.count { it.id != BUYER_REFERENCE_INPUT_ID && it.missing }
    EInvoiceStep.REVIEW -> statuses.count { it.missing }
}

private class EinvoiceReviewStep(val content: Div, val refresh: () -> Unit)

/**
 * The guided-mode content of the entry pane's "E-Rechnung" tab: the step-01 REFERENZEN form (with the InvoiceFormModule references section attached), the optional
 * step-02 PROFILFELDER (omitted without an effective profile) and the read-only step-03 PRÜFUNG,
 * composed with [GuidedEinvoiceStepRail]. Reads [InvoiceDetailQueryPort.findB2bComplianceFindings]
 * for the review step's compliance findings through that narrow query port.
 */
internal class EinvoiceGuidedSteps(
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val invoiceFormModuleRegistry: InvoiceFormModuleQueryPort,
    private val host: Component,
) {
    private fun tr(key: String, vararg params: Any) = UiTranslations.tr(host, key, *params)

    // ── Step 01 — REFERENZEN ──
    private fun buildGuidedReferencesStep(ctx: EinvoiceTabContext): Pair<Component, ModuleFieldSection> {
        val referencesForm = referencesFormLayout(ctx.fields)
        val moduleFieldSection = ModuleFieldSection(
            slot = FormSlot.OVERVIEW_REFERENCES,
            modules = invoiceFormModuleRegistry.modules(),
            ctx = FormContext(currentInvoiceDto(ctx.current), ctx.current.isReadOnly(), Locale.GERMAN),
            binder = ctx.binder,
        ).also { it.attachTo(referencesForm) }
        return Div(referencesForm) to moduleFieldSection
    }

    // ── Step 02 — PROFILFELDER (omitted entirely without an effective profile) ──
    private fun buildProfileFieldsStep(ctx: EinvoiceTabContext): Component? = if (ctx.trigger == EInvoiceGuidedTrigger.PROFILE) {
        Div(
            FormLayout(ctx.fields.b2bProfileBox).apply { setResponsiveSteps(FormLayout.ResponsiveStep("0", 1)) },
            ctx.fields.b2bHeaderSection,
        )
    } else {
        null
    }

    // ── Step 03 — PRÜFUNG (read-only review) ──
    private fun buildReviewStep(ctx: EinvoiceTabContext): EinvoiceReviewStep {
        val reviewStepContent = Div()
        fun refresh() {
            reviewStepContent.removeAll()
            val liveBlock = Div().apply { addClassName("einvoice-review-block") }
            ctx.currentStatuses().forEach { status -> liveBlock.add(reviewRow(status.label, !status.missing)) }
            val findingsBlock = Div().apply { addClassName("einvoice-review-block") }
            findingsBlock.add(
                Span(tr("invoiceDetail.entry.einvoice.findings.savedState")).apply { addClassNames("fs-08", "text-muted") },
            )
            invoiceDetailQueryPort.findB2bComplianceFindings(ctx.current.id).forEach { finding ->
                val label = b2bComplianceFindingLabel(
                    finding,
                    ifEmptySuffix = { conditionBtCode -> tr("compliance.b2b.conditionalIfEmptySuffix", conditionBtCode) },
                ) { positionId, definition ->
                    tr("invoiceDetail.summary.checklist.b2bLinePrefix", positionId, definition)
                }
                findingsBlock.add(reviewRow(label, false))
            }
            reviewStepContent.add(liveBlock, findingsBlock)
        }
        return EinvoiceReviewStep(reviewStepContent, ::refresh)
    }

    private fun buildGuidedHeader(ctx: EinvoiceTabContext): Component {
        val subtitleText = if (ctx.trigger == EInvoiceGuidedTrigger.PROFILE) {
            tr("invoiceDetail.entry.einvoice.profile", ctx.effectiveB2bProfile?.name.orEmpty())
        } else {
            tr("invoiceDetail.entry.einvoice.xrechnung")
        }
        return HorizontalLayout(
            Span(tr("invoiceDetail.entry.einvoice.title")).apply { addClassName("bau-eyebrow") },
            Span(subtitleText),
        ).apply {
            isPadding = false
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            addClassNames("einvoice-guided-header", "lt-gap-050")
        }
    }

    private fun buildGuidedRevealField(
        tab2Fields: Set<Component>,
        b2bProfileBox: ComboBox<B2bProfileDto>,
        rail: GuidedEinvoiceStepRail,
    ): (Component) -> Boolean = { field ->
        if (field !in tab2Fields) {
            false
        } else {
            val targetStep = if (field === b2bProfileBox) EInvoiceStep.PROFILE_FIELDS else EInvoiceStep.REFERENCES
            val stepIndex = rail.stepIndexOf(targetStep)
            if (stepIndex >= 0) rail.showStep(stepIndex)
            true
        }
    }

    fun build(ctx: EinvoiceTabContext): EinvoiceTabBuild {
        val stepList = einvoiceGuidedSteps(ctx.trigger)
        val (referencesStepContent, moduleFieldSection) = buildGuidedReferencesStep(ctx)
        val profileFieldsStepContent = buildProfileFieldsStep(ctx)
        val reviewStep = buildReviewStep(ctx)
        val stepDefs = buildStepDefs(stepList, referencesStepContent, profileFieldsStepContent, reviewStep.content)

        val rail = GuidedEinvoiceStepRail(stepDefs, { step -> tr(stepLabelKey(step)) }, tr("invoiceDetail.entry.einvoice.next"))
        rail.onStepShown = { step -> if (step == EInvoiceStep.REVIEW) reviewStep.refresh() }
        rail.bindEnter(
            listOf(
                ctx.fields.buyerReferenceField, ctx.fields.purchaseOrderReferenceField,
                ctx.fields.orderReferenceField, ctx.fields.orderReferenceDateField,
            ),
        )
        profileFieldsStepContent?.let { rail.bindEnter(ctx.fields.b2bHeaderSection.enterAdvanceCandidates()) }

        rail.showStep(0)
        rail.refreshProgress(ctx.currentStatuses()) { done, total -> tr("invoiceDetail.entry.einvoice.progress", done.toString(), total.toString()) }
        rail.refreshStepSquares { step -> stepMissingCount(step, ctx.currentStatuses()) }

        val header = buildGuidedHeader(ctx)
        val progressBlock = Div(rail.progressTrack, rail.progressLabel).apply { addClassName("einvoice-progress") }
        val body = Div(rail.stepRail, rail.stepContentContainer).apply { addClassName("einvoice-guided-body") }
        val content: Component = Div(header, progressBlock, body).apply { addClassName("einvoice-guided") }

        val revealField = buildGuidedRevealField(ctx.tab2Fields, ctx.fields.b2bProfileBox, rail)

        return EinvoiceTabBuild(content, revealField, moduleFieldSection) {
            rail.refreshProgress(ctx.currentStatuses()) { done, total -> tr("invoiceDetail.entry.einvoice.progress", done.toString(), total.toString()) }
            rail.refreshStepSquares { step -> stepMissingCount(step, ctx.currentStatuses()) }
            if (rail.isActiveStep(EInvoiceStep.REVIEW)) reviewStep.refresh()
        }
    }
}
