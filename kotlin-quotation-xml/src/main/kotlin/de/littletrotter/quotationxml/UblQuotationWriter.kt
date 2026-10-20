package de.littletrotter.quotationxml

import de.littletrotter.shared.Money
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

internal const val UBL_QUOTATION_NS = "urn:oasis:names:specification:ubl:schema:xsd:Quotation-2"
internal const val UBL_CAC_NS = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
internal const val UBL_CBC_NS = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"

private const val TAX_SCHEME_ID = "VAT"
private const val LINE_EXTENSION_AMOUNT = "LineExtensionAmount"
private const val QUANTITY = "Quantity"
private const val UNIT_CODE = "unitCode"
private const val CURRENCY_ID = "currencyID"

/**
 * Writes an OASIS UBL 2.1 `Quotation` document. Element order follows the XSD sequence, which is
 * why the output is schema-valid by construction; [QuotationXsdValidator] proves it in the tests.
 *
 * Amounts are written with two decimals (the scale [Money] normalises to), quantities and rates
 * exactly as stated in the model. Accompanying files are referenced by name and SHA-256 and never embedded.
 */
class UblQuotationWriter : QuotationWriter {

    override fun write(model: QuotationModel): ByteArray {
        val out = ByteArrayOutputStream()
        val w = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8")
        w.writeStartDocument("UTF-8", "1.0")
        w.writeStartElement("", "Quotation", UBL_QUOTATION_NS)
        w.writeDefaultNamespace(UBL_QUOTATION_NS)
        w.writeNamespace("cac", UBL_CAC_NS)
        w.writeNamespace("cbc", UBL_CBC_NS)
        writeHeader(w, model)
        model.attachedDocuments.forEach { writeDocumentReference(w, it) }
        writeParty(w, "SellerSupplierParty", model.seller)
        writeParty(w, "BuyerCustomerParty", model.buyer)
        w.cac("TransactionConditions") { w.cbc("Description", model.disclaimer) }
        writeTaxTotal(w, model)
        writeMonetaryTotal(w, model)
        model.lines.forEach { writeLine(w, model, it) }
        w.writeEndElement()
        w.writeEndDocument()
        w.close()
        return out.toByteArray()
    }

    private fun writeHeader(w: XMLStreamWriter, model: QuotationModel) {
        w.cbc("UBLVersionID", "2.1")
        w.cbc("ID", model.number)
        w.cbc("IssueDate", model.issueDate.toString())
        model.notes.forEach { w.cbc("Note", it) }
        w.cbc("PricingCurrencyCode", model.currency.code)
        w.cbc("LineCountNumeric", model.lines.size.toString())
        w.cac("ValidityPeriod") { w.cbc("EndDate", model.validUntil.toString()) }
    }

    private fun writeDocumentReference(w: XMLStreamWriter, ref: QuotationDocumentReference) {
        w.cac("AdditionalDocumentReference") {
            w.cbc("ID", ref.id)
            ref.description?.let { w.cbc("DocumentDescription", it) }
            w.cac("Attachment") {
                w.cac("ExternalReference") {
                    w.cbc("DocumentHash", ref.sha256)
                    w.cbc("HashAlgorithmMethod", "SHA-256")
                    w.cbc("MimeCode", ref.mimeCode)
                    w.cbc("FileName", ref.fileName)
                }
            }
        }
    }

    private fun writeParty(w: XMLStreamWriter, wrapper: String, party: QuotationParty) {
        w.cac(wrapper) {
            w.cac("Party") {
                party.email?.let { w.cbc("EndpointID", it, "schemeID" to "EM") }
                w.cac("PartyName") { w.cbc("Name", party.name) }
                w.cac("PostalAddress") {
                    w.cbc("StreetName", party.street)
                    w.cbc("CityName", party.city)
                    w.cbc("PostalZone", party.postalCode)
                    w.cac("Country") { w.cbc("IdentificationCode", party.country.alpha2) }
                }
                party.vatId?.let { vat ->
                    w.cac("PartyTaxScheme") {
                        w.cbc("CompanyID", vat)
                        w.cac("TaxScheme") { w.cbc("ID", TAX_SCHEME_ID) }
                    }
                }
                w.cac("PartyLegalEntity") { w.cbc("RegistrationName", party.name) }
            }
        }
    }

    private fun writeTaxTotal(w: XMLStreamWriter, model: QuotationModel) {
        val currency = model.currency.code
        w.cac("TaxTotal") {
            w.amount("TaxAmount", model.totals.tax, currency)
            model.taxSubtotals.forEach { sub ->
                w.cac("TaxSubtotal") {
                    w.amount("TaxableAmount", sub.taxableAmount, currency)
                    w.amount("TaxAmount", sub.taxAmount, currency)
                    writeTaxCategory(w, "TaxCategory", sub.category.code, sub.rate)
                }
            }
        }
    }

    private fun writeMonetaryTotal(w: XMLStreamWriter, model: QuotationModel) {
        val currency = model.currency.code
        w.cac("QuotedMonetaryTotal") {
            w.amount(LINE_EXTENSION_AMOUNT, model.totals.net, currency)
            w.amount("TaxExclusiveAmount", model.totals.net, currency)
            w.amount("TaxInclusiveAmount", model.totals.gross, currency)
            w.amount("PayableAmount", model.totals.gross, currency)
        }
    }

    private fun writeLine(w: XMLStreamWriter, model: QuotationModel, line: QuotationLine) {
        val currency = model.currency.code
        val id = line.position.toString()
        w.cac("QuotationLine") {
            w.cbc("ID", id)
            w.cbc(QUANTITY, line.quantity.toPlainString(), UNIT_CODE to line.unitCode)
            w.amount(LINE_EXTENSION_AMOUNT, line.lineTotal, currency)
            w.cac("LineItem") {
                w.cbc("ID", id)
                w.cbc(QUANTITY, line.quantity.toPlainString(), UNIT_CODE to line.unitCode)
                w.amount(LINE_EXTENSION_AMOUNT, line.lineTotal, currency)
                w.cac("Price") { w.amount("PriceAmount", line.unitPrice, currency) }
                w.cac("Item") {
                    line.description?.let { w.cbc("Description", it) }
                    w.cbc("Name", line.name)
                    writeTaxCategory(w, "ClassifiedTaxCategory", line.vatCategory.code, line.vatRate)
                }
            }
        }
    }

    private fun writeTaxCategory(w: XMLStreamWriter, element: String, code: String, rate: BigDecimal) {
        w.cac(element) {
            w.cbc("ID", code)
            w.cbc("Percent", rate.toPlainString())
            w.cac("TaxScheme") { w.cbc("ID", TAX_SCHEME_ID) }
        }
    }
}

private fun XMLStreamWriter.cac(name: String, body: () -> Unit) {
    writeStartElement("cac", name, UBL_CAC_NS)
    body()
    writeEndElement()
}

private fun XMLStreamWriter.cbc(name: String, value: String, vararg attributes: Pair<String, String>) {
    writeStartElement("cbc", name, UBL_CBC_NS)
    attributes.forEach { (key, attr) -> writeAttribute(key, attr) }
    writeCharacters(value)
    writeEndElement()
}

private fun XMLStreamWriter.amount(name: String, value: Money, currency: String) =
    cbc(name, value.toString(), CURRENCY_ID to currency)
