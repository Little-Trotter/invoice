package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getFormattedRow
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.dto.SenderProfileRowDto
import de.littletrotter.application.masterdata.port.inbound.SenderProfileQueryPort
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * [SenderProfileView.configureGrid]'s cityCountry/sellerNumber/shortHash columns
 * are plain `ValueProvider` lambdas, never invoked by Grid unless a row is actually formatted —
 * the same gap [ArticleViewCoverageKaribuTest] and [RecipientViewGridColumnsKaribuTest] already
 * closed for their own grids. Neither [SenderProfileViewKaribuTest] nor
 * [SenderProfileViewCoverageKaribuTest] ever calls `_getFormattedRow` on this one.
 */
class SenderProfileViewGridColumnsKaribuTest {

    private val senderProfileQueries: SenderProfileQueryPort = mock()
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
        SenderProfilePorts(senderProfileQueries, mock(), mock()),
        b2bSectionComposer,
        defaultsComposer,
        de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(languageQueries, mock(), mock()),
        numberRangeQueries,
        PartyFormExtensionRegistry(emptyList()),
        SellerVatRegistrationsExtensionRegistry(emptyList()),
    )

    private fun grid(view: SenderProfileView): de.littletrotter.ui.component.DataTable<SenderProfileRowDto> = view.grid

    // ── city/country column: billing address present vs. absent ────────────────────────────────

    @Test
    fun `the city-country column renders blank when the sender has no billing address`() {
        val view = newView()
        val sender = senderRow()

        val row = grid(view)._getFormattedRow(sender)

        assertEquals("", row[1], "cityCountry column with no billing address")
    }

    @Test
    fun `the city-country column renders city and country when a billing address is present`() {
        val view = newView()
        val sender = senderRow(billingCity = "Berlin", billingCountryAlpha2 = "DE")

        val row = grid(view)._getFormattedRow(sender)

        assertEquals("Berlin, Deutschland", row[1], "cityCountry column with a billing address")
    }

    // ── sellerNumber column: em-dash / real-value split ─────────────────────────────────────────

    @Test
    fun `the sellerNumber column renders the em dash placeholder when absent`() {
        val view = newView()
        val sender = senderRow(sellerNumber = null)

        val row = grid(view)._getFormattedRow(sender)

        assertEquals("—", row[3], "sellerNumber column")
    }

    @Test
    fun `the sellerNumber column renders the real value when present`() {
        val view = newView()
        val sender = senderRow(sellerNumber = "V-001")

        val row = grid(view)._getFormattedRow(sender)

        assertEquals("V-001", row[3], "sellerNumber column")
    }

    // ── shortHash column: em-dash / real-value split ────────────────────────────────────────────

    @Test
    fun `the shortHash column renders the em dash placeholder when absent`() {
        val view = newView()
        val sender = senderRow(shortHash = null)

        val row = grid(view)._getFormattedRow(sender)

        assertEquals("—", row[4], "shortHash column")
    }

    @Test
    fun `the shortHash column renders the real value when present`() {
        val view = newView()
        val sender = senderRow(shortHash = "AB12")

        val row = grid(view)._getFormattedRow(sender)

        assertEquals("AB12", row[4], "shortHash column")
    }
}
