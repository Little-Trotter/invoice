package de.littletrotter.fixture

import de.littletrotter.domain.message.SystemMessageReceipt

/**
 * Test-only write access to a [SystemMessageReceipt] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun SystemMessageReceipt.edit(block: SystemMessageReceiptEditor.() -> Unit): SystemMessageReceipt = also { SystemMessageReceiptEditor(it).block() }

/** Property-style access to every persisted field of a [SystemMessageReceipt]. */
class SystemMessageReceiptEditor internal constructor(internal val entity: SystemMessageReceipt) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }
}
