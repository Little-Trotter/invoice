package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientDraft

/**
 * Input port for the "maintain a recipient" write use case: save (with its billing/optional
 * delivery address) and delete. The read half stays on [RecipientQueryPort].
 *
 * `saveRecipientWithAddresses` takes the dialog's [RecipientDraft] and returns the saved state as
 * a [RecipientDetailDto] — no entity crosses this boundary. Building/loading the `Recipient`
 * entity the draft is applied onto, and reconciling the VAT-id verification state, are entirely
 * this use case's own concern now; the caller only ever holds the draft and the read model.
 */
interface ManageRecipientUseCase : CommandEntryPoint {
    fun saveRecipientWithAddresses(draft: RecipientDraft): RecipientDetailDto

    /** WP2 (0.9.10) — [expectedVersion] is the recipient's own version the editor dialog
     *  captured on open; `null` (internal/non-UI callers) skips the staleness check. */
    fun deleteRecipient(id: Long, expectedVersion: Long? = null)
}
