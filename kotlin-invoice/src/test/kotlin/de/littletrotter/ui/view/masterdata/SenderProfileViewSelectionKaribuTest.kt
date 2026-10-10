package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [SenderProfileView.configureGrid]'s selection listener
 * (`event.value?.let { openEditor(it) }`): neither
 * [SenderProfileViewKaribuTest] nor [SenderProfileViewCoverageKaribuTest] ever selects/clears a
 * grid row.
 *
 * `isGermanAddress`'s dead `"DEUTSCHLAND"` OR term is gone:
 * the country gate now lives in [de.littletrotter.domain.value.PostalCodeValidator], whose
 * inputs are ISO alpha-2 codes only.
 */
class SenderProfileViewSelectionKaribuTest {

    private val sellerProfileService: SellerProfileService = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val b2bSectionComposer = SenderB2bSectionComposer(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue)
    private val paymentTermQueries: PaymentTermQueryPort = mock()
    private val languageQueries: LanguageQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val defaultsComposer = SenderProfileDefaultsComposer(paymentTermQueries)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): SenderProfileView = SenderProfileView(
        sellerProfileService,
        b2bSectionComposer,
        defaultsComposer,
        de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(languageQueries, mock(), mock()),
        numberRangeQueries,
        PartyFormExtensionRegistry(emptyList()),
        SellerVatRegistrationsExtensionRegistry(emptyList()),
    )

    private fun grid(view: SenderProfileView): Grid<SenderProfile> = view.grid

    private fun editorDialog(view: SenderProfileView): Dialog {
        val f = AbstractCrudView::class.java.getDeclaredField("editorDialog")
        f.isAccessible = true
        return f.get(view) as Dialog
    }

    @Test
    fun `selecting a grid row opens the editor`() {
        val sender = SenderProfile().apply { id = 1L; name = "Anbieter" }
        whenever(sellerProfileService.findByIdForEdit(1L)).thenReturn(sender)
        val view = newView()

        grid(view).asSingleSelect().value = sender

        assertTrue(editorDialog(view).isOpened)
    }

    @Test
    fun `clearing the grid selection does not close an already-open editor`() {
        val sender = SenderProfile().apply { id = 1L; name = "Anbieter" }
        whenever(sellerProfileService.findByIdForEdit(1L)).thenReturn(sender)
        val view = newView()
        grid(view).asSingleSelect().value = sender

        grid(view).asSingleSelect().clear()

        // The `?.let {}` null branch must be a no-op — nothing re-opens or closes the dialog.
        assertTrue(editorDialog(view).isOpened)
    }
}
