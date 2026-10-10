package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.dialog
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.form
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.invoice
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.rate
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.save
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TODAY

/** A position text with a character the PDF fonts lack is refused with the PDF message. */
class ServiceItemDialogPdfTextKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val itemEntryQueryPort: ItemEntryQueryPort = mock()
    private val de19 = rate("DE_STANDARD_19", "S", "19")

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        // The dialogs read tax rates through the item-entry port; route it to the tax-rate mock
        // every test stubs. An explicit stub on the port itself still wins.
        whenever(itemEntryQueryPort.findAllTaxRates()).thenAnswer { taxRateQueries.findAllTaxRates() }
        whenever(itemEntryQueryPort.findReferenceTaxRate(any(), any())).thenAnswer {
            taxRateQueries.findReferenceTaxRate(it.getArgument(0), it.getArgument(1))
        }
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun open(
        onSave: (InvoiceItemCommand) -> Unit,
    ): Dialog {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort,
            itemEntryQueryPort = itemEntryQueryPort,
            invoice = invoice(),
            existingItem = null,
            defaultTaxRate = { de19 },
            onSaveNew = { onSave(it); 1L }, onUpdate = onSave, today = FIXED_TODAY,
        ).open()
        return dialog()
    }

    private fun desc(dlg: Dialog) = form(dlg).filterIsInstance<TextArea>()[0]

    @Test
    fun `a description with an emoji is refused with the PDF message and nothing is saved`() {
        var saved: InvoiceItemCommand? = null
        val dlg = open { saved = it }

        desc(dlg).value = "Beratung 💩"
        save(dlg)

        assertNull(saved)
        val field = desc(dlg)
        assertTrue(field.isInvalid)
        val message = field.errorMessage
        assertTrue(message.contains(UiTranslations.tr(dlg, "validation.pdfText.unrenderable") + ": 💩"), message)
    }
}
