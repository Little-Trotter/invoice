package de.littletrotter.application.quote.port.inbound

import java.time.Instant
import java.time.LocalDate

/** One immutable file version of a reusable offer document. */
data class AttachmentLibraryVersion(
    val id: String,
    val versionNo: Int,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val validFrom: LocalDate,
    val uploadedAt: Instant,
    val uploadedBy: String,
)

/** A reusable offer document with its versions, newest first; [currentVersion] is the first of them. */
data class AttachmentLibraryEntry(
    val id: String,
    val title: String,
    val description: String,
    val currentVersion: AttachmentLibraryVersion,
    val versions: List<AttachmentLibraryVersion>,
    val usedByQuotes: Int,
    val archived: Boolean,
)

/** A file as the user picked it for the library; a null [validFrom] lets the component choose today. */
class AttachmentLibraryFile(
    val fileName: String,
    val mimeType: String,
    val content: ByteArray,
    val validFrom: LocalDate? = null,
)

/** A new library entry together with its first file. */
class NewAttachmentLibraryEntry(val title: String, val description: String, val file: AttachmentLibraryFile)

/** Takes a library version over into a draft offer, optionally tied to one of its lines. */
data class AttachLibraryDocumentCommand(val quoteId: String, val versionId: String, val itemId: String? = null)

/** Swaps the library version an attachment of a draft offer carries. */
data class ReplaceLibraryVersionCommand(val quoteId: String, val attachmentId: String, val versionId: String)
