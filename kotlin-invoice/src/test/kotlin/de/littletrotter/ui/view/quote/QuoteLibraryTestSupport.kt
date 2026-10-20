package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryVersion
import java.time.Instant
import java.time.LocalDate

internal fun libraryVersion(id: String, versionNo: Int, fileName: String = "agb-$versionNo.pdf") = AttachmentLibraryVersion(
    id = id,
    versionNo = versionNo,
    fileName = fileName,
    mimeType = "application/pdf",
    sizeBytes = 2048,
    sha256 = "b".repeat(64),
    validFrom = LocalDate.of(2026, 10, 1),
    uploadedAt = Instant.parse("2026-10-01T09:00:00Z"),
    uploadedBy = "anna",
)

/** An entry whose versions are given oldest first; the newest one is the current version. */
internal fun libraryEntry(id: String, title: String, vararg versions: AttachmentLibraryVersion, archived: Boolean = false, usedBy: Int = 0) =
    AttachmentLibraryEntry(
        id = id,
        title = title,
        description = "$title (Beschreibung)",
        currentVersion = versions.maxBy { it.versionNo },
        versions = versions.sortedByDescending { it.versionNo },
        usedByQuotes = usedBy,
        archived = archived,
    )
