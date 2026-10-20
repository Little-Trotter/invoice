package de.littletrotter.shared.quote

/**
 * Resource paths of the offer component, relative to its `/quote` root path. The host builds its
 * URLs from these constants, the component declares its routes with them, so a path cannot drift
 * apart between the two.
 */
object QuoteApiPaths {
    const val QUOTES = "/api/quotes"
    const val STATISTICS = "/statistics"
    const val STATISTICS_DASHBOARD = "/statistics/dashboard"
    const val STATISTICS_RANKING = "/statistics/ranking"
    const val BY_ID = "/{id}"
    const val BY_NUMBER = "/by-number/{number}"
    const val HEADER = "/{id}/header"
    const val ITEMS = "/{id}/items"
    const val SUBMIT = "/{id}/submit"
    const val RETURN_TO_DRAFT = "/{id}/return-to-draft"
    const val CANCEL = "/{id}/cancel"
    const val SEND = "/{id}/send"
    const val SEND_GATE = "/{id}/send-gate"
    const val ACCEPT = "/{id}/accept"
    const val REQUEST_INVOICE = "/{id}/invoice-order"
    const val REJECT = "/{id}/reject"
    const val NOTES = "/{id}/notes"
    const val LOG = "/{id}/log"
    const val ARTICLES = "/{id}/articles"
    const val HEADER_FIELDS = "/{id}/header-fields"
    const val ATTACHMENTS = "/{id}/attachments"
    const val ATTACHMENT_CONTENT = "/{id}/attachments/{aid}/content"
    const val ATTACHMENT = "/{id}/attachments/{aid}"
    const val ATTACHMENT_ITEM = "/{id}/attachments/{aid}/item"
    const val ATTACHMENTS_FROM_LIBRARY = "/{id}/attachments/from-library"
    const val ATTACHMENT_VERSION = "/{id}/attachments/{aid}/version"
    const val DOCUMENTS = "/{id}/documents"
    const val DOCUMENT_VALIDATION_REPORT = "/{id}/documents/{did}/validation-report"
    const val DOCUMENT_PDF = "/{id}/documents/{did}/pdf"
    const val DOCUMENT_XML = "/{id}/documents/{did}/xml"
    const val ATTACHMENT_LIBRARY = "/api/attachment-library"
    const val LIBRARY_VERSIONS = "/{id}/versions"
    const val LIBRARY_ARCHIVE = "/{id}/archive"
    const val LIBRARY_VERSION_CONTENT = "/{id}/versions/{vid}/content"
    const val SETTINGS = "/api/settings"
    const val LOOKUP_CUSTOMERS = "/api/lookup/customers"
    const val REPLICA_RESYNC = "/api/admin/replicas/resync"
    const val DOCUMENT_BACKFILL = "/api/admin/documents/backfill"
}
