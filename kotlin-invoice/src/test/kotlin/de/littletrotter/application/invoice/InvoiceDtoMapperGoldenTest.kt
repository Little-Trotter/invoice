package de.littletrotter.application.invoice

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The CRITICAL acceptance bar: the refactored [InvoiceDtoMapper] must produce byte-identical
 * wire output to the pre-refactor `DocumentJobPublisher.mapToDto`. `invoice-dto-golden.json` was
 * generated once, before the extraction, from the exact same [MapperFixture] against the
 * then-inline mapping code (a fully populated invoice — items, receipts, party identifiers/
 * electronic addresses, B2B values, payment term, tax snapshots, PDF template ref). This test
 * re-runs the same fixture through the extracted mapper and compares the serialized JSON verbatim
 * (same Jackson config — `jacksonObjectMapper()` — as the publisher uses on the wire, so field
 * order is identical either way).
 *
 * `invoice-dto-golden.json` is regenerated only for a deliberate, reviewed wire contract change —
 * this test's job otherwise stays the same: freeze whatever the *current* mapper output is. Each
 * regeneration is a one-time, reviewed exception to "the golden file never changes", not a
 * precedent for casually re-running it on every future field tweak. Past regenerations, for
 * context on why the fixture's JSON looks the way it does:
 *
 * - The [InvoiceDto] greenfield restructuring grouped 39 flat top-level fields into
 *   [InvoiceDatesDto]/[InvoiceReferencesDto]/[InvoiceTotalsDto]/[InvoiceMetaDto] (a hard cut, no
 *   `InvoiceDtoV2`, no tolerant reader — kotlin-invoice and kotlin-xpdf deploy together).
 * - The `B2bValueDto` catalog-table removal: the field is `term: B2bTerm` now, not
 *   `btCode: String`, so the two `b2bValues` entries serialize as `{"term":"BT_155", ...}` /
 *   `{"term":"BT_19", ...}` instead of `{"btCode":"BT-155", ...}` / `{"btCode":"BT-19", ...}`
 *   (kotlin-xpdf's `MustangExportModelMapper` was updated to consume `B2bTerm` in the same hard cut).
 * - The new [InvoiceDto.draft] field: `"draft":true` now trails the fixture's JSON (the field's
 *   own constructor position), reflecting [MapperFixture]'s invoice never setting an explicit
 *   status, so it keeps the entity's own `InvoiceStatus.DRAFT` default — a genuinely correct
 *   mapper output, not a fixture bug.
 * - The sender profile's `website` and `registeredSeat` (the PDF footer's contact column and
 *   "Sitz" line): both travel on [de.littletrotter.shared.PartyDto] behind `footerNote`, so
 *   `"website":null,"registeredSeat":null` now sits between `footerNote` and `identifier` on
 *   both parties — [MapperFixture] sets neither.
 * - The two new `de.littletrotter.shared.InvoiceItemDto` fields `articleNumber`/
 *   `buyerArticleDescription` (system article number shown on every invoice regardless of B2B
 *   profile, plus the buyer's own article designation alongside its BT-156 number): each
 *   `items[]` entry now trails `"articleNumber":null,"buyerArticleDescription":null` — `null`
 *   because [MapperFixture]'s items carry no article reference, a genuinely correct mapper output
 *   for that fixture, not a bug.
 * - The two watermark flags `cancellation`/`cancelled`: at one point the fixture's type code `381`
 *   (`InvoiceTypeCode.CREDIT_NOTE`) made it a cancellation document and `"cancellation":true` was the correct
 *   output. The cancellation flow's own type code later moved to `384` (`CORRECTED_INVOICE`);
 *   `CREDIT_NOTE` is now reserved for a genuine credit note this system does not produce, so the
 *   same fixture correctly maps to `"cancellation":false` today — the field's *meaning* changed, the
 *   fixture only follows it.
 * - [InvoiceDto.cancelled] (the former "cancelled-original" watermark flag) was deleted outright
 *   — a cancelled original is never regenerated (the single-generation gate), so its archived PDF
 *   keeps whichever mark it already carried and this bit is no longer needed anywhere.
 *   `"cancelled":false` simply dropped off the trailing end of the fixture.
 * - The two new `InvoiceItemDto` fields `category`/`trip` (the export DTO carries the item's
 *   category, plus the `TRIP` detail `null`/[de.littletrotter.shared.TripExportDto] carries for a
 *   business-trip position): each `items[]` entry now trails `"category":"SERVICE","trip":null` —
 *   [MapperFixture]'s two items are both `SERVICE`, a genuinely correct mapper output, not a bug.
 * - The new `InvoiceItemDto.grossAmount` field (the printed trip position's "Brutto: X" subline
 *   reads it instead of re-deriving net+vat): each `items[]` entry now trails `"grossAmount":{...}`
 *   right after `vatAmount`, with the fixture's own `InvoiceItem.grossAmount` values (297.50 /
 *   500.00) — a genuinely correct mapper output, not a bug. `trip` stays `null` for both fixture
 *   items (neither is a `TRIP` position), so the new
 *   `TripExportDto.workbookRef`/`resolvedWorkbookBase64` fields never surface in this fixture.
 * - `meta.wordingProfile` is stamped by the mapper itself (`0` = HANDWERK default) instead of by
 *   the publisher; fixture value `null` → `0`. The new [InvoiceMetaDto.wordingProfile] field is
 *   not a new top-level [InvoiceDto] field (`InvoiceDtoArityTest`'s growth-stop rule, and `meta`
 *   already owns language/format/profile metadata): `"wordingProfile":null` now trails the `meta`
 *   object. The field is stamped by
 *   [de.littletrotter.application.invoice.DocumentJobPublishTransaction] at publish time
 *   (field-injected `WordingSettingsGate`, not a constructor parameter of [InvoiceDtoMapper]
 *   itself — see that class's own KDoc on why), never by [InvoiceDtoMapper.mapToDto] directly, so
 *   this test (which calls the mapper alone) correctly keeps seeing `null`.
 * - The new [de.littletrotter.shared.InvoiceItemDto.buyerItemDescription] field
 *   ("Referenz-Beschreibung", the recipient-specific companion to the existing BT-156
 *   "Referenz-ID"/`buyerItemNumber`): each `items[]` entry now trails
 *   `"buyerItemDescription":null` right after `"buyerArticleDescription":null` — `null` because
 *   [MapperFixture]'s items never set it, a genuinely correct mapper output for that fixture, not
 *   a bug.
 * - The removal of the deprecated bare [de.littletrotter.shared.InvoiceItemDto.taxRate]
 *   (superseded by `taxRateSnapshot` + `taxCategory`): each `items[]` entry drops its
 *   `"taxRate":<n>,` key, which used to sit right after `unitPriceNet` — a genuinely correct mapper
 *   output now that the field no longer exists, not a bug.
 * - The rename of [de.littletrotter.shared.InvoiceItemDto.taxRatePercent] (was `taxRateSnapshot`,
 *   non-null now): each `items[]` entry's `"taxRateSnapshot":<n>` key becomes
 *   `"taxRatePercent":<n>`, same values.
 */
class InvoiceDtoMapperGoldenTest {

    private val jsonMapper = jacksonObjectMapper()

    @Test
    fun `mapper output matches the pre-refactor golden JSON byte for byte`() {
        val ctx = MapperFixture.build()
        val mapper = InvoiceDtoMapper(
            receiptRepository = ctx.receiptRepository,
            b2bFieldValueService = ctx.b2bFieldValueService,
            payloadExchange = ctx.payloadExchange,
        )

        val actualJson = jsonMapper.writeValueAsString(mapper.mapToDto(ctx.invoice))
        val goldenJson = Files.readString(Paths.get("src/test/resources/fixtures/invoice-dto-golden.json"))

        assertEquals(goldenJson, actualJson, "the refactored mapper's wire output must be byte-identical to the pre-refactor golden fixture")
    }
}
