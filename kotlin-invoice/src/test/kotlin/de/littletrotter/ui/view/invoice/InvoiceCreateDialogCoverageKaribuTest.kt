package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.dom.Element
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.streams.toList
import de.littletrotter.domain.value.CustomerNumber

/**
 * Closes the remaining branch-coverage gaps in [InvoiceCreateDialog] that
 * [InvoiceCreateDialogKaribuTest] does not reach: the seller item-label generator's billing-city
 * null-safety chain, the recipient combo's own lazy data provider (`offset/limit` → `pageIndex`
 * idiom, same as [InvoiceListView]'s and [PrintJobListView]'s own conversions), and the
 * null-message fallback of both [InvoiceCreateDialog]'s create-error branches.
 */
class InvoiceCreateDialogCoverageKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()

    private val defaultSender = aSenderProfile {
        id = 1L
        name = "Haupt-Anbieter GmbH"
        isDefault = true
    }
    private val recipient = aRecipient {
        id = 10L
        name = "Testkunde AG"
        customerNumber = CustomerNumber("KD-0001")
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun dialogForm(dialog: Dialog): FormLayout = dialog.children.toList().filterIsInstance<FormLayout>().first()

    private fun createButton(dialog: Dialog): com.vaadin.flow.component.button.Button =
        dialog._get<com.vaadin.flow.component.button.Button> { text = UiTranslations.tr("invoice.dialog.create") }

    private fun sellerCombo(dialog: Dialog): ComboBox<SenderSummaryDto> =
        dialogForm(dialog).children.toList().filterIsInstance<ComboBox<*>>()[0].requireAs()

    private fun recipientCombo(dialog: Dialog): ComboBox<RecipientSummaryDto> =
        dialogForm(dialog).children.toList().filterIsInstance<ComboBox<*>>()[1].requireAs()

    private fun recipientComboDataProvider(dialog: Dialog): com.vaadin.flow.data.provider.DataProvider<RecipientSummaryDto, String> =
        recipientCombo(dialog).dataProvider.requireAs()

    private fun allComponents(): List<com.vaadin.flow.component.Component> {
        val found = mutableListOf<com.vaadin.flow.component.Component>()
        fun walk(element: Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(UI.getCurrent().element)
        return found
    }

    private fun assertNotificationShown(expectedText: String) {
        MockVaadin.clientRoundtrip()
        val found = allComponents().filterIsInstance<Span>().any { it.text == expectedText } ||
            allComponents().filterIsInstance<com.vaadin.flow.component.notification.Notification>()
                .any { de.littletrotter.ui.toastText(it) == expectedText }
        assertTrue(found, "expected an open notification with text: $expectedText")
    }

    // ── seller item label — billing city present vs. absent ────────────────────────────────────

    @Test
    fun `seller item label appends the billing city when present`() {
        val withCity = aSenderProfile { id = 2L; name = "Filial-Anbieter" }
        withCity.addAddress(Address.forSender(withCity, Address.AddressType.BILLING, "Hauptstr. 1", "80331", "München", Country.DE))
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(withCity.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val label = sellerCombo(dialog).itemLabelGenerator.apply(withCity.toSenderSummaryDto())

        assertEquals("Filial-Anbieter, München", label)
    }

    @Test
    fun `seller item label omits the city clause when there is no billing address`() {
        val noAddress = aSenderProfile { id = 3L; name = "Adresslos e.K." }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(noAddress.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val label = sellerCombo(dialog).itemLabelGenerator.apply(noAddress.toSenderSummaryDto())

        assertEquals("Adresslos e.K.", label)
    }

    // ── recipient combo's own lazy data provider — offset/limit -> pageIndex idiom ─────────────

    @Test
    fun `recipient combo's data provider computes pageIndex from offset over limit`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(recipientQuery.findRecipients(2, 20, "Test")).thenReturn(listOf(recipient.toRecipientSummaryDto()))
        whenever(recipientQuery.countRecipients("Test")).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val result = recipientComboDataProvider(dialog)
            .fetch(com.vaadin.flow.data.provider.Query<RecipientSummaryDto, String>(40, 20, emptyList(), null, "Test"))
            .toList()

        assertEquals(listOf(recipient.toRecipientSummaryDto()), result)
    }

    @Test
    fun `recipient combo's data provider defaults pageIndex to zero when limit is zero`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(recipientQuery.findRecipients(0, 0, null)).thenReturn(emptyList())

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val result = recipientComboDataProvider(dialog)
            .fetch(com.vaadin.flow.data.provider.Query<RecipientSummaryDto, String>(0, 0, emptyList(), null, null))
            .toList()

        assertTrue(result.isEmpty())
    }

    // ── create-error branches — the null-message fallback on each side ─────────────────────────

    @Test
    fun `an IllegalArgumentException with no message falls back to the generic create-error text`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenThrow(IllegalArgumentException())

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()
        createButton(dialog).click()

        assertNotificationShown(UiTranslations.tr("invoice.error.create"))
    }

    @Test
    fun `an unexpected exception with no message falls back to the generic error text with an empty detail`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenThrow(RuntimeException())

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()
        createButton(dialog).click()

        assertNotificationShown(UiTranslations.tr("invoice.error.create", UiTranslations.tr("message.error.generic")))
    }
}
