package de.littletrotter.domain.invoice

/**
 * test support. [Invoice.status] and the [Invoice.items]/[Invoice.documents]/
 * [Invoice.partySnapshots] collections are intentionally locked down against ordinary callers —
 * production code has no equivalent escape hatch, only [Invoice.transitionTo],
 * [Invoice.withInitialStatus], [Invoice.addItem]/[Invoice.removeItem] and
 * [Invoice.applyPartySnapshots]. Fixture setup across this test suite still needs to build an
 * [Invoice] in an arbitrary status, or with a pre-built collection, without replaying a full
 * lifecycle for every test. These two helpers are that one explicit, test-only door: reflection
 * for the status field (there is no factory for "change an already-existing instance's status
 * outside a legal transition", and none should exist in production code), a plain unchecked cast
 * for the collections (the exposed [List] is a real `MutableList` at runtime — the same object
 * [Invoice]'s own domain methods mutate internally).
 */
fun Invoice.forceStatusForTest(status: InvoiceStatus) {
    val field = Invoice::class.java.getDeclaredField("status")
    field.isAccessible = true
    field.set(this, status)
}

@Suppress("UNCHECKED_CAST")
fun <T> List<T>.asMutableForTest(): MutableList<T> = this as MutableList<T>
