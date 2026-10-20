package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InvoiceMailActionsReproductionKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val host = Div()
    private val blockedText get() = UiTranslations.tr("document.reproduction.sendBlocked")

    private fun dto() = InvoiceDetailDto(
        id = 1L, invoiceNumber = "RE-1", status = 2, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun dispatch(): InvoiceMailDispatchService =
        mock<InvoiceMailDispatchService>().also { whenever(it.isAvailable()).thenReturn(true) }

    @Test
    fun `the compose rejection with the reproduction code shows the blocked text`() {
        val dispatch = dispatch()
        whenever(dispatch.compose(any())).thenThrow(MailDispatchException("[DOCUMENT_REPRODUCTION_NOT_SENDABLE] raw english"))

        InvoiceMailActions(dispatch, null).openMailCompose(host, dto())
        MockVaadin.clientRoundtrip()

        assertTrue(_find<Span>().any { it.text == blockedText })
    }

    @Test
    fun `any other dispatch rejection keeps its own message`() {
        val dispatch = dispatch()
        whenever(dispatch.compose(any())).thenThrow(MailDispatchException("no recipient email"))

        InvoiceMailActions(dispatch, null).openMailCompose(host, dto())
        MockVaadin.clientRoundtrip()

        assertTrue(_find<Span>().any { it.text == "no recipient email" })
    }
}
