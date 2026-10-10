package de.littletrotter.domain.invoice

// Extension rather than member: keeps Invoice under detekt's TooManyFunctions threshold.
/**
 * WP1 (0.9.10, concurrent-editing) — first-committed-writer-wins guard shared by every
 * item/trip write path ([de.littletrotter.application.invoice.InvoiceItemMutationService]/
 * [de.littletrotter.application.invoice.TripService]): item/trip commands are versioned via
 * the owning invoice aggregate, not their own column, so the check mirrors
 * [de.littletrotter.application.invoice.InvoiceHeaderUpdateService.updateHeader]/
 * [de.littletrotter.application.invoice.ReceiptLinkService]'s own inline check one level down.
 * `expectedVersion == null` skips the check — internal/non-UI callers that never carry one
 * keep today's last-writer-wins behaviour, the same null semantics as
 * [de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand.expectedVersion].
 */
fun Invoice.requireExpectedVersion(expectedVersion: Long?) {
    if (expectedVersion != null && version != null && version != expectedVersion) {
        throw jakarta.persistence.OptimisticLockException(
            "Invoice $id was modified concurrently (expected version $expectedVersion, found $version)",
        )
    }
}
