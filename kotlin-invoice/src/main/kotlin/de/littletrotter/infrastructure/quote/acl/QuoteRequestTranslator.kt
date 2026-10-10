package de.littletrotter.infrastructure.quote.acl

import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.shared.quote.AddAttachmentLibraryVersionRequest
import de.littletrotter.shared.quote.AttachFromLibraryRequest
import de.littletrotter.shared.quote.AttachQuoteFileRequest
import de.littletrotter.shared.quote.CreateAttachmentLibraryEntryRequest
import de.littletrotter.shared.quote.EditQuoteHeaderRequest
import de.littletrotter.shared.quote.EditQuoteItemsRequest
import de.littletrotter.shared.quote.QuoteItemLineRequest
import de.littletrotter.shared.quote.RequestQuoteDocumentRequest
import de.littletrotter.shared.quote.UpdateQuoteSettingsRequest
import java.util.Base64

/**
 * Write side of the anti-corruption layer: local commands become contract request bodies, local
 * filters become REST parameters. Enums go out by name, the same text the component reads back.
 */
object QuoteRequestTranslator {

    /** The list parameters; an unset status and blank text are left out, order, offset and limit always sent. */
    fun toListParameters(criteria: QuoteListCriteria): List<Pair<String, String>> =
        buildList {
            criteria.status?.let { add("status" to it.name) }
            criteria.text?.takeIf { it.isNotBlank() }?.let { add("text" to it) }
            add("sort" to criteria.sortField.name)
            add("dir" to criteria.sortDirection.name)
            add("offset" to criteria.offset.toString())
            add("limit" to criteria.limit.toString())
        }

    /**
     * The statistics selection as query parameters: the year always, quarter or month only when
     * chosen, and a status or customer type only when the page narrows by it.
     */
    fun toStatisticsParameters(selection: QuoteStatisticsSelection): List<Pair<String, String>> =
        buildList {
            add("year" to selection.year.toString())
            selection.quarter?.let { add("quarter" to it.toString()) }
            selection.month?.let { add("month" to it.toString()) }
            selection.status?.let { add("status" to it.name) }
            selection.customerType?.let { add("customerType" to it.name) }
        }

    /** The ranking parameters: the selection's plus what is ranked and how many lines are wanted. */
    fun toRankingParameters(
        kind: QuoteRankingKind,
        selection: QuoteStatisticsSelection,
        limit: Int,
    ): List<Pair<String, String>> =
        toStatisticsParameters(selection) + listOf("kind" to kind.name, "limit" to limit.toString())

    /** One repeated `kind` parameter per requested kind, in enum order so the URL is stable. */
    fun toLogParameters(kinds: Set<QuoteLogKind>): List<Pair<String, String>> =
        QuoteLogKind.entries.filter { it in kinds }.map { "kind" to it.name }

    fun toHeaderRequest(command: EditQuoteHeaderCommand): EditQuoteHeaderRequest =
        EditQuoteHeaderRequest(
            validUntil = command.validUntil,
            priceDisplay = command.priceDisplay.name,
            subject = command.subject,
            introText = command.introText,
            closingText = command.closingText,
            noteText = command.noteText,
            b2bValues = command.b2bValues,
        )

    fun toItemsRequest(lines: List<QuoteItemInput>): EditQuoteItemsRequest =
        EditQuoteItemsRequest(lines.map(::toLine))

    fun toAttachRequest(upload: QuoteAttachmentUpload): AttachQuoteFileRequest =
        AttachQuoteFileRequest(upload.kind.name, upload.fileName, upload.mimeType, Base64.getEncoder().encodeToString(upload.content), upload.itemId)

    fun toCreateEntryRequest(entry: NewAttachmentLibraryEntry): CreateAttachmentLibraryEntryRequest =
        CreateAttachmentLibraryEntryRequest(
            title = entry.title,
            description = entry.description,
            fileName = entry.file.fileName,
            mimeType = entry.file.mimeType,
            contentBase64 = Base64.getEncoder().encodeToString(entry.file.content),
            validFrom = entry.file.validFrom,
        )

    fun toAddVersionRequest(file: AttachmentLibraryFile): AddAttachmentLibraryVersionRequest =
        AddAttachmentLibraryVersionRequest(
            fileName = file.fileName,
            mimeType = file.mimeType,
            contentBase64 = Base64.getEncoder().encodeToString(file.content),
            validFrom = file.validFrom,
        )

    fun toAttachFromLibraryRequest(command: AttachLibraryDocumentCommand): AttachFromLibraryRequest =
        AttachFromLibraryRequest(command.versionId, command.itemId)

    private fun toLine(line: QuoteItemInput): QuoteItemLineRequest =
        QuoteItemLineRequest(
            description = line.description,
            quantity = line.quantity,
            category = line.category.name,
            unit = line.unit.name,
            unitPrice = line.unitPrice,
            vatCategory = line.vatCategory.name,
            vatRate = line.vatRate,
            id = line.id,
            articleId = line.articleId,
            articleNumber = line.articleNumber,
            customerReference = line.customerReference,
            customerArticleName = line.customerArticleName,
        )

    fun toDocumentRequest(mark: QuoteDocumentMark): RequestQuoteDocumentRequest = RequestQuoteDocumentRequest(mark.name)

    fun toSettingsRequest(validityDays: Long, xmlFormat: QuoteXmlFormat): UpdateQuoteSettingsRequest =
        UpdateQuoteSettingsRequest(validityDays, xmlFormat.name)
}
