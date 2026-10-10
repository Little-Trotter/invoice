package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.github.mvysny.kaributools.getText
import com.vaadin.flow.component.notification.Notification
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.RestoreInvoiceDocumentUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.domain.exception.GenerationBlockReason
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.RestoreSource
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InvoicePrintControlsRestoreKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun invoice(status: InvoiceStatus = InvoiceStatus.SENT) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun roles(granted: Boolean): AppRoleProvider = mock<AppRoleProvider>().also {
        whenever(it.hasPermission(AppPermission.DOCUMENT_REPRODUCE)).thenReturn(granted)
    }

    private fun controls(
        useCase: RestoreInvoiceDocumentUseCase?,
        granted: Boolean = true,
        status: InvoiceStatus = InvoiceStatus.SENT,
    ): InvoicePrintControls {
        var reloads = 0
        return InvoicePrintControls(
            invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>(),
            recipientQueryPort = mock<RecipientQueryPort>(),
            generationJobCreationService = mock<GenerationJobCreationService>(),
            documentJobPublisher = mock<DocumentJobPublisher>(),
            llmCheckAvailability = LlmCheckAvailability(),
            restoreInvoiceDocumentUseCase = useCase,
            appRoleProvider = roles(granted),
        ).also {
            it.onReload = { reloads++ }
            it.render(invoice(status))
        }
    }

    private fun restoreButton(controls: InvoicePrintControls): Button? =
        flatten(controls).filterIsInstance<Button>().singleOrNull { it.text == UiTranslations.tr("document.recovery.action") }

    private fun toastTexts(): List<String> {
        MockVaadin.clientRoundtrip()
        return _find<Notification>().map { it.getText() } + _find<Span>().mapNotNull { it.text }
    }

    private fun clickWith(resolution: DocumentResolution): Pair<RestoreInvoiceDocumentUseCase, List<String>> {
        val useCase: RestoreInvoiceDocumentUseCase = mock()
        whenever(useCase.restore(1L)).thenReturn(resolution)
        restoreButton(controls(useCase))!!.click()
        return useCase to toastTexts()
    }

    @Test
    fun `hidden without the permission, a use case or an issued invoice`() {
        assertEquals(null, restoreButton(controls(mock(), granted = false)))
        assertEquals(null, restoreButton(controls(null)))
        assertEquals(null, restoreButton(controls(mock(), status = InvoiceStatus.DRAFT)))
    }

    @Test
    fun `visible with the permission on an issued invoice`() {
        assertTrue(restoreButton(controls(mock())) != null)
    }

    @Test
    fun `a voided invoice offers the restore action while the ordinary print action stays disabled`() {
        val useCase: RestoreInvoiceDocumentUseCase = mock()
        whenever(useCase.restore(1L)).thenReturn(DocumentResolution.RegenerationStarted(9L, reproduction = true))
        val queries: InvoiceDetailQueryPort = mock()
        whenever(queries.generationBlockReason(any())).thenReturn(GenerationBlockReason.VOIDED)
        val controls = InvoicePrintControls(
            invoiceDetailQueryPort = queries,
            recipientQueryPort = mock<RecipientQueryPort>(),
            generationJobCreationService = mock<GenerationJobCreationService>(),
            documentJobPublisher = mock<DocumentJobPublisher>(),
            llmCheckAvailability = LlmCheckAvailability(),
            restoreInvoiceDocumentUseCase = useCase,
            appRoleProvider = roles(true),
        ).also { it.render(invoice(InvoiceStatus.VOIDED)) }

        val print = flatten(controls).filterIsInstance<Button>().single { it.text == UiTranslations.tr("invoiceDetail.jobs.print") }
        assertFalse(print.isEnabled)
        restoreButton(controls)!!.click()

        verify(useCase).restore(1L)
        assertTrue(UiTranslations.tr("document.recovery.regenerationStarted", 9L) in toastTexts())
    }

    @Test
    fun `a voided invoice without the permission gets no restore action`() {
        assertEquals(null, restoreButton(controls(mock(), granted = false, status = InvoiceStatus.VOIDED)))
    }

    @Test
    fun `serve and no document report that nothing had to be done`() {
        listOf(DocumentResolution.Serve("k"), DocumentResolution.NoDocument).forEach {
            val (useCase, texts) = clickWith(it)
            verify(useCase, times(1)).restore(1L)
            assertTrue(UiTranslations.tr("document.recovery.nothingToDo") in texts)
        }
    }

    @Test
    fun `restored confirms on the restore button instead of a toast`() {
        val useCase: RestoreInvoiceDocumentUseCase = mock()
        whenever(useCase.restore(1L)).thenReturn(DocumentResolution.Restored("k", RestoreSource.MAIL_JOURNAL))
        val button = restoreButton(controls(useCase))!!

        button.click()

        assertEquals(UiTranslations.tr("message.saved"), button.text)
        assertTrue(getNotifications().isEmpty())
    }

    @Test
    fun `regeneration started names the job and reloads the jobs`() {
        val useCase: RestoreInvoiceDocumentUseCase = mock()
        whenever(useCase.restore(1L)).thenReturn(DocumentResolution.RegenerationStarted(7L, reproduction = false))
        var reloads = 0
        val controls = controls(useCase).also { it.onReload = { reloads++ } }

        restoreButton(controls)!!.click()

        assertTrue(UiTranslations.tr("document.recovery.regenerationStarted", 7L) in toastTexts())
        assertEquals(1, reloads)
    }

    @Test
    fun `storage unavailable is an error toast`() {
        val (_, texts) = clickWith(DocumentResolution.StorageUnavailable)

        assertTrue(UiTranslations.tr("document.recovery.storageUnavailable") in texts)
    }

    @Test
    fun `a rejected restore is reported instead of escaping the click`() {
        val useCase: RestoreInvoiceDocumentUseCase = mock()
        whenever(useCase.restore(any())).thenThrow(SecurityException("denied"))
        val failing = controls(useCase)

        restoreButton(failing)!!.click()

        MockVaadin.clientRoundtrip()
        assertTrue(_find<Notification>().any { it.hasThemeName("error") })
    }

    @Test
    fun `an unexpected failure is reported instead of escaping the click`() {
        val useCase: RestoreInvoiceDocumentUseCase = mock()
        whenever(useCase.restore(any())).thenThrow(IllegalStateException("boom"))

        restoreButton(controls(useCase))!!.click()

        MockVaadin.clientRoundtrip()
        assertTrue(_find<Notification>().any { it.hasThemeName("error") })
    }
}
