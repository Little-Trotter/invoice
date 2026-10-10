package de.littletrotter.shared

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [InvoiceItemDto.category]/[InvoiceItemDto.trip] used to let a caller build a
 * self-contradicting DTO (`category == TRIP` with `trip == null`, or the reverse); every consumer
 * then had its own defensive re-check of a case that, coverage found, could never actually happen
 * once the DTO was built correctly. These tests pin the construction invariant and the
 * [InvoiceItemDto.categoryDetail] sealed view that replaces those re-checks.
 */
class InvoiceItemDtoTest {

    private fun trip() = TripExportDto(detailedOnInvoice = true, fixedNetPrice = null, calculatedNet = Money.of("42.00"))

    private fun item(category: InvoiceItemCategory, trip: TripExportDto?) =
        InvoiceItemDto(positionId = "1", taxRatePercent = BigDecimal("19.00"), category = category, trip = trip)

    @Test
    fun `a TRIP item without trip data is rejected at construction`() {
        assertThatThrownBy { item(InvoiceItemCategory.TRIP, trip = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a non-TRIP item carrying trip data is rejected at construction`() {
        assertThatThrownBy { item(InvoiceItemCategory.SERVICE, trip = trip()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `categoryDetail is Trip carrying the export data for a TRIP item`() {
        val export = trip()
        val detail = item(InvoiceItemCategory.TRIP, trip = export).categoryDetail
        assertThat(detail).isEqualTo(InvoiceItemCategoryDetail.Trip(export))
    }

    @Test
    fun `categoryDetail is NonTrip for every other category`() {
        assertThat(item(InvoiceItemCategory.SERVICE, trip = null).categoryDetail).isEqualTo(InvoiceItemCategoryDetail.NonTrip)
        assertThat(item(InvoiceItemCategory.PRODUCT, trip = null).categoryDetail).isEqualTo(InvoiceItemCategoryDetail.NonTrip)
        assertThat(item(InvoiceItemCategory.UNIT_PRICE, trip = null).categoryDetail).isEqualTo(InvoiceItemCategoryDetail.NonTrip)
    }

    @Test
    fun `categoryDetail is not serialized onto the wire`() {
        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(item(InvoiceItemCategory.SERVICE, trip = null))
        assertThat(json).doesNotContain("categoryDetail")
    }

    @Test
    fun `a SERVICE item serializes category as its enum name and trip as null, unchanged from before`() {
        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(item(InvoiceItemCategory.SERVICE, trip = null))
        assertThat(json).contains("\"category\":\"SERVICE\"").contains("\"trip\":null")
    }

    @Test
    fun `a TRIP item round-trips through JSON with its trip payload intact`() {
        val mapper = jacksonObjectMapper()
        val original = item(InvoiceItemCategory.TRIP, trip = trip())
        val json = mapper.writeValueAsString(original)
        val read: InvoiceItemDto = mapper.readValue(json)
        assertThat(read.categoryDetail).isEqualTo(InvoiceItemCategoryDetail.Trip(trip()))
    }
}
