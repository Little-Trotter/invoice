package de.littletrotter.xpdf

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.Money
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.shared.ShipToDto
import de.littletrotter.shared.VatRegistrationDto
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Unit tests for the embedded KoSIT validator.
 * No Testcontainers needed - the validator and the XRechnung scenario are loaded from the classpath.
 */
class KositValidationIT {

    private val validator = EmbeddedKositValidator().apply { init() }

    @Test
    fun `embedded validator initializes successfully`() {
        assertNotNull(validator, "Validator should be created")
    }

    @Test
    fun `validator version includes engine and scenario date`() {
        val result = validator.validate(validCiiXml())
        assertNotNull(result.validatorVersion, "Result should include validator version")
        assertTrue(result.validatorVersion!!.contains("2026-01-31"), "Scenario date should be part of the version")
    }

    @Test
    fun `valid XRechnung invoice is accepted`() {
        val result = validator.validate(validCiiXml())
        assertEquals(KositOutcome.VALID, result.status, "A conformant invoice must be VALID, got: ${result.summary}")
    }

    @Test
    fun `invoice violating BR-CO-25 is rejected as INVALID`() {
        // BR-CO-25: a positive amount due for payment requires payment due date or payment terms.
        val xml = validCiiXml().replace(
            """              <ram:SpecifiedTradePaymentTerms>
                <ram:Description>Zahlbar innerhalb von 30 Tagen</ram:Description>
                <ram:DueDateDateTime>
                  <udt:DateTimeString format="102">20260910</udt:DateTimeString>
                </ram:DueDateDateTime>
              </ram:SpecifiedTradePaymentTerms>
""",
            ""
        )
        val result = validator.validate(xml)
        assertEquals(KositOutcome.INVALID, result.status, "A BR-CO-25 violation must be INVALID, got: ${result.summary}")
        assertTrue(result.details.isNotEmpty(), "INVALID result should carry details")
    }

    @Test
    fun `malformed XML is rejected as INVALID`() {
        val result = validator.validate("<Invoice><broken>")
        assertEquals(KositOutcome.INVALID, result.status, "Malformed input is a document failure, never VALID")
    }

    // An existing BR-CO-25 violation (payment terms removed) carries the rule ID and
    // stage in the mapped result.
    @Test
    fun `BR-CO-25 violation carries ruleId and stage KOSIT`() {
        val xml = validCiiXml().replace(
            """              <ram:SpecifiedTradePaymentTerms>
                <ram:Description>Zahlbar innerhalb von 30 Tagen</ram:Description>
                <ram:DueDateDateTime>
                  <udt:DateTimeString format="102">20260910</udt:DateTimeString>
                </ram:DueDateDateTime>
              </ram:SpecifiedTradePaymentTerms>
""",
            ""
        )
        val result = validator.validate(xml)
        val brCo25 = result.details.firstOrNull { it.ruleId == "BR-CO-25" }
        assertNotNull(brCo25, "Expected a finding with ruleId BR-CO-25, got: ${result.details}")
        assertEquals("KOSIT", brCo25!!.stage)
        assertEquals("kosit", brCo25.validator)
        assertEquals("ERROR", brCo25.severity)
    }

    // A pure recommendation-level violation (BR-DE-17: TypeCode not in the allowed
    // subset) is a WARNING, not an ERROR, and doesn't necessarily flip the document to INVALID
    // on its own severity alone — it's still surfaced with severity == WARNING.
    @Test
    fun `BR-DE-17 recommendation violation is surfaced as WARNING`() {
        val xml = validCiiXml().replace("<ram:TypeCode>380</ram:TypeCode>", "<ram:TypeCode>750</ram:TypeCode>")
        val result = validator.validate(xml)
        val brDe17 = result.details.firstOrNull { it.ruleId == "BR-DE-17" }
        assertNotNull(brDe17, "Expected a finding with ruleId BR-DE-17, got: ${result.details}")
        assertEquals("WARNING", brDe17!!.severity)
    }

    // BR-DE-18: a Skonto line appended to the free-text payment terms, in the machine-readable
    // form XRechnung's Guidelines/Implementation prescribe. The term name and the Skonto line
    // are each on their own line, the Skonto line starts and ends with '#', followed by a line
    // break - this is the exact shape MustangTransactionBuilder must produce.
    @Test
    fun `BR-DE-18 - a Skonto line after the term name is VALID`() {
        val xml = validCiiXml().replace(
            "<ram:Description>Zahlbar innerhalb von 30 Tagen</ram:Description>",
            "<ram:Description>Zahlbar innerhalb von 30 Tagen\n#SKONTO#TAGE=7#PROZENT=2.00#\n</ram:Description>"
        )
        val result = validator.validate(xml)
        assertEquals(KositOutcome.VALID, result.status, "A well-formed BR-DE-18 Skonto line must be VALID, got: ${result.summary}")
    }

    // A malformed Skonto line (percent without two decimals) violates BR-DE-18 and is rejected.
    @Test
    fun `BR-DE-18 - a Skonto line without two-decimal percent is INVALID`() {
        val xml = validCiiXml().replace(
            "<ram:Description>Zahlbar innerhalb von 30 Tagen</ram:Description>",
            "<ram:Description>Zahlbar innerhalb von 30 Tagen\n#SKONTO#TAGE=7#PROZENT=2#\n</ram:Description>"
        )
        val result = validator.validate(xml)
        assertEquals(KositOutcome.INVALID, result.status, "A malformed BR-DE-18 Skonto line must be INVALID, got: ${result.summary}")
        val brDe18 = result.details.firstOrNull { it.ruleId?.contains("BR-DE-18") == true }
        assertNotNull(brDe18, "Expected a finding with ruleId containing BR-DE-18, got: ${result.details}")
    }

    // Not-well-formed XML fails at the syntax stage, tagged accordingly.
    @Test
    fun `malformed XML carries stage SYNTAX when the parser reports it`() {
        val result = validator.validate("<Invoice><broken>")
        assertEquals(KositOutcome.INVALID, result.status)
        // The KoSIT engine's own well-formedness failure surfaces via schemaViolations
        // (stage SYNTAX) when it can produce one; if the parser fails before that, the
        // finding list may be empty — the status itself is still the authoritative signal.
        if (result.details.isNotEmpty()) {
            assertTrue(result.details.any { it.stage == "SYNTAX" }, "Expected a SYNTAX-stage finding, got: ${result.details}")
        }
    }

    // An engine failure yields ERROR, never VALID.
    //
    // Since EmbeddedKositValidator.check is now lazy (not @PostConstruct-eager),
    // simply never calling init() no longer reproduces an engine failure by itself: the very first
    // validate() call below would lazily build a perfectly working Check and succeed. The
    // `checkOverrideForTest` seam (that class's own KDoc) forces a Check that genuinely throws
    // instead, deterministically exercising validate()'s catch(Exception)->ERROR degradation path.
    @Test
    fun `engine exception during check yields ERROR, never VALID`() {
        val throwingCheck = Mockito.mock(de.kosit.validationtool.api.Check::class.java)
        Mockito.`when`(throwingCheck.checkInput(Mockito.any(de.kosit.validationtool.api.Input::class.java)))
            .thenThrow(RuntimeException("engine boom"))
        val broken = EmbeddedKositValidator().apply { checkOverrideForTest = throwingCheck }

        val result = broken.validate(validCiiXml())

        assertEquals(KositOutcome.ERROR, result.status)
    }

    // buildCheckWith's own catch(Exception)->"Failed to initialize embedded KoSIT
    // validator" wrapping and extractScenario's missing-classpath-resource branch have no path
    // through the real bundled scenario ZIP; buildCheckWith's own resourceLookup test seam
    // (that method's KDoc) forces both at once.
    @Test
    fun `boundary - buildCheckWith wraps a missing scenario resource in a single explanatory IllegalStateException`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            EmbeddedKositValidator().buildCheckWith(resourceLookup = { null })
        }

        assertEquals("Failed to initialize embedded KoSIT validator", ex.message)
        assertTrue(ex.cause is IllegalStateException, "the original extractScenario failure is chained as the cause")
        assertTrue(
            ex.cause!!.message!!.contains("not found on classpath"),
            "was: ${ex.cause!!.message}",
        )
    }

    // KoSIT accepts the three category shapes the tax-rule mapping table produces: standard
    // rate (S), Reverse Charge (AE + VATEX-EU-AE, both parties' VAT-IDs present) and a public-
    // sector (Behörden/B2G) invoice identified by a Leitweg-ID-shaped BuyerReference. Built as
    // targeted mutations of the already-accepted [validXml] base fixture rather than through the
    // full DTO→XmlExportServiceImpl pipeline, to keep this a deterministic, low-risk schema/
    // schematron check independent of the mapping code's own unit tests.

    @Test
    fun `R8-T16a standard rate S invoice is accepted by KoSIT`() {
        val result = validator.validate(standardRateXml())
        assertEquals(KositOutcome.VALID, result.status, "Expected S/19% to be VALID, got: ${result.summary} ${result.details}")
    }

    @Test
    fun `R8-T16b reverse charge AE invoice with both VAT-IDs is accepted by KoSIT`() {
        val result = validator.validate(reverseChargeXml())
        assertEquals(KositOutcome.VALID, result.status, "Expected AE + VATEX-EU-AE to be VALID, got: ${result.summary} ${result.details}")
    }

    // PO decision 4 — BT-120 (ram:ExemptionReason) composed as "German original — English
    // standard text" is still KoSIT-VALID. Element order (CalculatedAmount, TypeCode,
    // ExemptionReason, BasisAmount, CategoryCode, ExemptionReasonCode, RateApplicablePercent)
    // matches the official ZUGFeRD 2.5.2 EN16931 examples that carry BT-120
    // (E12_Innergemeinschaftliche_Lieferungen, E25_steuerbefreite_Auslandslieferung), not invented — the base fixture never carried this
    // element at all (`ExemptionReasonCode`/BT-121 only).
    @Test
    fun `IN-6 reverse charge AE invoice with a composed German+English BT-120 is accepted by KoSIT`() {
        val result = validator.validate(reverseChargeXmlWithComposedBt120())
        assertEquals(
            KositOutcome.VALID,
            result.status,
            "Expected the composed German+English BT-120 to be VALID, got: ${result.summary} ${result.details}",
        )
    }

    @Test
    fun `R8-T16c public sector invoice with a Leitweg-ID buyer reference is accepted by KoSIT`() {
        val result = validator.validate(publicSectorXml())
        assertEquals(KositOutcome.VALID, result.status, "Expected the Behörden fixture to be VALID, got: ${result.summary} ${result.details}")
    }

    // ── T7 — the export proof at the level that counts ─────────────────────────────────────
    //
    // All cases above check **hand-built** XML strings. That's right for targeted rule
    // violations, but proves nothing about the path a real document takes: InvoiceDto →
    // MustangTransactionBuilder → XmlExportServiceImpl. That exact path is checked here — with
    // the B2B invoice from [b2bInvoiceDto], the same one the XML and PDF/A proofs use.
    //
    // This is the real proof required: not "our fixture is valid", but "what this application
    // produces is valid".

    @Test
    fun `an invoice with free text and a consumer note as two BT-22 notes is VALID`() {
        val base = b2bInvoiceDto()
        val invoice = base.copy(meta = base.meta.copy(freeText = "Freitext", consumerNote = "Hinweis fuer Privatkunden"))
        val xml = String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)

        assertEquals(2, Regex("<ram:IncludedNote>").findAll(xml.substringBefore("<rsm:SupplyChainTradeTransaction>")).count())
        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Befunde: " + result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )
    }

    @Test
    fun `T7 - the xml produced by the real export path for a B2B invoice is VALID`() {
        val xml = String(XmlExportServiceImpl().generate(b2bInvoiceDto()), Charsets.UTF_8)

        val result = validator.validate(xml)

        assertEquals(
            KositOutcome.VALID, result.status,
            "Der erzeugte Beleg muss XRechnung-konform sein. Befunde: " +
                result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )
    }

    /**
     * EXPORT-1 — the proof that would mean nothing without the previous case, and vice versa.
     * `VALID` alone is no evidence that the B2B values are actually **present**: an export that
     * silently drops them would also be `VALID` (all seven terms are optional). So every term is
     * verified against its CII element on **the same** document KoSIT just accepted.
     *
     * Conversely, the elements being present would mean little if it made the document invalid —
     * so this case checks validity at the same time.
     */
    @Test
    fun `EXPORT-1 - the validated B2B document really carries the captured terms`() {
        val xml = String(XmlExportServiceImpl().generate(b2bInvoiceDto()), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Befunde: " + result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )

        // BT-12 — ram:ContractReferencedDocument/ram:IssuerAssignedID
        assertTrue(
            compact.contains("<ram:ContractReferencedDocument><ram:IssuerAssignedID>V-2026-0815</ram:IssuerAssignedID></ram:ContractReferencedDocument>"),
            "BT-12 fehlt im geprüften Beleg",
        )
        // BT-18 — ram:AdditionalReferencedDocument with TypeCode 130; adds BT-18-1
        // (ReferenceTypeCode "IL", UNTDID 1153) to this slot.
        assertTrue(
            compact.contains("<ram:AdditionalReferencedDocument><ram:IssuerAssignedID>OBJ-4711</ram:IssuerAssignedID><ram:TypeCode>130</ram:TypeCode><ram:ReferenceTypeCode>IL</ram:ReferenceTypeCode></ram:AdditionalReferencedDocument>"),
            "BT-18 fehlt im geprüften Beleg",
        )
        // BT-56/BT-58 — ram:BuyerTradeParty/ram:DefinedTradeContact
        val buyer = compact.substringAfter("<ram:BuyerTradeParty>").substringBefore("</ram:BuyerTradeParty>")
        assertTrue(
            buyer.contains("<ram:DefinedTradeContact><ram:PersonName>Frau Meier, Kreditorenbuchhaltung</ram:PersonName><ram:EmailURIUniversalCommunication><ram:URIID>meier@acme.example</ram:URIID></ram:EmailURIUniversalCommunication></ram:DefinedTradeContact>"),
            "BT-56/BT-58 fehlen am Käufer: $buyer",
        )
        // BT-155/BT-156 — ram:SpecifiedTradeProduct
        val product = compact.substringAfter("<ram:SpecifiedTradeProduct>").substringBefore("</ram:SpecifiedTradeProduct>")
        assertTrue(product.contains("<ram:SellerAssignedID>ART-4711</ram:SellerAssignedID>"), "BT-155 fehlt: $product")
        assertTrue(product.contains("<ram:BuyerAssignedID>KUNDE-0815</ram:BuyerAssignedID>"), "BT-156 fehlt: $product")
        // BT-73/BT-74 — the header-level billing period, from the same document.
        val settlement = compact.substringAfter("<ram:ApplicableHeaderTradeSettlement>")
        assertTrue(
            settlement.contains("<ram:BillingSpecifiedPeriod><ram:StartDateTime><udt:DateTimeString format=\"102\">20260101</udt:DateTimeString></ram:StartDateTime><ram:EndDateTime><udt:DateTimeString format=\"102\">20260131</udt:DateTimeString></ram:EndDateTime></ram:BillingSpecifiedPeriod>"),
            "BT-73/BT-74 fehlen im Kopf-Settlement",
        )

        // BT-19 still has no header element (Mustang 2.25.0 offers no header setter),
        // but its value now reaches the KoSIT-validated document as BT-133 on the line.
        assertTrue(
            compact.contains("<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-4711</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"),
            "BT-133 fehlt an der Position: $compact",
        )
    }

    // ── BT-31 under an active foreign seller VAT registration ──────────────────────────────────
    //
    // The same real export path as above, this time with the seller billed under an
    // LU VAT registration (`meta.sellerVatRegistrationCountry`): the produced XML must
    // stay KoSIT-VALID *and* carry the LU registration number as BT-31 on the seller's own
    // SpecifiedTaxRegistration — never the German home vatId, which is precisely the defect
    // this test guards against, discovered live (a 17% LU-taxed invoice whose XML declared the
    // German USt-ID instead of the LU TVA number that actually legitimizes the rate).

    @Test
    fun `RCG012 - a B2B invoice billed under an active LU seller registration is VALID and carries the LU number, not the DE home vatId, as BT-31`() {
        val base = b2bInvoiceDto()
        val invoice = base.copy(
            senderProfile = base.senderProfile?.copy(
                additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")),
            ),
            recipient = base.recipient?.copy(country = "LU"),
            meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU"),
        )
        val xml = String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Der LU-registrierte Beleg muss XRechnung-konform bleiben. Befunde: " +
                result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )

        val seller = compact.substringAfter("<ram:SellerTradeParty>").substringBefore("</ram:SellerTradeParty>")
        assertTrue(
            seller.contains("<ram:SpecifiedTaxRegistration><ram:ID schemeID=\"VA\">LU12345678</ram:ID></ram:SpecifiedTaxRegistration>"),
            "BT-31 muss die LU-Registrierungsnummer tragen: $seller",
        )
        assertFalse(
            seller.contains("DE123456789"),
            "Die deutsche Heim-USt-ID darf nicht mehr auf dem Beleg stehen, wenn er unter der LU-Registrierung läuft: $seller",
        )
    }

    @Test
    fun `T7 - the same invoice without B2B values is VALID as well`() {
        // The counter-case (community): that the B2B values don't break the document would be
        // indistinguishable from "the document is always valid anyway" without this — and
        // vice versa.
        val xml = String(XmlExportServiceImpl().generate(b2bInvoiceDto(b2bValues = false)), Charsets.UTF_8)

        val result = validator.validate(xml)

        assertEquals(
            KositOutcome.VALID, result.status,
            "Der Community-Beleg muss ebenso konform sein. Befunde: " +
                result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )
    }

    // ── receipts (BG-24) — real export path, live-KoSIT-run regression ─────────────────────
    //
    // DSC001-2025-05-00001 failed BR-52 (×2), BR-CL-07 (×2), CII-SR-039, CII-SR-040 and
    // CII-SR-113 because receipts used to write TypeCode-130 filename references, notes, and a
    // nameless TypeCode-916 attachment at LINE level (SpecifiedLineTradeAgreement) — a shape
    // CII-SR-113 forbids outright, independent of the other three findings. The fix moves every
    // receipt to ONE deduplicated `AdditionalReferencedDocument` (TypeCode 916) per document,
    // under ApplicableHeaderTradeAgreement (BG-24). This is the fixture-based proof that the real
    // export path (InvoiceDto -> MustangTransactionBuilder -> XmlExportServiceImpl) produces a
    // document KoSIT accepts, with the receipts actually embedded where they belong and nowhere
    // else — not just an assertion that the finding IDs are gone.

    private class SeededDocumentStore(private val objects: Map<String, ByteArray>) : DocumentStore {
        override fun put(objectKey: String, data: ByteArray, contentType: String) {}
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not found: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) {}
    }

    @Test
    fun `receipts - item-level and invoice-level receipts produce one deduped BG-24 entry each, none at line level, and the document stays VALID`() {
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34) // "%PDF-1.4"
        val store = SeededDocumentStore(
            mapOf(
                "receipt-pool/shared.pdf" to pdfBytes,
                "receipt-pool/second.pdf" to pdfBytes,
            ),
        )
        val base = b2bInvoiceDto()
        val invoice = base.copy(
            // Attached to the invoice as a whole (`invoice_receipt`) …
            receipts = listOf(ReceiptDto(fileName = "shared.pdf", objectKey = "receipt-pool/shared.pdf")),
            // … AND to the position — the same receipt in both places is the dedup case;
            // second.pdf only hangs on the position, to prove item-level receipts still reach BG-24.
            items = base.items.map {
                it.copy(
                    receipts = listOf(
                        ReceiptDto(fileName = "shared.pdf", objectKey = "receipt-pool/shared.pdf"),
                        ReceiptDto(fileName = "second.pdf", objectKey = "receipt-pool/second.pdf"),
                    ),
                )
            },
        )

        val xml = String(XmlExportServiceImpl(store).generate(invoice), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Belege an Position und Kopf dürfen den Beleg nicht mehr ungültig machen (BR-52/BR-CL-07/CII-SR-113). Befunde: " +
                result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )

        // BG-24 lives in the header (ApplicableHeaderTradeAgreement), TypeCode 916,
        // IssuerAssignedID = file name (BT-122) — never without it (that was BR-52).
        val headerAgreement = compact
            .substringAfter("<ram:ApplicableHeaderTradeAgreement>")
            .substringBefore("</ram:ApplicableHeaderTradeAgreement>")
        assertTrue(
            headerAgreement.contains("<ram:AdditionalReferencedDocument><ram:IssuerAssignedID>shared.pdf</ram:IssuerAssignedID><ram:TypeCode>916</ram:TypeCode>"),
            "BG-24-Eintrag für shared.pdf fehlt im Kopf: $headerAgreement",
        )
        assertTrue(
            headerAgreement.contains("<ram:AdditionalReferencedDocument><ram:IssuerAssignedID>second.pdf</ram:IssuerAssignedID><ram:TypeCode>916</ram:TypeCode>"),
            "BG-24-Eintrag für second.pdf fehlt im Kopf: $headerAgreement",
        )
        // shared.pdf is attached to both the line item AND the header — it must still appear
        // only once as a TypeCode-916 entry (the dedup rule).
        assertEquals(
            2, Regex("<ram:TypeCode>916</ram:TypeCode>").findAll(headerAgreement).count(),
            "erwartet genau zwei 916-Einträge (shared.pdf einmal trotz Doppelbezug, second.pdf einmal): $headerAgreement",
        )

        // Nothing remains at line level: no AdditionalReferencedDocument (CII-SR-113) and no
        // "Beleg: <file>" note (CII-SR-039/040) under SpecifiedLineTradeAgreement.
        val lineAgreement = compact
            .substringAfter("<ram:SpecifiedLineTradeAgreement>")
            .substringBefore("</ram:SpecifiedLineTradeAgreement>")
        assertFalse(
            lineAgreement.contains("AdditionalReferencedDocument"),
            "Zeilenebene darf kein BG-24 mehr tragen (CII-SR-113): $lineAgreement",
        )
        assertFalse(
            lineAgreement.contains("IncludedNote"),
            "Zeilenebene darf keine Beleg-Notiz mehr tragen (CII-SR-039/040): $lineAgreement",
        )
    }

    /** A synthetic, valid single-page PDF padded (via an unreferenced custom stream — harmless,
     *  ignored by parsers) to exactly [targetBytes] — the shape [MAX_RECEIPTS_PER_INVOICE]
     *  receipts at the 440 KiB `ReceiptSizePolicy.TARGET_RECEIPT_SIZE` actually look like on disk,
     *  not an arbitrary byte array. [marker] only keeps the 19 fixtures from being byte-identical. */
    private fun paddedReceiptPdf(targetBytes: Long, marker: Int): ByteArray {
        fun build(padBytes: Int): ByteArray = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = org.apache.pdfbox.pdmodel.PDPage()
            doc.addPage(page)
            val padStream = doc.document.createCOSStream()
            padStream.createOutputStream().use { it.write(ByteArray(padBytes) { marker.toByte() }) }
            page.cosObject.setItem(org.apache.pdfbox.cos.COSName.getPDFName("LTPad"), padStream)
            val os = java.io.ByteArrayOutputStream()
            doc.save(os)
            os.toByteArray()
        }

        var pad = 0
        var bytes = build(pad)
        repeat(5) {
            val delta = targetBytes - bytes.size
            if (delta == 0L) return bytes
            pad = (pad + delta).toInt().coerceAtLeast(0)
            bytes = build(pad)
        }
        error("test fixture did not converge to the exact target size, got ${bytes.size}, wanted $targetBytes")
    }

    // The budget this project actually promises (IR-1): 19 receipts (the per-invoice cap) at
    // 440 KiB each — real fixture sizes, not a placeholder count — must still produce ONE
    // deduplicated BG-24 entry per receipt and a KoSIT-VALID document. The PDF-side half of this
    // same budget (final container ≤ 20 MiB, exactly one embedded file) is proven separately —
    // this class never renders a PDF, only the XML XmlExportServiceImpl.generate produces.
    @Test
    fun `receipts - the 19-receipt budget produces 19 deduped BG-24 entries and stays VALID`() {
        val receiptCount = de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE
        val receiptSizeBytes = 440L * 1024
        val seeded = (1..receiptCount).associate { i ->
            "receipt-pool/beleg-$i.pdf" to paddedReceiptPdf(receiptSizeBytes, marker = i)
        }
        val store = SeededDocumentStore(seeded)
        val receipts = (1..receiptCount).map { i -> ReceiptDto(fileName = "beleg-$i.pdf", objectKey = "receipt-pool/beleg-$i.pdf") }
        val invoice = b2bInvoiceDto().copy(receipts = receipts)

        val xml = String(XmlExportServiceImpl(store).generate(invoice), Charsets.UTF_8)

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Das 19-Belege-Budget muss ein gültiges Dokument bleiben. Befunde: " +
                result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )

        val attachmentCount = Regex("<ram:AttachmentBinaryObject").findAll(xml).count()
        assertEquals(receiptCount, attachmentCount, "erwartet genau $receiptCount AttachmentBinaryObject-Elemente (eines je Beleg)")
        val typeCode916Count = Regex("<ram:TypeCode>916</ram:TypeCode>").findAll(xml).count()
        assertEquals(receiptCount, typeCode916Count, "erwartet genau $receiptCount deduplizierte BG-24-Einträge")
    }

    // ── BT-13 split from BT-10 — both cases over the real export path ──────────────────────
    //
    // BT-13 is **optional** in EN 16931. That's the sentence the whole split rests on: without
    // it, a document with no captured purchase order number would become non-conformant after
    // the split, trading one rule violation for another. Verified rather than assumed — and on
    // the document this application actually produces, not a hand-built string.

    @Test
    fun `BT-13 - a document carrying a purchase order reference next to the Leitweg-ID is VALID`() {
        val base = b2bInvoiceDto()
        val invoice = base.copy(
            references = base.references.copy(
                // A public-sector buyer: BT-10 is the Leitweg-ID …
                buyerReference = "04011000-1234512345-06",
                // … and BT-13 the buyer's purchase order number, distinct from it.
                purchaseOrderReference = "BST-2026-0042",
            ),
        )
        val xml = String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Befunde: " + result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )

        assertTrue(
            compact.contains("<ram:BuyerReference>04011000-1234512345-06</ram:BuyerReference>"),
            "BT-10 fehlt im geprüften Beleg",
        )
        assertTrue(
            compact.contains("<ram:BuyerOrderReferencedDocument><ram:IssuerAssignedID>BST-2026-0042</ram:IssuerAssignedID></ram:BuyerOrderReferencedDocument>"),
            "BT-13 fehlt im geprüften Beleg",
        )
    }

    @Test
    fun `BT-13 - the same document without a purchase order reference is VALID too, and keeps BT-10`() {
        // The case that would mean nothing without the previous one: BT-13 is optional, omitting
        // it does not invalidate the document — and BT-10 stays untouched, it does not
        // disappear with it.
        val base = b2bInvoiceDto()
        val invoice = base.copy(references = base.references.copy(buyerReference = "04011000-1234512345-06", purchaseOrderReference = null))
        val xml = String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "BT-13 ist optional — ohne erfasste Bestellnummer muss der Beleg konform bleiben. Befunde: " +
                result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )

        assertFalse(compact.contains("<ram:BuyerOrderReferencedDocument>"), "BT-13 darf ohne Wert kein Element erzeugen")
        assertTrue(
            compact.contains("<ram:BuyerReference>04011000-1234512345-06</ram:BuyerReference>"),
            "BT-10 muss unverändert übermittelt werden",
        )
    }

    // ── BT-11 (project reference), over the real export path ───────────────────────────────

    /**
     * BT-11 is `BOUND` in the B2B catalog. Deliberately a `.copy()` of [b2bInvoiceDto], not a
     * change to the fixture's default — BT-11 is unrelated to the fixture's `b2bValues` toggle,
     * and touching that default would also require rewriting `CommunityGoldenFile`'s byte-exact
     * comparison in [de.littletrotter.xpdf.impl.mustang.En16931TermsExportTest] for no benefit.
     */
    @Test
    fun `BT-11 - a document carrying a project reference is VALID and the CII element carries the value`() {
        val base = b2bInvoiceDto()
        val invoice = base.copy(references = base.references.copy(projectNumber = "PRJ-2026-0099"))
        val xml = String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Befunde: " + result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )
        assertTrue(
            compact.contains(
                "<ram:SpecifiedProcuringProject><ram:ID>PRJ-2026-0099</ram:ID>" +
                    "<ram:Name>Project</ram:Name></ram:SpecifiedProcuringProject>",
            ),
            "BT-11 fehlt im geprüften Beleg: $compact",
        )
    }

    @Test
    fun `BT-11 - the same document without a project reference is VALID too and carries no element`() {
        val xml = String(XmlExportServiceImpl().generate(b2bInvoiceDto()), Charsets.UTF_8)

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "BT-11 ist optional. Befunde: " + result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )
        assertFalse(xml.contains("<ram:SpecifiedProcuringProject>"), "BT-11 darf ohne Wert kein Element erzeugen")
    }

    // ── BG-13 is EN 16931-conformant optional ─────────────

    /**
     * A delivery party (BG-13) must not turn an otherwise-conformant document invalid. Built on
     * the same real export path as the T7 cases above (`InvoiceDto -> MustangTransactionBuilder
     * -> XmlExportServiceImpl`), not a hand-built XML string.
     */
    @Test
    fun `AP-1c - a document carrying a shipTo delivery party stays VALID`() {
        val invoice = b2bInvoiceDto().copy(
            shipTo = ShipToDto(
                name = "Acme AG", street = "Lieferstraße 3", postalCode = "10117", city = "Berlin", country = null,
            ),
        )
        val xml = String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)

        val result = validator.validate(xml)

        assertEquals(
            KositOutcome.VALID, result.status,
            "Ein BG-13-Lieferpartner ist EN-16931-konform optional. Befunde: " +
                result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )
        assertTrue(xml.contains("ram:ShipToTradeParty"), "der Nachweis soll auf einem Beleg laufen, der wirklich einen shipTo trägt")
    }

    @Test
    fun `T7 - a seller without BT-42 is rejected, so the fixture's phone number is load-bearing`() {
        // A proof that would hold even without checking anything is no proof: BT-42 from
        // [b2bInvoiceDto] is not incidental, it's the difference between VALID and INVALID
        // (BR-DE-6). Without this case the fixture could silently be gutted at any time.
        val invoice = b2bInvoiceDto()
        val withoutPhone = invoice.copy(senderProfile = invoice.senderProfile?.copy(phone = null))
        val xml = String(XmlExportServiceImpl().generate(withoutPhone), Charsets.UTF_8)

        val result = validator.validate(xml)

        assertEquals(KositOutcome.INVALID, result.status, "war: ${result.summary}")
        assertTrue(result.details.any { it.ruleId == "BR-DE-6" }, "erwartet BR-DE-6, war: ${result.details}")
    }

    // ── The sign normalization at the export boundary really produces a
    // BR-27/BR-28-conformant document (non-negative BT-146) for a rebate line, real export path.

    /**
     * A discount/rebate position (negative `unitPriceNet` in the domain, see the B1 comment on
     * `InvoiceItem.validate`) exported next to a regular line. Before this normalization existed, this line's
     * negative unit price alone would already violate BR-27/BR-28 (unit price must be
     * non-negative); the export normalization ([de.littletrotter.xpdf.impl.mustang.MustangExportModelMapper])
     * moves the sign onto the quantity instead, so the whole document stays KoSIT `VALID`.
     */
    @Test
    fun `NEKAR-T4 - a rebate line normalized to a negative quantity is VALID`() {
        val base = b2bInvoiceDto()
        val invoice = base.copy(
            items = base.items + InvoiceItemDto(
                positionId = "2",
                description = "Mengenrabatt",
                quantity = BigDecimal("1"),
                unitPriceNet = Money(BigDecimal("-50.00")),
                unitCode = "C62",
                taxRatePercent = BigDecimal("19"),
                taxCategory = "S",
                netAmount = Money(BigDecimal("-50.00")),
                vatAmount = Money(BigDecimal("-9.50")),
            ),
            totals = base.totals.copy(
                netAmount = Money(BigDecimal("750.00")),
                vatAmount = Money(BigDecimal("142.50")),
                grossAmount = Money(BigDecimal("892.50")),
            ),
        )
        val xml = String(XmlExportServiceImpl().generate(invoice), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = validator.validate(xml)
        assertEquals(
            KositOutcome.VALID, result.status,
            "Rabattposition (negative BT-129, nicht-negativer BT-146) muss konform sein. Befunde: " +
                result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" },
        )
        assertTrue(compact.contains("<ram:BilledQuantity unitCode=\"C62\">-1.0000</ram:BilledQuantity>"), compact)
        assertTrue(compact.contains("<ram:ChargeAmount>50.0000</ram:ChargeAmount>"), compact)
    }

    private fun standardRateXml(): String = validCiiXml()
        .replace("<ram:CategoryCode>E</ram:CategoryCode>\n                  <ram:RateApplicablePercent>0</ram:RateApplicablePercent>",
            "<ram:CategoryCode>S</ram:CategoryCode>\n                  <ram:RateApplicablePercent>19</ram:RateApplicablePercent>")
        .replace("""              <ram:ApplicableTradeTax>
                <ram:CalculatedAmount>0.00</ram:CalculatedAmount>
                <ram:TypeCode>VAT</ram:TypeCode>
                <ram:BasisAmount>100.00</ram:BasisAmount>
                <ram:CategoryCode>E</ram:CategoryCode>
                <ram:ExemptionReasonCode>VATEX-EU-AE</ram:ExemptionReasonCode>
                <ram:RateApplicablePercent>0</ram:RateApplicablePercent>
              </ram:ApplicableTradeTax>""",
            """              <ram:ApplicableTradeTax>
                <ram:CalculatedAmount>19.00</ram:CalculatedAmount>
                <ram:TypeCode>VAT</ram:TypeCode>
                <ram:BasisAmount>100.00</ram:BasisAmount>
                <ram:CategoryCode>S</ram:CategoryCode>
                <ram:RateApplicablePercent>19</ram:RateApplicablePercent>
              </ram:ApplicableTradeTax>""")
        .replace("<ram:TaxTotalAmount currencyID=\"EUR\">0.00</ram:TaxTotalAmount>", "<ram:TaxTotalAmount currencyID=\"EUR\">19.00</ram:TaxTotalAmount>")
        .replace("<ram:GrandTotalAmount>100.00</ram:GrandTotalAmount>", "<ram:GrandTotalAmount>119.00</ram:GrandTotalAmount>")
        .replace("<ram:DuePayableAmount>100.00</ram:DuePayableAmount>", "<ram:DuePayableAmount>119.00</ram:DuePayableAmount>")

    private fun reverseChargeXml(): String = validCiiXml()
        .replace("<ram:CategoryCode>E</ram:CategoryCode>\n                  <ram:RateApplicablePercent>0</ram:RateApplicablePercent>",
            "<ram:CategoryCode>AE</ram:CategoryCode>\n                  <ram:RateApplicablePercent>0</ram:RateApplicablePercent>")
        .replace("<ram:CategoryCode>E</ram:CategoryCode>\n                <ram:ExemptionReasonCode>VATEX-EU-AE</ram:ExemptionReasonCode>",
            "<ram:CategoryCode>AE</ram:CategoryCode>\n                <ram:ExemptionReasonCode>VATEX-EU-AE</ram:ExemptionReasonCode>")
        .replace(
            """                <ram:URIUniversalCommunication>
                  <ram:URIID schemeID="EM">buyer@example.com</ram:URIID>
                </ram:URIUniversalCommunication>
              </ram:BuyerTradeParty>""",
            """                <ram:URIUniversalCommunication>
                  <ram:URIID schemeID="EM">buyer@example.com</ram:URIID>
                </ram:URIUniversalCommunication>
                <ram:SpecifiedTaxRegistration>
                  <ram:ID schemeID="VA">DE999999999</ram:ID>
                </ram:SpecifiedTaxRegistration>
              </ram:BuyerTradeParty>""",
        )

    /** [reverseChargeXml] with an added `<ram:ExemptionReason>` (BT-120): the composed
     *  "German original — English standard text" shape [de.littletrotter.xpdf.impl
     *  .composeTaxNote] produces for the wire. Inserted between `TypeCode` and `BasisAmount`,
     *  the position both `E12_Innergemeinschaftliche_Lieferungen` and
     *  `E25_steuerbefreite_Auslandslieferung` use. */
    private fun reverseChargeXmlWithComposedBt120(): String = reverseChargeXml()
        .replace(
            """                <ram:CalculatedAmount>0.00</ram:CalculatedAmount>
                <ram:TypeCode>VAT</ram:TypeCode>
                <ram:BasisAmount>100.00</ram:BasisAmount>
                <ram:CategoryCode>AE</ram:CategoryCode>""",
            """                <ram:CalculatedAmount>0.00</ram:CalculatedAmount>
                <ram:TypeCode>VAT</ram:TypeCode>
                <ram:ExemptionReason>Steuerschuldnerschaft aus § 3a Abs. 2 UStG. — Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).</ram:ExemptionReason>
                <ram:BasisAmount>100.00</ram:BasisAmount>
                <ram:CategoryCode>AE</ram:CategoryCode>""",
        )

    private fun publicSectorXml(): String = validCiiXml()
        .replace("<ram:BuyerReference>buyer-ref-123</ram:BuyerReference>", "<ram:BuyerReference>04011000-1234512345-06</ram:BuyerReference>")

    // ── EXTENDED URN — why ExportValidationStages skips the KoSIT stage for that profile ────
    //
    // The bundled XRechnung 3.0.2 scenarios.xml has no scenario matching the EXTENDED guideline
    // URN (only the XRechnung URNs and the plain "urn:cen.eu:en16931:2017" EN16931-CII URN are
    // known). An otherwise-KoSIT-clean document, run only with its guideline URN swapped to
    // EXTENDED, is *not* VALID here — this is the permanent, observed proof for why
    // ExportValidationStages.runKositStage skips KoSIT for that profile instead of letting this
    // engine reject a document the Factur-X-EXTENDED Schematron already accepted.
    @Test
    fun `EXTENDED guideline URN is not VALID under the XRechnung 3-0-2 KoSIT configuration`() {
        val xml = validCiiXml().replace(
            "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0",
            "urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended",
        )

        val result = validator.validate(xml)

        assertTrue(
            result.status != KositOutcome.VALID,
            "erwartet: kein KoSIT-Szenario für die EXTENDED-Leitlinie, also nicht VALID; war: ${result.status} ${result.summary}",
        )
    }
}
