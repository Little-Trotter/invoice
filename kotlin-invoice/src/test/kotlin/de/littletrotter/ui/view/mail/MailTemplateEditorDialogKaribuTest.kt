package de.littletrotter.ui.view.mail

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailBccTestResult
import de.littletrotter.application.mail.port.inbound.MailPlaceholderCatalogQueryPort
import de.littletrotter.application.mail.port.inbound.MailPlaceholderEntry
import de.littletrotter.application.mail.port.inbound.MailPlaceholderType
import de.littletrotter.application.mail.port.inbound.MailRenderPreview
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewQueryPort
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateDraft
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTemplateQueryPort
import de.littletrotter.application.mail.port.inbound.MailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.MailTemplateVersion
import de.littletrotter.application.mail.port.inbound.MailTestToMeResult
import de.littletrotter.application.mail.port.inbound.ResetMailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.SendMailTemplateBccTestUseCase
import de.littletrotter.application.security.AccessDeniedException
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@DisplayName("MailTemplateEditorDialog — edit, preview, history and tests of one template")
class MailTemplateEditorDialogKaribuTest {

    private val query: MailTemplateQueryPort = mock()
    private val catalog: MailPlaceholderCatalogQueryPort = mock()
    private val preview: MailRenderPreviewQueryPort = mock()
    private val templates: MailTemplateUseCase = mock()
    private val reset: ResetMailTemplateUseCase = mock()
    private val bccTest: SendMailTemplateBccTestUseCase = mock()
    private var changed = 0

    private val stored = template(version = 2, subject = "Rechnung {{invoiceNumber}}", body = "Guten Tag {{recipientName}}, Nr. {{invoiceNumber}}")

    private fun template(version: Int, subject: String, body: String, seeded: Boolean = false, bcc: String? = null) =
        MailTemplate(1, MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, version, subject, body, bcc, seeded, Instant.parse("2026-10-01T10:00:00Z"))

    private fun tr(key: String) = UiTranslations.tr(key)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(catalog.placeholders(MailTemplateKind.INVOICE_DISPATCH)).thenReturn(
            listOf(
                MailPlaceholderEntry("invoiceNumber", MailPlaceholderType.TEXT, "RE-2026-0042"),
                MailPlaceholderEntry("recipientName", MailPlaceholderType.TEXT, "Muster GmbH"),
                MailPlaceholderEntry("dueDate", MailPlaceholderType.DATE, "01.11.2026"),
                MailPlaceholderEntry("grossTotal", MailPlaceholderType.MONEY, "119,00 EUR"),
                MailPlaceholderEntry("senderName", MailPlaceholderType.TEXT, "Little Trotter"),
                MailPlaceholderEntry("custom", MailPlaceholderType.TEXT, "x"),
            ),
        )
        whenever(preview.renderPreview(any())).thenReturn(
            MailRenderPreview("Invoice {{invoiceNumber}}", "Dear {{recipientName}}", MailTemplateLanguage.EN, 1),
        )
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun open(template: MailTemplate = stored): MailTemplateEditorDialog {
        val ports = MailTemplatePorts(query, catalog, preview, templates, reset, bccTest)
        return MailTemplateEditorDialog(ports, template) { changed++ }.also { it.open() }
    }

    private fun previewText(dialog: MailTemplateEditorDialog): String =
        dialog.preview.subjectLine.text + "|" + dialog.preview.bodyFrame.element.textRecursively

    @Test
    fun `each chip box sits directly above the element it inserts into`() {
        val dialog = open()
        val children = dialog.editColumn.children.toList()

        val subjectAt = children.indexOf(dialog.subjectField)
        val bodyAt = children.indexOf(dialog.bodyEditor)
        assertEquals(dialog.subjectChipsBox, children[subjectAt - 1])
        assertEquals(dialog.bodyChipsBox, children[bodyAt - 1])
        assertTrue(subjectAt < bodyAt)
    }

    @Test
    fun `the subject chip panel is hidden until the toggle in the subject field is clicked`() {
        val dialog = open()

        assertFalse(dialog.subjectChipsBox.isVisible)
        assertTrue(dialog.bodyChipsBox.isVisible)
        assertEquals(dialog.subjectChipsToggle, dialog.subjectField.suffixComponent)

        dialog.subjectChipsToggle._click()
        assertTrue(dialog.subjectChipsBox.isVisible)

        dialog.subjectChipsToggle._click()
        assertFalse(dialog.subjectChipsBox.isVisible)
    }

    @Test
    fun `the body chip panel is open by default and toggles like the subject one`() {
        val dialog = open()

        assertTrue(dialog.bodyChipsBox.isVisible)
        dialog.bodyChipsToggle._click()
        assertFalse(dialog.bodyChipsBox.isVisible)
        dialog.bodyChipsToggle._click()
        assertTrue(dialog.bodyChipsBox.isVisible)
        assertFalse(dialog.subjectChipsBox.isVisible)
    }

    @Test
    fun `the header carries version and origin as one subtitle and the body has no formatting helper`() {
        val dialog = open()

        assertEquals(UiTranslations.tr("mail.templates.editor.version", 2) + " · " + tr("mail.templates.origin.custom"), dialog.headerSubtitle.text)
        val helpers = dialog.bodyEditor.element.children.flatMap { it.children }
            .filter { it.classList.contains("lt-html-freetext-editor__helper") }.toList()
        assertTrue(helpers.all { it.text.isEmpty() })
    }

    @Test
    fun `the preview switches are segmented controls`() {
        val dialog = open()

        assertTrue(dialog.preview.modeToggle.hasClassName("lt-segmented"))
        assertTrue(dialog.preview.languageToggle.hasClassName("lt-segmented"))
    }

    @Test
    fun `both chip panels use the same grouped rows`() {
        val dialog = open()

        assertEquals(dialog.bodyChipsBox.children.count(), dialog.subjectChipsBox.children.count())
        assertTrue(dialog.subjectChipsBox.children.allMatch { it.element.classList.contains("mail-template-group-row") })
    }

    @Test
    fun `a chip is named after the German placeholder and falls back to the key when none exists`() {
        val dialog = open()
        val chipLabels = dialog.bodyChipsBox._find<com.vaadin.flow.component.button.Button>().map { it.element.getChild(0).text }

        assertEquals("Rechnungsnummer", dialog.placeholderLabel("invoiceNumber"))
        assertEquals("custom", dialog.placeholderLabel("custom"))
        assertTrue("Rechnungsnummer" in chipLabels)
        assertTrue("custom" in chipLabels)
    }

    private fun click(item: com.vaadin.flow.component.contextmenu.MenuItem) {
        com.vaadin.flow.component.ComponentUtil.fireEvent(item, com.vaadin.flow.component.ClickEvent(item))
    }

    @Test
    fun `the edit column reads like a mail client, starting with the bcc field`() {
        val dialog = open()

        assertEquals(dialog.bccField, dialog.editColumn.children.toList().first())
    }

    @Test
    fun `the bcc test item is disabled and anonymous without an address`() {
        val dialog = open()

        assertFalse(dialog.testBccItem.isEnabled)
        assertEquals(tr("mail.templates.editor.testBcc"), dialog.testBccItem.text)
        assertTrue(dialog.testToMeItem.isEnabled)
    }

    @Test
    fun `the bcc test item names the typed address and follows the field`() {
        val dialog = open(template(2, stored.subject, stored.body, bcc = "b@example.com"))
        assertTrue(dialog.testBccItem.isEnabled)
        assertEquals(UiTranslations.tr("mail.templates.editor.testBccTo", "b@example.com"), dialog.testBccItem.text)

        dialog.bccField.value = "  "

        assertFalse(dialog.testBccItem.isEnabled)
        assertEquals(tr("mail.templates.editor.testBcc"), dialog.testBccItem.text)
    }

    @Test
    fun `the footer holds only the test menu, cancel and save`() {
        val dialog = open()

        assertEquals(listOf<com.vaadin.flow.component.Component>(dialog.testMenu, dialog.cancelButton, dialog.saveButton), dialog.footer.element.children.toList().map { it.component.get() })
    }

    @Test
    fun `history and reset tools sit in the header`() {
        val dialog = open()

        val tools = dialog.header.element.children.toList().map { it.component.get() }.drop(1)
        assertEquals(listOf<com.vaadin.flow.component.Component>(dialog.historyButton, dialog.toolsMenu), tools)
    }

    @Test
    fun `an empty bcc does not stop the test to me item`() {
        whenever(templates.sendTestToMe(any(), any())).thenReturn(MailTestToMeResult("anna@example.com", "<m>", Instant.EPOCH))

        click(open().testToMeItem)

        verify(templates).sendTestToMe(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)
    }

    private fun textMode(dialog: MailTemplateEditorDialog) {
        dialog.preview.modeToggle.value = MailPreviewMode.TEXT
    }

    @Test
    fun `the header names kind, language, version and origin`() {
        val dialog = open()

        assertTrue(dialog.headerSubtitle.text.contains("2"))
        assertTrue(dialog.headerSubtitle.text.contains(tr("mail.templates.origin.custom")))
        assertEquals("Rechnung {{invoiceNumber}}", dialog.subjectField.value)
        assertTrue(dialog.saveButton.isEnabled)
    }

    @Test
    fun `save is blocked while the required placeholder is missing`() {
        val dialog = open()

        dialog.subjectField.value = "Rechnung"
        dialog.bodyEditor.value = "Guten Tag"

        assertFalse(dialog.saveButton.isEnabled)
        assertTrue(dialog.problemBox.element.textRecursively.contains("{{invoiceNumber}}"))
        com.vaadin.flow.component.ComponentUtil.fireEvent(dialog.saveButton, com.vaadin.flow.component.ClickEvent(dialog.saveButton))
        verify(templates, never()).save(any())
    }

    @Test
    fun `an unknown placeholder is marked in red and blocks saving`() {
        val dialog = open()

        dialog.bodyEditor.value = "Nr. {{invoiceNumber}} {{nonsense}}"

        assertFalse(dialog.saveButton.isEnabled)
        assertTrue(dialog.problemBox.element.textRecursively.contains("nonsense"))
        assertTrue(dialog.problemBox.children.anyMatch { it.hasClassName("lt-mail-problem") })
        dialog.bodyEditor.value = "Nr. {{invoiceNumber}}"
        assertTrue(dialog.saveButton.isEnabled)
    }

    @Test
    fun `an unusable catalogue keeps saving locked`() {
        whenever(catalog.placeholders(any())).thenThrow(MailDispatchException("down"))

        val dialog = open()

        assertFalse(dialog.saveButton.isEnabled)
    }

    @Test
    fun `the preview fills sample values and changes on input`() {
        val dialog = open()
        textMode(dialog)
        assertTrue(previewText(dialog).contains("Rechnung RE-2026-0042"))
        assertTrue(previewText(dialog).contains("Guten Tag Muster GmbH"))

        dialog.bodyEditor.value = "Neu {{invoiceNumber}}"
        dialog.subjectField.value = "Betreff {{dueDate}} {{invoiceNumber}}"

        assertTrue(previewText(dialog).contains("Betreff 01.11.2026 RE-2026-0042"))
        assertTrue(previewText(dialog).contains("Neu RE-2026-0042"))
    }

    @Test
    fun `the html view renders the formatted mail`() {
        val dialog = open()
        dialog.bodyEditor.value = "Nr. {{invoiceNumber}}"

        assertEquals(MailPreviewMode.HTML, dialog.preview.modeToggle.value)
        val html = dialog.preview.bodyFrame.children.findFirst().get() as com.vaadin.flow.component.Html
        assertTrue(html.innerHtml.contains("Nr. RE-2026-0042"))
    }

    @Test
    fun `bold text and an indented plain line reach the html preview without literal markers`() {
        val dialog = open()
        dialog.bodyEditor.value = "**Hallo** Welt\n    Eingerückt"

        val html = (dialog.preview.bodyFrame.children.findFirst().get() as com.vaadin.flow.component.Html).innerHtml
        assertTrue(html.contains("<b>Hallo</b> Welt"), html)
        assertTrue(html.contains("lt-ft-l1"), html)
        assertTrue(html.contains("Eingerückt"), html)
        assertFalse(html.contains("*"), html)
    }

    @Test
    fun `the language toggle shows the stored sister template and returns to the edited text`() {
        val dialog = open()
        textMode(dialog)

        dialog.preview.languageToggle.value = MailTemplateLanguage.EN

        assertTrue(previewText(dialog).contains("Invoice RE-2026-0042"))
        assertTrue(previewText(dialog).contains("Dear Muster GmbH"))
        dialog.preview.languageToggle.value = MailTemplateLanguage.DE
        assertTrue(previewText(dialog).contains("Rechnung RE-2026-0042"))
    }

    @Test
    fun `an unavailable sister preview says so instead of failing`() {
        whenever(preview.renderPreview(any())).thenThrow(MailDispatchException("down"))
        val dialog = open()

        dialog.preview.languageToggle.value = MailTemplateLanguage.EN

        assertTrue(dialog.preview.bodyFrame.element.textRecursively.contains(tr("mail.templates.preview.unavailable")))
    }

    @Test
    fun `save sends the edited draft and adopts the new version`() {
        whenever(templates.save(any())).thenReturn(template(3, "Neu {{invoiceNumber}}", "Text {{invoiceNumber}}", bcc = "b@example.com"))
        val dialog = open()
        dialog.subjectField.value = " Neu {{invoiceNumber}} "
        dialog.bodyEditor.value = "Text {{invoiceNumber}}"
        dialog.bccField.value = " b@example.com "

        dialog.saveButton._click()

        val draft = argumentCaptor<MailTemplateDraft>().also { verify(templates).save(it.capture()) }.firstValue
        assertEquals("Neu {{invoiceNumber}}", draft.subject)
        assertEquals("b@example.com", draft.bccAddress)
        assertEquals(1, changed)
        assertTrue(dialog.headerSubtitle.text.contains("3"))
    }

    @Test
    fun `a refused save is reported and the dialog stays`() {
        whenever(templates.save(any())).thenThrow(MailDispatchException("refused"))
        val dialog = open()

        dialog.saveButton._click()

        assertEquals(0, changed)
        assertTrue(dialog.isOpened)
        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty())
    }

    @Test
    fun `a denied permission is reported like a refusal`() {
        whenever(templates.sendTestToMe(any(), any())).thenThrow(AccessDeniedException("no"))
        val dialog = open()

        click(dialog.testToMeItem)

        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty())
    }

    @Test
    fun `an empty bcc stays null in the draft`() {
        whenever(templates.save(any())).thenReturn(stored)
        val dialog = open()

        dialog.saveButton._click()

        val draft = argumentCaptor<MailTemplateDraft>().also { verify(templates).save(it.capture()) }.firstValue
        assertEquals(null, draft.bccAddress)
    }

    @Test
    fun `restoring a version creates a new version that the editor shows`() {
        val older = MailTemplateVersion(
            MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1, "Alt {{invoiceNumber}}", "Alter Text {{invoiceNumber}}",
            null, true, Instant.parse("2026-09-01T10:00:00Z"), false,
        )
        val current = older.copy(version = 2, subject = stored.subject, body = stored.body, seededDefault = false, current = true)
        whenever(query.listVersions(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)).thenReturn(listOf(current, older))
        whenever(templates.restoreVersion(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1))
            .thenReturn(template(3, "Alt {{invoiceNumber}}", "Alter Text {{invoiceNumber}}", seeded = true))
        val dialog = open()

        dialog.historyButton._click()
        val history = _find<MailTemplateHistoryDialog>().single()
        assertEquals(2, history.grid.dataCommunicator.itemCount)
        val restoreButtons = restoreButtons(history)
        assertEquals(1, restoreButtons.size, "the current version needs no restore")
        restoreButtons.single()._click()

        verify(templates).restoreVersion(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1)
        assertEquals("Alt {{invoiceNumber}}", dialog.subjectField.value)
        assertTrue(dialog.headerSubtitle.text.contains("3"))
        assertEquals(1, changed)
    }

    @Test
    fun `showing a version pins it in the preview until the next edit`() {
        val older = MailTemplateVersion(
            MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1, "Alt {{invoiceNumber}}", "Alter Text",
            null, true, Instant.parse("2026-09-01T10:00:00Z"), false,
        )
        whenever(query.listVersions(any(), any())).thenReturn(listOf(older))
        val dialog = open()
        textMode(dialog)

        dialog.historyButton._click()
        val history = _find<MailTemplateHistoryDialog>().single()
        val show = history.grid._getCellComponent(0, "actions").children.toList().first() as Button
        show._click()

        assertTrue(previewText(dialog).contains("Alt RE-2026-0042"))
        dialog.bodyEditor.value = "Nr. {{invoiceNumber}}"
        assertTrue(previewText(dialog).contains("Rechnung RE-2026-0042"))
    }

    @Test
    fun `a failing history load shows an empty list`() {
        whenever(query.listVersions(any(), any())).thenThrow(MailDispatchException("down"))
        val dialog = open()

        dialog.historyButton._click()

        assertEquals(0, _find<MailTemplateHistoryDialog>().single().grid.dataCommunicator.itemCount)
    }

    @Test
    fun `a refused restore is reported`() {
        val older = MailTemplateVersion(
            MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE, 1, "s", "b", null, true, Instant.EPOCH, false,
        )
        whenever(query.listVersions(any(), any())).thenReturn(listOf(older))
        whenever(templates.restoreVersion(any(), any(), any())).thenThrow(MailDispatchException("no"))
        val dialog = open()

        dialog.historyButton._click()
        restoreButtons(_find<MailTemplateHistoryDialog>().single()).single()._click()

        assertEquals(0, changed)
        assertEquals("Rechnung {{invoiceNumber}}", dialog.subjectField.value)
    }

    @Test
    fun `reset asks first and then shows the standard text as the new version`() {
        whenever(reset.reset(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE))
            .thenReturn(template(3, "Std {{invoiceNumber}}", "Standard {{invoiceNumber}}", seeded = true))
        val dialog = open()

        click(dialog.resetItem)
        verify(reset, never()).reset(any(), any())
        val confirm = _find<ConfirmDialog>().single()
        com.vaadin.flow.component.ComponentUtil.fireEvent(confirm, ConfirmDialog.ConfirmEvent(confirm, false))

        verify(reset).reset(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)
        assertEquals("Std {{invoiceNumber}}", dialog.subjectField.value)
        assertTrue(dialog.headerSubtitle.text.contains(tr("mail.templates.origin.standard")))
        assertEquals(1, changed)
    }

    @Test
    fun `test to me sends the stored template`() {
        whenever(templates.sendTestToMe(any(), any())).thenReturn(MailTestToMeResult("anna@example.com", "<m>", Instant.EPOCH))
        val dialog = open()

        click(dialog.testToMeItem)

        verify(templates).sendTestToMe(MailTemplateKind.INVOICE_DISPATCH, MailTemplateLanguage.DE)
    }

    @Test
    fun `test to bcc needs an address and sends the one typed now`() {
        whenever(bccTest.sendBccTest(any(), any(), any())).thenReturn(MailBccTestResult("b@example.com", "<m>", Instant.EPOCH))
        val dialog = open()

        click(dialog.testBccItem)
        verify(bccTest, never()).sendBccTest(any(), any(), any())

        dialog.bccField.value = " b@example.com "
        click(dialog.testBccItem)
        verify(bccTest).sendBccTest(eq(MailTemplateKind.INVOICE_DISPATCH), eq(MailTemplateLanguage.DE), eq("b@example.com"))
    }

    @Test
    fun `cancel closes without saving`() {
        val dialog = open()

        dialog.cancelButton._click()

        assertFalse(dialog.isOpened)
        verify(templates, never()).save(any())
    }

    private fun restoreButtons(history: MailTemplateHistoryDialog): List<Button> {
        return (0 until history.grid.dataCommunicator.itemCount).flatMap { row ->
            history.grid._getCellComponent(row, "actions").children.toList().filterIsInstance<Button>()
                .filter { it.text == tr("mail.templates.history.restore") }
        }
    }
}
