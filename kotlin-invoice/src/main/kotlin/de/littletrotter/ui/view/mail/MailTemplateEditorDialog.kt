package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.menubar.MenuBarVariant
import com.vaadin.flow.component.shared.Tooltip
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailPlaceholderEntry
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewRequest
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateDraft
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTemplateVersion
import de.littletrotter.application.security.AccessDeniedException
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.components.freetext.ChipCandidate
import de.littletrotter.ui.components.freetext.FreeTextChips
import de.littletrotter.ui.components.freetext.HtmlFreeTextEditor
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications

/**
 * Edits one mail template: subject and formatted body on the left, the live preview on the right.
 *
 * Unknown or missing required placeholders are listed in red and lock "Speichern", so a template
 * that would send a raw `{{…}}` token or no document number never reaches the component. The
 * preview is filled with the catalogue's own sample values; the sister language and any history
 * version come through the preview and version ports, never from data invented here. "Test an mich"
 * and "Test an BCC" send what is stored (the BCC test the address typed now), which is why saving
 * comes first when the text changed.
 *
 * The dialog follows the stored template: after a save, reset or restore it shows the new current
 * version, and [onChanged] tells the list to reload.
 */
internal class MailTemplateEditorDialog(
    private val ports: MailTemplatePorts,
    private var template: MailTemplate,
    private val onChanged: () -> Unit,
) : AppDialog("", Size.L) {

    private var catalog: List<MailPlaceholderEntry> = loadCatalog()
    internal val subjectField = TextField(tr("mail.templates.editor.subject"))
    internal val bodyEditor = HtmlFreeTextEditor("", FreeTextMarkup.MAX_LENGTH).apply { addClassName("mail-template-body-editor") }
    internal val bccField = TextField(tr("mail.templates.editor.bcc"))
    internal val problemBox = Div().apply { addClassName("mail-template-problems") }
    internal val headerTitleText = Span().apply { addClassName("mail-template-title") }
    internal val headerSubtitle = Span().apply { addClassName("mail-template-subtitle") }
    internal val preview = MailTemplatePreviewPane(
        template.language,
        { MailTemplateRules.sampleValues(catalog) },
        { language -> contentFor(language) },
    )
    internal val saveButton = AppButtons.primary(tr("app.save")) { save() }
    internal val historyButton = AppButtons.icon(ActionIcons.HISTORY, tr("mail.templates.editor.history")) { openHistory() }
    internal val toolsMenu = MenuBar()
    internal val resetItem = toolsMenu.addItem(Icon(VaadinIcon.ELLIPSIS_DOTS_V))
        .also { it.subMenu.addItem(tr("mail.templates.editor.reset")) { confirmReset() } }
        .subMenu.items.single()
    internal val testMenu = MenuBar()
    internal val testToMeItem = testMenu.addItem(testMenuLabel()).subMenu
        .addItem(tr("mail.templates.editor.testToMe")) { guarded { sendTestToMe() } }
    internal val testBccItem = testMenu.items.single().subMenu
        .addItem(tr("mail.templates.editor.testBcc")) { guarded { sendTestToBcc() } }
    internal val cancelButton = AppButtons.tertiary(tr("app.cancel")) { close() }

    // One grid row per group (label column, then its chips) so each editor stays close to its chips.
    internal val bodyChipsBox = chipPanel()
    internal val bodyChipsToggle =
        AppButtons.icon(ActionIcons.PLACEHOLDERS, tr("mail.templates.editor.placeholders")) { bodyChipsBox.isVisible = !bodyChipsBox.isVisible }
    private val bodyLabelRow = Div(Span(tr("mail.templates.editor.body")).apply { addClassName("mail-template-field-label") }, bodyChipsToggle)
        .apply { addClassName("mail-template-field-label-row") }

    /** Hidden until asked for: eleven chips above a one-line field would dwarf it. */
    internal val subjectChipsBox = chipPanel().apply { isVisible = false }
    internal val subjectChipsToggle =
        AppButtons.icon(ActionIcons.PLACEHOLDERS, tr("mail.templates.editor.placeholders")) { subjectChipsBox.isVisible = !subjectChipsBox.isVisible }

    /** Every chip box sits directly above the element it inserts into. */
    internal val editColumn: VerticalLayout = buildEditColumn()

    init {
        isCloseOnOutsideClick = false
        subjectField.setWidthFull()
        bccField.setWidthFull()
        subjectField.valueChangeMode = ValueChangeMode.EAGER
        subjectField.addValueChangeListener { onInput() }
        bodyEditor.setWidthFull()
        bodyEditor.addValueChangeListener { onInput() }
        configureButtons()
        val layout = FormLayout(editColumn, previewColumn()).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(TWO_COLUMN_MIN_WIDTH, 2))
        }
        add(layout)
        header.add(Div(headerTitleText, headerSubtitle).apply { addClassName("mail-template-header-text") }, historyButton, toolsMenu)
        footer(cancelButton, saveButton)
        footer.addComponentAsFirst(testMenu)
        show(template)
    }

    private fun buildEditColumn(): VerticalLayout =
        VerticalLayout(bccField, subjectChipsBox, subjectField, bodyLabelRow, bodyChipsBox, bodyEditor, problemBox).apply {
            isPadding = false
            isSpacing = true
        }

    /** Sticky so the mail stays in view while a long editor column scrolls. */
    private fun previewColumn(): VerticalLayout = VerticalLayout(preview).apply {
        isPadding = false
        addClassName("lt-sticky-top")
    }

    private fun configureButtons() {
        subjectField.suffixComponent = subjectChipsToggle
        clearEditorHelper()
        val toolsLabel = tr("mail.templates.editor.tools")
        toolsMenu.addThemeVariants(MenuBarVariant.LUMO_TERTIARY, MenuBarVariant.LUMO_ICON)
        toolsMenu.element.setAttribute("aria-label", toolsLabel)
        Tooltip.forComponent(toolsMenu).withText(toolsLabel)
        testMenu.addThemeVariants(MenuBarVariant.LUMO_TERTIARY)
        testMenu.addClassName("lt-margin-inline-end-auto")
        bccField.placeholder = tr("mail.templates.editor.bccPlaceholder")
        bccField.valueChangeMode = ValueChangeMode.EAGER
        bccField.addValueChangeListener { syncBccItem() }
    }

    /** The mail body needs no formatting hint of its own; the counter beside it stays. */
    private fun clearEditorHelper() {
        bodyEditor.element.children
            .flatMap { it.children }
            .filter { it.classList.contains("lt-html-freetext-editor__helper") }
            .forEach { it.text = "" }
    }

    private fun testMenuLabel(): HorizontalLayout =
        HorizontalLayout(Icon(ActionIcons.TEST_MAIL), Span(tr("mail.templates.editor.testMenu"))).apply {
            isPadding = false
            isSpacing = true
            addClassName("lt-inline-center")
        }

    /** The menu names the address it would write to, so nobody has to remember what the BCC field holds. */
    private fun syncBccItem() {
        val address = bccField.value.trim()
        testBccItem.isEnabled = address.isNotEmpty()
        testBccItem.text =
            if (address.isEmpty()) tr("mail.templates.editor.testBcc") else tr("mail.templates.editor.testBccTo", address)
    }

    /** Loads [stored] into every field and header; the single place the dialog adopts a new current version. */
    private fun show(stored: MailTemplate) {
        template = stored
        headerTitleText.text = tr("mail.templates.editor.title", tr("mail.journal.kind.${stored.kind.name}"), stored.language.name)
        headerSubtitle.text = tr("mail.templates.editor.version", stored.version) + " · " + originLabel(stored)
        subjectField.value = stored.subject
        bodyEditor.value = stored.body
        bccField.value = stored.bccAddress.orEmpty()
        syncBccItem()
        rebuildChips()
        onInput()
    }

    private fun originLabel(stored: MailTemplate): String =
        tr(if (stored.seededDefault) "mail.templates.origin.standard" else "mail.templates.origin.custom")

    private fun loadCatalog(): List<MailPlaceholderEntry> =
        try {
            ports.catalog.placeholders(template.kind)
        } catch (e: MailDispatchException) {
            // Without the catalogue the chips and the placeholder check have nothing to work with; the
            // user can still read and cancel, and saving stays locked because nothing can be verified.
            UiNotifications.error(tr("mail.templates.loadFailed", e.message.orEmpty()))
            emptyList()
        }

    /** The chip's visible name: the translated placeholder, or the raw key when the catalogue is ahead of the bundle. */
    internal fun placeholderLabel(key: String): String =
        UiTranslations.trOrNull(null, "mail.templates.placeholder.$key") ?: key

    private fun candidateOf(entry: MailPlaceholderEntry): ChipCandidate {
        val name = placeholderLabel(entry.key)
        return ChipCandidate(name, name, MailPlaceholderText.token(entry.key), MailTemplateRules.groupOf(entry).name)
    }

    private fun chipPanel(): VerticalLayout =
        VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            addClassName("mail-template-chip-panel")
        }

    private fun rebuildChips() {
        fillChipPanel(bodyChipsBox) { bodyEditor.insertAtCursor(it.insertText) }
        fillChipPanel(subjectChipsBox) { subjectField.value += it.insertText }
    }

    /** One grid row per non-empty group: a fixed label column and the group's chips beside it. */
    private fun fillChipPanel(panel: VerticalLayout, onInsert: (ChipCandidate) -> Unit) {
        panel.removeAll()
        MailPlaceholderGroup.entries.forEach { group ->
            val inGroup = catalog.filter { MailTemplateRules.groupOf(it) == group }
            if (inGroup.isEmpty()) return@forEach
            val chips = FreeTextChips({ inGroup.map(::candidateOf) }, onInsert)
            chips.refresh()
            val label = Span(tr("mail.templates.group.${group.name}")).apply { addClassName("mail-template-group") }
            panel.add(Div(label, chips).apply { addClassName("mail-template-group-row") })
        }
    }

    private fun onInput() {
        preview.unpin()
        val problems = MailTemplateRules.check(template.kind, subjectField.value, bodyEditor.value, catalog)
        showProblems(problems)
        saveButton.isEnabled = !problems.any && subjectField.value.isNotBlank() && bodyEditor.value.isNotBlank()
        preview.refresh()
    }

    private fun showProblems(problems: MailTemplateProblems) {
        problemBox.removeAll()
        problems.unknown.forEach { problemBox.add(problemLine("mail.templates.problem.unknown", it)) }
        problems.missing.forEach { problemBox.add(problemLine("mail.templates.problem.missing", MailPlaceholderText.token(it))) }
        subjectField.isInvalid = MailTemplateRules.tokensIn(subjectField.value).any { it in problems.unknown }
        subjectField.errorMessage = tr("mail.templates.problem.subject")
    }

    private fun problemLine(key: String, name: String): Span =
        Span(tr(key, name)).apply {
            addClassName("mail-template-problem")
            addClassName("lt-mail-problem")
        }

    /** The text the preview shows for [language]: the edited one for the own language, the stored sister otherwise. */
    private fun contentFor(language: MailTemplateLanguage): MailTemplateContent? =
        if (language == template.language) {
            MailTemplateContent(subjectField.value, bodyEditor.value)
        } else {
            sisterContent(language)
        }

    private fun sisterContent(language: MailTemplateLanguage): MailTemplateContent? =
        try {
            val rendered = ports.preview.renderPreview(
                MailRenderPreviewRequest(template.kind, MailTemplateRules.familyOf(language), MailTemplateRules.sampleValues(catalog)),
            )
            MailTemplateContent(rendered.subject, rendered.body)
        } catch (e: MailDispatchException) {
            UiNotifications.error(tr("mail.templates.loadFailed", e.message.orEmpty()))
            null
        }

    private fun save() {
        if (!saveButton.isEnabled) return
        val draft = MailTemplateDraft(
            kind = template.kind,
            language = template.language,
            subject = subjectField.value.trim(),
            body = bodyEditor.value,
            bccAddress = bccField.value.trim().ifEmpty { null },
        )
        guarded {
            val saved = ports.templates.save(draft)
            UiNotifications.confirmOn(saveButton)
            show(saved)
            onChanged()
        }
    }

    private fun confirmReset() {
        ConfirmDialog(
            tr("mail.templates.reset.title"),
            tr("mail.templates.reset.text"),
            tr("mail.templates.reset.confirm"),
        ) {
            guarded {
                val reset = ports.reset.reset(template.kind, template.language)
                UiNotifications.success(tr("mail.templates.reset.done", reset.version))
                show(reset)
                onChanged()
            }
        }.also {
            it.setCancelable(true)
            it.setCancelText(tr("app.cancel"))
            it.open()
        }
    }

    private fun sendTestToMe() {
        val result = ports.templates.sendTestToMe(template.kind, template.language)
        UiNotifications.success(tr("mail.templates.testToMe.done", result.recipientAddress))
    }

    private fun sendTestToBcc() {
        val address = bccField.value.trim()
        if (address.isEmpty()) {
            UiNotifications.errorOn(bccField, tr("mail.templates.testBcc.noAddress"))
            return
        }
        val result = ports.bccTest.sendBccTest(template.kind, template.language, address)
        UiNotifications.success(tr("mail.templates.testBcc.done", result.bccAddress))
    }

    /** Shows an older [version] in the preview until the next edit. */
    fun previewVersion(version: MailTemplateVersion) {
        preview.pin(MailTemplateContent(version.subject, version.body))
    }

    private fun openHistory() {
        MailTemplateHistoryDialog(
            ports,
            template.kind,
            template.language,
            onShow = ::previewVersion,
            onRestored = { restored ->
                show(restored)
                onChanged()
            },
        ).open()
    }

    /** Runs [action], turning a refusal by the component or the permission check into a message the user can act on. */
    private fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: MailDispatchException) {
            UiNotifications.error(tr("mail.templates.actionFailed", e.message.orEmpty()))
        } catch (e: AccessDeniedException) {
            UiNotifications.error(tr("mail.templates.actionFailed", e.message.orEmpty()))
        }
    }

    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(key, *params)

    private companion object {
        const val TWO_COLUMN_MIN_WIDTH = "48em"
    }
}
