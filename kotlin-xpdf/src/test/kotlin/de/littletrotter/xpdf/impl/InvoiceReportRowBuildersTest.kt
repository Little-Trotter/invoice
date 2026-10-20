package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * RCG012-2026-08-00001 — the seller-info box (pinfoBox, §8) must show a foreign VAT registration
 * (e.g. Luxembourg) under that country's own designation, ahead of the German home USt-ID/VAT ID
 * line, when [InvoiceReportContextBuilder.buildContext] resolved one for this invoice
 * ([SenderRenderContext]'s `registrationVatId`/`registrationVatCountry`, see that class). See
 * [pinfoBox]'s own KDoc for the full PO reasoning.
 */
class InvoiceReportRowBuildersTest {

    @ParameterizedTest
    @ValueSource(strings = ["Quadratmeter", "Pauschale", "Stück", "Tag", "Stunde", "Kubikmeter", "Meter", "Hektar"])
    fun `supported units stay apart from the quantity`(unit: String) {
        val row = buildItems(
            InvoiceRenderContext(items = listOf(ItemRenderContext(qty = "2,5", unitLabel = unit))),
            "de",
        ).single()

        assertEquals("2,5", row.qtyPlain)
        assertEquals(unit, row.unitLabel)
    }

    @Test
    fun `missing unit keeps the quantity and leaves the unit label empty`() {
        val row = buildItems(InvoiceRenderContext(items = listOf(ItemRenderContext(qty = "2,5", unitLabel = ""))), "de").single()

        assertEquals("2,5", row.qtyPlain)
        assertEquals("", row.unitLabel)
    }

    @Test
    fun `optional item description stays raw and separate from the article identifiers`() {
        val row = buildItems(
            InvoiceRenderContext(
                items = listOf(
                    ItemRenderContext(
                        description = "Name", itemDescription = "Details & <parts>\nSecond line",
                        sellerItemId = "ART-42",
                    ),
                ),
            ),
            "de",
        ).single()
        assertEquals("Name", row.description)
        assertEquals("Details & <parts>\nSecond line", row.itemDescription)
        assertEquals(listOf(ReportItemIdLine("", "ART-42")), row.idLines)
    }

    @Test
    fun `absent empty and whitespace descriptions add no line`() {
        for (description in listOf(null, "", " \n\t")) {
            val row = buildItems(
                InvoiceRenderContext(items = listOf(ItemRenderContext(description = "Name", itemDescription = description))),
                "de",
            ).single()
            assertTrue(row.itemDescription.isBlank())
        }
    }

    @Test
    fun `pinfoBox renders the Luxembourg registration VAT before the German home vatId`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "LU12345678",
            registrationVatCountry = "LU",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("TVA\nUSt-ID", box.rows.joinToString("\n") { it.label })
        assertEquals("LU12345678\nDE123456789", box.rows.joinToString("\n") { it.value })
        assertEquals(2, box.rows.size)
    }

    @Test
    fun `pinfoBox renders the Luxembourg registration label regardless of English document language`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "LU12345678",
            registrationVatCountry = "LU",
        )

        // The national designation deliberately overrides the render language: an
        // English-language document billed under the LU registration still says "TVA", not a
        // translated "VAT (LU)".
        val box = pinfoBox(senderCtx, "en")

        assertEquals("TVA\nVAT ID", box.rows.joinToString("\n") { it.label })
        assertEquals("LU12345678\nDE123456789", box.rows.joinToString("\n") { it.value })
    }

    @Test
    fun `pinfoBox without a registration renders the single home vatId line unchanged`() {
        val senderCtx = SenderRenderContext(vatId = "DE123456789")

        val box = pinfoBox(senderCtx, "de")

        assertEquals("USt-ID", box.rows.joinToString("\n") { it.label })
        assertEquals("DE123456789", box.rows.joinToString("\n") { it.value })
        assertEquals(1, box.rows.size)
    }

    @Test
    fun `pinfoBox with a registration country but no matching vat number renders only the home line`() {
        // Defensive case: sellerVatRegistrationCountry set, but additionalVatRegistrations had no
        // matching entry — InvoiceReportContextBuilder.buildContext then leaves registrationVatId
        // null, so pinfoBox must not invent a row for it.
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = null,
            registrationVatCountry = null,
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("USt-ID", box.rows.joinToString("\n") { it.label })
        assertEquals("DE123456789", box.rows.joinToString("\n") { it.value })
        assertEquals(1, box.rows.size)
    }

    @Test
    fun `pinfoBox falls back to the localized bundle label for a registration country without a designation`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "IT12345678901",
            registrationVatCountry = "IT",
        )

        val box = pinfoBox(senderCtx, "de")

        // Unknown country -> the bundle's own localized
        // sender.vatId label, never a hardcoded English "VAT number" on a German invoice
        assertEquals("USt-ID\nUSt-ID", box.rows.joinToString("\n") { it.label })
        assertEquals("IT12345678901\nDE123456789", box.rows.joinToString("\n") { it.value })
    }

    @Test
    fun `pinfoBox labels an Austrian registration UID-Nr`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "ATU12345678",
            registrationVatCountry = "AT",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("UID-Nr.\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox labels a Swiss registration MWST-Nr`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "CHE-123.456.789 MWST",
            registrationVatCountry = "CH",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("MWST-Nr.\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox labels a French registration TVA`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "FR12345678901",
            registrationVatCountry = "FR",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("TVA\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox labels a Luxembourg registration TVA`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "LU12345678",
            registrationVatCountry = "LU",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("TVA\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox labels a Belgian registration BTW-TVA`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "BE0123456789",
            registrationVatCountry = "BE",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("BTW/TVA\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox labels a Dutch registration Btw-nr`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "NL123456789B01",
            registrationVatCountry = "NL",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("Btw-nr.\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox labels a Polish registration NIP`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "PL1234567890",
            registrationVatCountry = "PL",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("NIP\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox labels a Czech registration DIC`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "CZ12345678",
            registrationVatCountry = "CZ",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("DIČ\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox labels a Danish registration CVR-nr`() {
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "DK12345678",
            registrationVatCountry = "DK",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("CVR-nr.\nUSt-ID", box.rows.joinToString("\n") { it.label })
    }

    @Test
    fun `pinfoBox with only registration vat id no home vat id`() {
        val senderCtx = SenderRenderContext(
            registrationVatId = "LU12345678",
            registrationVatCountry = "LU",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals("TVA", box.rows.joinToString("\n") { it.label })
        assertEquals("LU12345678", box.rows.joinToString("\n") { it.value })
        assertEquals(1, box.rows.size)
    }

    @Test
    fun `pinfoBox with vatId and taxNumber renders exactly those two rows`() {
        // Email and phone moved to the footer contact block (FOOTER_*_FIT); the pinfo box
        // itself stays limited to VAT ID and tax number even when contact fields are set.
        val senderCtx = SenderRenderContext(
            vatId = "DE123456789",
            taxNumber = "12 345 678 901",
            email = "info@example.com",
            phone = "+49 123 456789",
        )

        val box = pinfoBox(senderCtx, "de")

        assertEquals(2, box.rows.size)
        assertEquals("USt-ID", box.rows.first().label)
        assertFalse(box.rows.joinToString("\n") { it.value }.contains("info@example.com"))
        assertFalse(box.rows.joinToString("\n") { it.value }.contains("+49 123 456789"))
    }

    @Test
    fun `rinfoBox includes invoice number and date as mandatory rows`() {
        val invoiceCtx = InvoiceRenderContext(number = "INV-2026-001", date = "27.08.2026")
        val recipientCtx = RecipientRenderContext()

        val box = rinfoBox(invoiceCtx, recipientCtx, "de")

        assertEquals(2, box.rows.size)
    }

    @Test
    fun `rinfoBox includes optional fields when present`() {
        val invoiceCtx = InvoiceRenderContext(
            number = "INV-2026-001",
            date = "27.08.2026",
            offerReference = "OFF-2026-001",
            dueDate = "27.09.2026",
        )
        val recipientCtx = RecipientRenderContext()

        val box = rinfoBox(invoiceCtx, recipientCtx, "de")

        assertEquals(4, box.rows.size)
    }

    @Test
    fun `buildItems returns empty when there are no items`() {
        val rows = buildItems(InvoiceRenderContext(), "de")

        assertEquals(0, rows.size)
    }

    @Test
    fun `buildItems includes non-empty items`() {
        val invoiceCtx = InvoiceRenderContext(
            items = listOf(
                ItemRenderContext(
                    position = "1",
                    description = "Item 1",
                    qty = "10",
                    unitPrice = "100.00",
                    lineTotal = "1000.00",
                    lineVat = "190.00",
                    vatPercent = "19",
                ),
            ),
        )

        val rows = buildItems(invoiceCtx, "de")

        assertEquals(1, rows.size)
    }

    // The former "buildItems is empty when the items value is not a list" defensive test
    // exercised a hand-built `Map<String, Any?>` whose "items" entry held a `String` instead of a
    // list; that shape is no longer expressible at all once `buildItems` takes a typed
    // `InvoiceRenderContext` (`items: List<ItemRenderContext>`, never `Any?`) — the compiler now
    // rejects what the old test had to construct and assert against at runtime. No replacement
    // assertion: there is no longer a value to assert on.

    private fun itemContextWithArticleIds(sellerItemId: String?, buyerItemId: String?, buyerArticleDescription: String? = null) =
        InvoiceRenderContext(
            items = listOf(
                ItemRenderContext(
                    position = "1", description = "Item",
                    sellerItemId = sellerItemId, buyerItemId = buyerItemId,
                    buyerArticleDescription = buyerArticleDescription,
                ),
            ),
        )

    // finding-fixes (2026-08-31, PO order) — the system article number (BT-155, sellerItemId)
    // must show on every invoice regardless of B2B profile; InvoiceReportContextBuilder is the layer
    // that falls back to the linked Article's own number when no BT-155 was captured, so from
    // buildItems' own perspective a populated sellerItemId is all that matters here.

    @Test
    fun `buildItems carries the seller article number through to the first id line unchanged`() {
        val rows = buildItems(itemContextWithArticleIds(sellerItemId = "ART-1", buyerItemId = null), "de")
        assertEquals("ART-1", rows.single().idLines.first().value)
    }

    @Test
    fun `buildItems shows the bare buyer article number as the second id line`() {
        val rows = buildItems(itemContextWithArticleIds(sellerItemId = "ART-1", buyerItemId = "K-42"), "de")
        assertEquals("K-42", rows.single().idLines[1].value)
    }

    // Line 1 — the seller's own article number, bare (no label). Line 2 — "Ref. <buyer number>",
    // identical wording in German and English (item.buyerId). Line 3 — the buyer description,
    // only together with line 2.

    private val sellerLine = ReportItemIdLine("", "ART-1")
    private val buyerLine = ReportItemIdLine("Ref.", "K-42")

    @Test
    fun `identifier lines carry the bare seller number when no buyer data is captured`() {
        val row = buildItems(itemContextWithArticleIds(sellerItemId = "ART-1", buyerItemId = null), "de").single()
        assertEquals(listOf(sellerLine), row.idLines)
        assertEquals("", row.buyerArticleDescriptionLine)
    }

    @ParameterizedTest
    @ValueSource(strings = ["de", "en"])
    fun `identifier lines carry the seller number bare, the buyer number as Ref and the buyer description as its own third line`(language: String) {
        val row = buildItems(
            itemContextWithArticleIds(sellerItemId = "ART-1", buyerItemId = "K-42", buyerArticleDescription = "Kabelbinder 200mm"),
            language,
        ).single()
        assertEquals(listOf(sellerLine, buyerLine), row.idLines)
        assertEquals("Kabelbinder 200mm", row.buyerArticleDescriptionLine)
    }

    @ParameterizedTest
    @ValueSource(strings = ["de", "en"])
    fun `the Ref line stands without a third line when no description was resolved`(language: String) {
        val row = buildItems(itemContextWithArticleIds(sellerItemId = "ART-1", buyerItemId = "K-42"), language).single()
        assertEquals(listOf(sellerLine, buyerLine), row.idLines)
        assertEquals("", row.buyerArticleDescriptionLine)
    }

    @Test
    fun `a designation without a buyer number produces neither the Ref line nor the description line`() {
        val row = buildItems(
            itemContextWithArticleIds(sellerItemId = "ART-1", buyerItemId = null, buyerArticleDescription = "Kabelbinder 200mm"),
            "de",
        ).single()
        assertEquals(listOf(sellerLine), row.idLines)
        assertEquals("", row.buyerArticleDescriptionLine)
    }

    @ParameterizedTest
    @ValueSource(strings = ["de", "en"])
    fun `the Ref line and the description line are suppressed when the buyer id equals the seller id`(language: String) {
        val row = buildItems(
            itemContextWithArticleIds(sellerItemId = "ART-1", buyerItemId = "ART-1", buyerArticleDescription = "Kabelbinder 200mm"),
            language,
        ).single()
        assertEquals(listOf(sellerLine), row.idLines)
        assertEquals("", row.buyerArticleDescriptionLine)
    }

    // buyerArticleDescriptionLine's own suppression rule: a designation that, once normalised
    // (trimmed, whitespace runs collapsed, case folded), repeats BT-153 or BT-154 verbatim tells
    // the reader nothing new — exact equality only, never a partial/contains match.

    @Test
    fun `buyerArticleDescriptionLine is suppressed when it matches the position description after normalisation`() {
        val rows = buildItems(
            InvoiceRenderContext(
                items = listOf(
                    ItemRenderContext(
                        position = "1", description = "Kabelbinder schwarz, 200 mm",
                        sellerItemId = "ART-1", buyerItemId = "K-42",
                        buyerArticleDescription = "  kabelbinder   schwarz,  200 MM ",
                    ),
                ),
            ),
            "de",
        )
        val row = rows.single()
        assertEquals("", row.buyerArticleDescriptionLine)
        assertEquals(listOf(ReportItemIdLine("Ref.", "K-42")), row.idLines.filter { it.label.isNotBlank() })
    }

    @Test
    fun `buyerArticleDescriptionLine is suppressed when it matches the seller item description after normalisation`() {
        val rows = buildItems(
            InvoiceRenderContext(
                items = listOf(
                    ItemRenderContext(
                        position = "1", description = "Item",
                        itemDescription = "Austausch Therme inkl. Verrohrung, 2. OG",
                        sellerItemId = "ART-1", buyerItemId = "K-42",
                        buyerArticleDescription = "austausch therme   inkl. verrohrung, 2.  og",
                    ),
                ),
            ),
            "de",
        )
        val row = rows.single()
        assertEquals("", row.buyerArticleDescriptionLine)
        assertEquals(listOf(ReportItemIdLine("Ref.", "K-42")), row.idLines.filter { it.label.isNotBlank() })
    }

    @Test
    fun `buyerArticleDescriptionLine prints when it differs from both the position and item description`() {
        val rows = buildItems(
            InvoiceRenderContext(
                items = listOf(
                    ItemRenderContext(
                        position = "1", description = "Kabelbinder schwarz, 200 mm",
                        itemDescription = "Austausch Therme inkl. Verrohrung, 2. OG",
                        sellerItemId = "ART-1", buyerItemId = "K-42",
                        buyerArticleDescription = "Kabelbinder 200mm",
                    ),
                ),
            ),
            "de",
        )
        val row = rows.single()
        assertEquals("Kabelbinder 200mm", row.buyerArticleDescriptionLine)
    }

    private fun itemContextWithPeriod(from: String?, to: String?) = InvoiceRenderContext(
        items = listOf(
            ItemRenderContext(
                position = "1", description = "Item", qty = "1",
                servicePeriodFrom = from, servicePeriodTo = to,
            ),
        ),
    )

    @Test
    fun `an item's service period combines both dates into a range when both are present`() {
        val rows = buildItems(itemContextWithPeriod("01.03.2026", "31.03.2026"), "de")
        assertEquals("01.03.2026 – 31.03.2026", rows.single().servicePeriod)
    }

    @Test
    fun `an item's service period is just the end date when only 'to' is present`() {
        val rows = buildItems(itemContextWithPeriod(null, "31.03.2026"), "de")
        assertEquals("31.03.2026", rows.single().servicePeriod)
    }

    @Test
    fun `an item's service period is just the start date when only 'from' is present`() {
        val rows = buildItems(itemContextWithPeriod("01.03.2026", null), "de")
        assertEquals("01.03.2026", rows.single().servicePeriod)
    }

    @Test
    fun `buildTaxRows returns empty when the taxBreakdown carries no rows key at all`() {
        val invoiceCtx = InvoiceRenderContext(taxBreakdown = RenderTaxBreakdown())

        val rows = buildTaxRows(invoiceCtx)

        assertEquals(0, rows.size)
    }

    @Test
    fun `buildTaxRows returns empty when no tax breakdown`() {
        val rows = buildTaxRows(InvoiceRenderContext())

        assertEquals(0, rows.size)
    }

    // ── The printed currency unit comes from invoiceMap.currencyCode (set once in
    //    InvoiceReportContextBuilder from the invoice's own currency), never from the document
    //    language. ──

    private fun itemContextWithCurrency(currencyCode: String?) = InvoiceRenderContext(
        currencyCode = currencyCode ?: "",
        items = listOf(
            ItemRenderContext(
                position = "1",
                description = "Item 1",
                qty = "10",
                unitPrice = "100,00",
                lineTotal = "1.000,00",
                lineVat = "190,00",
                vatPercent = "19",
            ),
        ),
    )

    @Test
    fun `R8 - buildItems shows CHF, never a Euro sign, for a CHF invoice`() {
        val row = buildItems(itemContextWithCurrency("CHF"), "de").single()

        assertTrue(row.price.endsWith("CHF"), "price: ${row.price}")
        assertTrue(row.net.endsWith("CHF"), "net: ${row.net}")
        assertTrue(row.vatAmt.endsWith("CHF"), "vat: ${row.vatAmt}")
        assertFalse(row.price.contains("€"), "must not contain a Euro sign: ${row.price}")
        assertFalse(row.net.contains("€"), "must not contain a Euro sign: ${row.net}")
        assertFalse(row.vatAmt.contains("€"), "must not contain a Euro sign: ${row.vatAmt}")
    }

    @Test
    fun `buildItems uses the Euro sign for an EUR invoice`() {
        val row = buildItems(itemContextWithCurrency("EUR"), "de").single()

        assertTrue(row.price.endsWith("€"), "price: ${row.price}")
    }

    @Test
    fun `buildItems falls back to EUR when invoiceMap carries no currencyCode key at all`() {
        val invoiceCtx = InvoiceRenderContext(
            items = listOf(ItemRenderContext(position = "1", description = "Item", qty = "1", unitPrice = "1,00")),
        )

        val row = buildItems(invoiceCtx, "de").single()

        assertTrue(row.price.endsWith("€"), "price: ${row.price}")
    }

    @Test
    fun `buildItems uses the Euro sign for an English-language EUR invoice`() {
        val row = buildItems(itemContextWithCurrency("EUR"), "en").single()

        assertTrue(row.price.endsWith("€"), "price: ${row.price}")
    }

    private fun taxBreakdownContextWithCurrency(currencyCode: String?) = InvoiceRenderContext(
        currencyCode = currencyCode ?: "",
        taxBreakdown = RenderTaxBreakdown(
            rows = listOf(TaxBreakdownRowContext(rate = "19,00", category = "S", net = "100,00", vat = "19,00")),
        ),
    )

    @Test
    fun `R8 - buildTaxRows shows CHF, never a Euro sign, for a CHF invoice`() {
        val row = buildTaxRows(taxBreakdownContextWithCurrency("CHF")).single()

        assertTrue(row.net.endsWith("CHF"), "net: ${row.net}")
        assertTrue(row.vat.endsWith("CHF"), "vat: ${row.vat}")
        assertFalse(row.net.contains("€"), "must not contain a Euro sign: ${row.net}")
        assertFalse(row.vat.contains("€"), "must not contain a Euro sign: ${row.vat}")
    }

    @Test
    fun `buildTaxRows uses the Euro sign consistently for an EUR invoice`() {
        val row = buildTaxRows(taxBreakdownContextWithCurrency("EUR")).single()

        assertTrue(row.net.endsWith("€"), "net: ${row.net}")
        assertTrue(row.vat.endsWith("€"), "vat: ${row.vat}")
    }

    @Test
    fun `buildTaxRows falls back to EUR when invoiceMap carries no currencyCode key at all`() {
        val invoiceCtx = InvoiceRenderContext(
            taxBreakdown = RenderTaxBreakdown(
                rows = listOf(TaxBreakdownRowContext(rate = "19,00", category = "S", net = "100,00", vat = "19,00")),
            ),
        )

        val row = buildTaxRows(invoiceCtx).single()

        assertTrue(row.net.endsWith("€"), "net: ${row.net}")
    }

    @Test
    fun `buildTaxRows includes multiple tax rows`() {
        val invoiceCtx = InvoiceRenderContext(
            taxBreakdown = RenderTaxBreakdown(
                rows = listOf(
                    TaxBreakdownRowContext(rate = "19.00", category = "S", net = "100.00", vat = "19.00"),
                    TaxBreakdownRowContext(rate = "0.00", category = "AE", net = "200.00", vat = "0.00"),
                ),
            ),
        )

        val rows = buildTaxRows(invoiceCtx)

        assertEquals(2, rows.size)
    }

    private fun luSender(taxNumber: String? = "1990010112345", labelKey: String? = "foreignTaxNumber.label.matricule") = SenderRenderContext(
        vatId = "DE123456789",
        taxNumber = "12/345/67890",
        registrationVatId = "LU12345678",
        registrationVatCountry = "LU",
        registrationTaxNumber = taxNumber,
        registrationTaxNumberLabelKey = labelKey,
    )

    @Test
    fun `pinfoBox puts the foreign tax number directly below the foreign VAT id and keeps the German rows`() {
        val box = pinfoBox(luSender(), "de")

        assertEquals("TVA\nMatricule\nUSt-ID\nSt.-Nr.", box.rows.joinToString("\n") { it.label })
        assertEquals("LU12345678\n1990010112345\nDE123456789\n12/345/67890", box.rows.joinToString("\n") { it.value })
    }

    @Test
    fun `pinfoBox prints the generic TIN label with the country code in both languages`() {
        val sender = SenderRenderContext(
            vatId = "DE123456789",
            registrationVatId = "DK12345678",
            registrationVatCountry = "DK",
            registrationTaxNumber = "99887766",
            registrationTaxNumberLabelKey = "foreignTaxNumber.label.tin",
        )

        assertEquals("TIN (DK)", pinfoBox(sender, "de").rows[1].label)
        assertEquals("TIN (DK)", pinfoBox(sender, "en").rows[1].label)
    }

    @Test
    fun `pinfoBox adds no row without a foreign tax number, a label key or a registration`() {
        assertEquals(3, pinfoBox(luSender(taxNumber = null), "de").rows.size)
        assertEquals(3, pinfoBox(luSender(labelKey = null), "de").rows.size)
        val noRegistration = SenderRenderContext(
            vatId = "DE123456789",
            registrationTaxNumber = "1990010112345",
            registrationTaxNumberLabelKey = "foreignTaxNumber.label.matricule",
        )
        assertEquals(1, pinfoBox(noRegistration, "de").rows.size)
    }

    @Test
    fun `the German tax number row stays when the foreign number is present`() {
        val box = pinfoBox(luSender(), "en")

        assertEquals(true, box.rows.any { it.label == "Tax Number" && it.value == "12/345/67890" })
    }
}
