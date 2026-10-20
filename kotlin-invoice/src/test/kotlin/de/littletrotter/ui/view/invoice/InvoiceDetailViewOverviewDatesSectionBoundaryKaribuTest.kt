package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.reference.Language
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.edit
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import java.time.LocalDate

/** The payment-term label per document language and the due-date recalculation when a
 *  payment term is picked while no invoice date is set. */
class InvoiceDetailViewOverviewDatesSectionBoundaryKaribuTest {

    private val refs = ReferenceDataMocks()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun language(id: Int, code: String, fallback: Boolean) = Language().edit { this.id = id; this.code = code; isFallback = fallback }

    private fun term(id: Long, days: Int, names: Map<Language, String> = emptyMap()): PaymentTermDto =
        aPaymentTerm {
            this.id = id
            dueInDays = days
            names.forEach { (lang, name) -> changeName(lang, name) }
        }.toDto()

    private fun invoice(documentLanguage: String, invoiceDate: LocalDate?, paymentTermId: Long? = null) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = invoiceDate,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
        paymentTermId = paymentTermId, dueDate = null, documentLanguage = documentLanguage,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun pane(terms: List<PaymentTermDto>, current: InvoiceDetailDto): Component {
        whenever(refs.paymentTermQueries.findAllPaymentTerms()).thenReturn(terms)
        terms.forEach { whenever(refs.paymentTermQueries.findPaymentTermById(it.id!!)).thenReturn(it) }
        val view = ItemDialogTestFixtures.view(refs)
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = {})
        UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result.component
    }

    private fun termBox(root: Component): ComboBox<PaymentTermDto> = flatten(root).filterIsInstance<ComboBox<*>>()
        .single { it.label == UiTranslations.tr("invoiceDetail.paymentTerm") }.requireAs()

    private fun dueDate(root: Component): DatePicker = flatten(root).filterIsInstance<DatePicker>()
        .single { it.label == UiTranslations.tr("invoiceDetail.dueDate") }

    @Test
    fun `happy path - an English invoice labels its payment term with the English name`() {
        val netto = term(1L, 14, mapOf(germanFallbackLanguage() to "Netto 14", language(2, "en", false) to "Net 14"))

        val root = pane(listOf(netto), invoice("en", LocalDate.of(2026, 1, 1)))

        assertEquals("Net 14", termBox(root).itemLabelGenerator.apply(netto))
    }

    @Test
    fun `boundary - a term without a name in the document language falls back to its German name`() {
        val germanOnly = term(1L, 14, mapOf(language(5, "de", false) to "Netto 14"))

        val root = pane(listOf(germanOnly), invoice("en", LocalDate.of(2026, 1, 1)))

        assertEquals("Netto 14", termBox(root).itemLabelGenerator.apply(germanOnly))
    }

    @Test
    fun `boundary - a term without any name is labelled with an empty string`() {
        val unnamed = term(1L, 14)

        val root = pane(listOf(unnamed), invoice("de", LocalDate.of(2026, 1, 1)))

        assertEquals("", termBox(root).itemLabelGenerator.apply(unnamed))
    }

    @Test
    fun `boundary - picking a payment term while no invoice date is set leaves the due date empty`() {
        val netto = term(1L, 14, mapOf(germanFallbackLanguage() to "Netto 14"))
        val root = pane(listOf(netto), invoice("de", invoiceDate = null))

        termBox(root).value = netto

        assertNull(dueDate(root).value)
    }
}
