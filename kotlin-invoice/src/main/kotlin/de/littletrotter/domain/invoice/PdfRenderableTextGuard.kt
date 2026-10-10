package de.littletrotter.domain.invoice

import de.littletrotter.shared.documents.PdfRenderableText

/**
 * The PDF and the XML of an invoice must say the same thing, so a character the PDF fonts cannot
 * show is rejected up front instead of being dropped from one of the two. The PDF engine refuses
 * such a character outright, which — discovered only after the invoice has been issued — would
 * leave an issued invoice without a document. Entry forms check the same set
 * ([PdfRenderableText]); this guard is the second line of defence for data that bypassed a form
 * (imports, older drafts) and runs before the document job is created and again before the
 * status change.
 *
 * [additionalTexts] carries texts that live outside the invoice aggregate (B2B field values).
 *
 * Collects every text the document prints: header free text and note, references, payment term
 * text, party names and addresses, and all position texts.
 */
fun Invoice.pdfUnrenderableTextFields(additionalTexts: Map<String, String?> = emptyMap()): Map<String, List<String>> {
    val found = linkedMapOf<String, List<String>>()
    fun check(label: String, text: String?) {
        val bad = PdfRenderableText.firstOffending(text)
        if (bad.isNotEmpty()) found.putIfAbsent(label, bad)
    }
    additionalTexts.forEach { (label, text) -> check(label, text) }
    check("Freitext", freeText)
    check("Rechnungshinweis", invoiceNote)
    check("Privatkunden-Hinweis", consumerNote)
    check("Ursprüngliche Rechnungsnummer", originalInvoiceNumber)
    references.let {
        check("Käuferreferenz", it.buyerReference)
        check("Bestellnummer", it.purchaseOrderReference)
        check("Auftragsnummer", it.orderReference)
        check("Angebotsnummer", it.offerReference)
        check("Projektnummer", it.projectNumber)
        check("Kostenstelle", it.costCenter)
    }
    check("Zahlungsbedingung", paymentTermSnapshot?.name)
    partySnapshotList.forEach {
        check("Name (${it.partyRole.name.lowercase()})", it.name)
        check("Käuferreferenz", it.buyerReference)
    }
    effectiveSender()?.let { sender ->
        check("Absender: Name", sender.name)
        check("Absender: Bank", sender.bankName)
        check("Absender: Telefon", sender.phone)
        check("Absender: Geschäftsführung", sender.managingDirector)
        check("Absender: Handelsregister", sender.commercialRegisterNumber)
        check("Absender: Gerichtsstand", sender.courtOfJurisdiction)
        check("Absender: Registergericht", sender.registerCourt)
        check("Absender: Website", sender.website)
        check("Absender: Sitz", sender.registeredSeat)
    }
    recipient?.let { recipient ->
        check("Empfänger: Name", recipient.name)
        check("Empfänger: Ansprechpartner", recipient.contactPerson)
        check("Empfänger: Telefon", recipient.phone)
    }
    listOfNotNull(effectiveSenderBillingAddress(), effectiveRecipientBillingAddress(), effectiveRecipientDeliveryAddress())
        .forEach {
            check("Adresse: Straße", it.street)
            check("Adresse: Ort", it.city)
        }
    itemList.forEachIndexed { index, item ->
        val position = "Position ${item.positionId.ifBlank { (index + 1).toString() }}"
        check("$position: Bezeichnung", item.description)
        check("$position: Beschreibung", item.itemDescription)
        check("$position: Kundenbezeichnung", item.buyerItemDescription)
        check("$position: Lieferschein", item.deliveryNoteReference)
        check("$position: Kostenstelle", item.costCenter)
        check("$position: Befreiungstext", item.exemptionReasonText)
        check("$position: Rechtsgrundlage", item.taxLegalBasis)
        item.tripExpenses.forEach { check("$position: Reisekosten", it.description) }
        item.perDiemEntries.forEach {
            check("$position: Verpflegung", it.description)
            check("$position: Reisender", it.participant)
        }
    }
    return found
}

/** Rejects the invoice while any printed text holds a character the PDF fonts cannot render. */
fun Invoice.ensureTextRenderableInPdf(additionalTexts: Map<String, String?> = emptyMap()) {
    val fields = pdfUnrenderableTextFields(additionalTexts)
    if (fields.isNotEmpty()) throw PdfUnrenderableTextException(fields)
}

/**
 * Plain [RuntimeException] with a German user-facing message, like
 * [InvoiceDateBeforeServiceEndException]: a sealed hierarchy in `domain/exception/` cannot be
 * extended from this package. [fields] maps each affected field label to its offending characters.
 */
class PdfUnrenderableTextException(val fields: Map<String, List<String>>) : RuntimeException(
    "Die Rechnung enthält Zeichen, die im PDF nicht darstellbar sind: " +
        fields.entries.joinToString("; ") { (label, chars) -> "$label (${chars.joinToString(" ")})" } +
        ". Bitte die Zeichen entfernen oder ersetzen."
)
