package de.littletrotter.application.dto

/**
 * Read model for the "take over master data again" preview: what refreshing the invoice's seller
 * and buyer snapshots from the current master data would change. Computed read-only.
 */
data class PartySnapshotDiffDto(
    val sellerChanges: List<SnapshotFieldChangeDto>,
    val buyerChanges: List<SnapshotFieldChangeDto>,
) {
    val hasChanges: Boolean get() = sellerChanges.isNotEmpty() || buyerChanges.isNotEmpty()
}

/** One changed snapshot field; [field] is a display-only label, never re-parsed. */
data class SnapshotFieldChangeDto(
    val field: String,
    val oldValue: String?,
    val newValue: String?,
)
