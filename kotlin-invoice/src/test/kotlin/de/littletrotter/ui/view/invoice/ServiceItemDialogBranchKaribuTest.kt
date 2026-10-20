package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleTaxVariantDto
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.dialog
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.form
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.invoice
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.rate
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.save
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TODAY

/**
 * [ServiceItemDialog] branches beyond the existing suite: a cleared unit radio falls back
 * to hours, cleared quantity/price count as 1 and 0, article picks under reverse charge and with a
 * seller country, and a new position from an article without description uses its name.
 */
class ServiceItemDialogBranchKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val itemEntryQueryPort: ItemEntryQueryPort = mock()
    private val de19 = rate("DE_STANDARD_19", "S", "19")
    private val rcAe = rate("EU_B2B_RC_SERVICE", "AE", "0.00")
    private val frRate = rate("FR_STANDARD", "S", "20.00", Country.FR)

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
        rates: List<TaxRateDto> = listOf(de19),
        reverseCharge: Boolean = false,
        sellerCountry: String? = null,
        onSave: (InvoiceItemCommand) -> Unit,
    ): Dialog {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(rates)
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort,
            itemEntryQueryPort = itemEntryQueryPort,
            invoice = invoice(reverseCharge = reverseCharge, sellerVatRegistrationCountry = sellerCountry),
            existingItem = null,
            defaultTaxRate = { de19 },
            onSaveNew = { onSave(it); 1L }, onUpdate = onSave, today = FIXED_TODAY,
        ).open()
        return dialog()
    }

    private fun desc(dlg: Dialog) = form(dlg).filterIsInstance<TextArea>()[0]
    private fun qty(dlg: Dialog) = form(dlg).filterIsInstance<NumberField>()[0]
    private fun price(dlg: Dialog) = form(dlg).filterIsInstance<NumberField>()[1]

    private fun unitGroup(dlg: Dialog): RadioButtonGroup<String> = form(dlg).filterIsInstance<RadioButtonGroup<*>>().first().requireAs()

    private fun articleBox(dlg: Dialog): ComboBox<ArticleDto> = form(dlg).filterIsInstance<ComboBox<*>>().first().requireAs()

    private fun article(description: String? = null) = ArticleDto(
        id = 3L, name = "Beratung", category = InvoiceItemCategory.SERVICE, description = description,
        defaultUnitPriceNet = Money.of("120.00"), defaultTaxRateId = de19.id,
    )

    @Test
    fun `happy path - a new position from an article without description takes the article name`() {
        var saved: InvoiceItemCommand? = null
        val dlg = open { saved = it }

        articleBox(dlg).value = article()
        save(dlg)

        assertEquals("Beratung", saved?.description)
        assertEquals(3L, saved?.articleId)
        assertEquals(Money.of("120.00"), saved?.unitPriceNet)
    }

    @Test
    fun `boundary - a cleared unit radio falls back to hours with an empty price label`() {
        var saved: InvoiceItemCommand? = null
        val dlg = open { saved = it }

        unitGroup(dlg).value = null
        assertEquals("", price(dlg).label)
        desc(dlg).value = "Beratung"
        qty(dlg).value = 2.0
        save(dlg)

        assertEquals(InvoiceItemUnit.HOUR, saved?.unit)
        assertEquals(0, BigDecimal("2.0").compareTo(saved?.quantity))
    }

    @Test
    fun `boundary - cleared quantity and price count as one and zero and a cleared article carries no id`() {
        var saved: InvoiceItemCommand? = null
        val dlg = open { saved = it }
        articleBox(dlg).value = article(description = "Workshop")
        articleBox(dlg).value = null

        qty(dlg).value = null
        price(dlg).value = null
        save(dlg)

        assertEquals("Workshop", saved?.description)
        assertEquals(0, BigDecimal.ONE.compareTo(saved?.quantity))
        assertEquals(Money.ZERO, saved?.unitPriceNet)
        assertNull(saved?.articleId)
    }

    @Test
    fun `boundary - under reverse charge an article pick keeps the locked rate`() {
        var saved: InvoiceItemCommand? = null
        val dlg = open(rates = listOf(de19, rcAe), reverseCharge = true) { saved = it }

        articleBox(dlg).value = article(description = "Workshop")
        save(dlg)

        assertEquals(rcAe.id, saved?.taxRateId)
    }

    @Test
    fun `boundary - with a seller country an article without variant takes the reference rate`() {
        whenever(taxRateQueries.findReferenceTaxRate(eq(Country.FR), any())).thenReturn(frRate)
        var saved: InvoiceItemCommand? = null
        val dlg = open(rates = listOf(frRate), sellerCountry = "FR") { saved = it }

        articleBox(dlg).value = article(description = "Audit")
        save(dlg)

        assertEquals(frRate.id, saved?.taxRateId)
        assertEquals(UiTranslations.tr("invoiceDetail.service.rateLabel.hour"), price(dlg).label)
    }

    // ── Remaining branch gaps ─────────────────────────────────────────────────────────────────

    @Test
    fun `boundary - with a seller country an article that DOES carry a matching variant uses that variant's rate`() {
        val articleWithVariant = article(description = "Audit").copy(taxVariants = listOfNotNull(frRate.id?.let { ArticleTaxVariantDto(Country.FR, it) }))
        var saved: InvoiceItemCommand? = null
        val dlg = open(rates = listOf(frRate), sellerCountry = "FR") { saved = it }

        articleBox(dlg).value = articleWithVariant
        save(dlg)

        assertEquals(frRate.id, saved?.taxRateId)
    }

    @Test
    fun `boundary - picking an article carries its own article number into the article number field`() {
        val dlg = open { }
        val numbered = article(description = "Beratung").copy(articleNumber = "AR-42")

        articleBox(dlg).value = numbered

        val numberField = form(dlg).filterIsInstance<com.vaadin.flow.component.textfield.TextField>()
            .single { it.value == "AR-42" }
        assertEquals("AR-42", numberField.value)
    }
}
