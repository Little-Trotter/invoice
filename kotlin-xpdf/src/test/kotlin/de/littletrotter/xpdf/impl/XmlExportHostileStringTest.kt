package de.littletrotter.xpdf.impl

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.shared.ShipToDto
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.stream.Stream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Security regression: every free-text property of [InvoiceDto] must survive
 * `XmlExportServiceImpl.generate` as well-formed, schema-valid XML whose text content equals the
 * input (characters are escaped, never stripped). Fields are discovered by marker: a field is
 * only asserted when a benign marker value actually shows up in the XML (not every DTO field is
 * exported); the hostile run for that field must then read back unchanged.
 */
class XmlExportHostileStringTest {

    class Field(val name: String, val set: (InvoiceDto, String) -> InvoiceDto) {
        override fun toString() = name
    }

    companion object {
        private const val MARKER = "ZZMARKERZZ"

        val HOSTILE_STRINGS = listOf(
            "<img src=x onerror=alert(1)>",
            "<script>alert(1)</script>",
            "&",
            "&amp; &lt;b&gt; &#60;",
            "\"",
            "'",
            "]]>",
            "<![CDATA[x]]>",
            "a\tb\nc\rd",
            "A".repeat(5000),
        )

        private fun base() = InvoiceDto(
            invoiceNumber = InvoiceNumberDto("HOSTILE-001"),
            dates = InvoiceDatesDto(invoiceDate = "2026-04-01", dueDate = "2026-04-15", paymentTermsDays = 14),
            senderProfile = PartyDto(
                name = "Sender GmbH", street = "Hauptstr. 1", postalCode = "10115", city = "Berlin",
                country = "DE", vatId = "DE123456789", iban = "DE02120300000000202051", bic = "BYLADEM1001",
            ),
            recipient = PartyDto(name = "Empfaenger AG", street = "Neue Str. 2", postalCode = "80331", city = "Munich", country = "DE"),
            items = listOf(
                InvoiceItemDto(
                    positionId = "1", description = "Consulting", quantity = BigDecimal("1"),
                    taxRatePercent = BigDecimal("19"), unitPriceNet = Money(BigDecimal("100.00")),
                    netAmount = Money(BigDecimal("100.00")),
                ),
            ),
        )

        private fun party(sender: Boolean, f: (PartyDto, String) -> PartyDto): (InvoiceDto, String) -> InvoiceDto = { i, v ->
            if (sender) i.copy(senderProfile = f(i.senderProfile!!, v)) else i.copy(recipient = f(i.recipient!!, v))
        }

        private fun item(f: (InvoiceItemDto, String) -> InvoiceItemDto): (InvoiceDto, String) -> InvoiceDto = { i, v ->
            i.copy(items = listOf(f(i.items[0], v)))
        }

        private fun partyFields(prefix: String, sender: Boolean) = listOf(
            Field("$prefix.name", party(sender) { p, v -> p.copy(name = v) }),
            Field("$prefix.street", party(sender) { p, v -> p.copy(street = v) }),
            Field("$prefix.postalCode", party(sender) { p, v -> p.copy(postalCode = v) }),
            Field("$prefix.city", party(sender) { p, v -> p.copy(city = v) }),
            Field("$prefix.email", party(sender) { p, v -> p.copy(email = v) }),
            Field("$prefix.phone", party(sender) { p, v -> p.copy(phone = v) }),
            Field("$prefix.taxNumber", party(sender) { p, v -> p.copy(taxNumber = v) }),
            Field("$prefix.globalId", party(sender) { p, v -> p.copy(globalId = v) }),
            Field("$prefix.customerNumber", party(sender) { p, v -> p.copy(customerNumber = v) }),
            Field("$prefix.bankName", party(sender) { p, v -> p.copy(bankName = v) }),
            Field("$prefix.managingDirector", party(sender) { p, v -> p.copy(managingDirector = v) }),
            Field("$prefix.courtOfJurisdiction", party(sender) { p, v -> p.copy(courtOfJurisdiction = v) }),
            Field("$prefix.registerCourt", party(sender) { p, v -> p.copy(registerCourt = v) }),
            Field("$prefix.commercialRegisterNumber", party(sender) { p, v -> p.copy(commercialRegisterNumber = v) }),
            Field("$prefix.footerNote", party(sender) { p, v -> p.copy(footerNote = v) }),
            Field("$prefix.website", party(sender) { p, v -> p.copy(website = v) }),
            Field("$prefix.registeredSeat", party(sender) { p, v -> p.copy(registeredSeat = v) }),
            Field("$prefix.siret", party(sender) { p, v -> p.copy(siret = v) }),
        )

        val FIELDS: List<Field> = listOf(
            Field("invoiceNumber", { i, v -> i.copy(invoiceNumber = InvoiceNumberDto(v)) }),
            Field("meta.freeText", { i, v -> i.copy(meta = i.meta.copy(freeText = v)) }),
            Field("meta.consumerNote", { i, v -> i.copy(meta = i.meta.copy(consumerNote = v)) }),
            Field("meta.languageId", { i, v -> i.copy(meta = i.meta.copy(languageId = v)) }),
            Field("meta.documentStatusReasonCode", { i, v -> i.copy(meta = i.meta.copy(documentStatusReasonCode = v)) }),
            Field("dates.paymentTermName", { i, v -> i.copy(dates = i.dates.copy(paymentTermName = v)) }),
            Field("references.buyerReference", { i, v -> i.copy(references = i.references.copy(buyerReference = v)) }),
            Field("references.purchaseOrderReference", { i, v -> i.copy(references = i.references.copy(purchaseOrderReference = v)) }),
            Field("references.orderReference", { i, v -> i.copy(references = i.references.copy(orderReference = v)) }),
            Field("references.offerReference", { i, v -> i.copy(references = i.references.copy(offerReference = v)) }),
            Field("references.projectNumber", { i, v -> i.copy(references = i.references.copy(projectNumber = v)) }),
            Field("references.costCenter", { i, v -> i.copy(references = i.references.copy(costCenter = v)) }),
            Field("references.originalInvoiceNumber", { i, v ->
                i.copy(references = i.references.copy(originalInvoiceNumber = v, originalInvoiceDate = "2026-03-01"))
            }),
            Field("b2bValues.BT_19", { i, v -> i.copy(b2bValues = listOf(B2bValueDto(B2bTerm.BT_19, v))) }),
            Field("shipTo.name", { i, v -> i.copy(shipTo = (i.shipTo ?: ShipToDto()).copy(name = v)) }),
            Field("shipTo.street", { i, v -> i.copy(shipTo = (i.shipTo ?: ShipToDto(name = "Ship")).copy(street = v)) }),
            Field("shipTo.postalCode", { i, v -> i.copy(shipTo = (i.shipTo ?: ShipToDto(name = "Ship")).copy(postalCode = v)) }),
            Field("shipTo.city", { i, v -> i.copy(shipTo = (i.shipTo ?: ShipToDto(name = "Ship")).copy(city = v)) }),
            Field("item.description", item { li, v -> li.copy(description = v) }),
            Field("item.itemDescription", item { li, v -> li.copy(itemDescription = v) }),
            Field("item.articleNumber", item { li, v -> li.copy(articleNumber = v) }),
            Field("item.buyerArticleDescription", item { li, v -> li.copy(buyerArticleDescription = v) }),
            Field("item.buyerItemDescription", item { li, v -> li.copy(buyerItemDescription = v) }),
            Field("item.costCenter", item { li, v -> li.copy(costCenter = v) }),
            Field("item.unitLabel", item { li, v -> li.copy(unitLabel = v) }),
            Field("item.productClassificationCode", item { li, v ->
                li.copy(productClassificationCode = v, productClassificationScheme = "HS")
            }),
            Field("item.exemptionReasonText", item { li, v -> li.copy(exemptionReasonText = v) }),
            Field("item.taxLegalBasis", item { li, v -> li.copy(taxLegalBasis = v) }),
            Field("receipt.fileName", { i, v -> i.copy(receipts = listOf(ReceiptDto(fileName = v, objectKey = "k"))) }),
        ) + partyFields("sender", true) + partyFields("recipient", false)

        @JvmStatic
        fun cases(): Stream<Arguments> =
            FIELDS.stream().flatMap { f -> HOSTILE_STRINGS.stream().map { Arguments.of(f, it) } }

        private fun parse(bytes: ByteArray) =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

        /** All text and attribute values of the document, so a read-back needs no per-field XPath. */
        private fun texts(node: Node, out: MutableList<String> = mutableListOf()): List<String> {
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) out += node.nodeValue
            node.attributes?.let { a -> for (n in 0 until a.length) out += a.item(n).nodeValue }
            var c = node.firstChild
            while (c != null) { texts(c, out); c = c.nextSibling }
            return out
        }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("cases")
    fun `hostile free text is escaped, preserved and schema valid`(field: Field, hostile: String) {
        val service = XmlExportServiceImpl()
        val markerXml = String(service.generate(field.set(base(), MARKER)), Charsets.UTF_8)
        val emitted = markerXml.contains(MARKER)

        val bytes = service.generate(field.set(base(), hostile))
        val doc = parse(bytes) // throws SAXParseException when not well-formed
        if (emitted) {
            // XML parsers normalise literal CR/CRLF to LF; compare with that normalisation applied.
            val expected = hostile.replace("\r\n", "\n").replace('\r', '\n')
            val all = texts(doc)
            assertTrue(
                all.any { it.contains(expected) || it.replace("\r\n", "\n").replace('\r', '\n').contains(expected) },
                "${field.name}: hostile value not read back unchanged",
            )
        }
        val xsd = CiiXsdValidator().apply { init() }.validate(String(bytes, Charsets.UTF_8))
        assertEquals(KositOutcome.VALID, xsd.outcome, "${field.name}: XSD findings ${xsd.findings}")
    }
}
