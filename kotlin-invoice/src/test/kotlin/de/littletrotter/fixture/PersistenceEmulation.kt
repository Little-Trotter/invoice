package de.littletrotter.fixture

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceReferences
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.Money
import java.time.LocalDate

/**
 * Writes the values only the persistence layer assigns: the generated identity, the optimistic
 * lock version and the association Hibernate hydrates from legacy rows. The aggregates expose no
 * setter for them, because no business operation may change them; a unit test that needs an
 * entity "as loaded from the database" emulates the insert here instead.
 *
 * This object is the single place in the test sources that uses reflection on an aggregate.
 */
object PersistenceEmulation {
    private val IS_PREFIXED = Regex("is([A-Z].*)")

    /** Gives [entity] the identity the database would generate on insert. */
    fun <T : Any> assignId(entity: T, id: Long?): T = entity.also { writeThroughAccessor(it, "id", id) }

    /** Gives [entity] the optimistic-lock version Hibernate maintains. */
    fun <T : Any> assignVersion(entity: T, version: Long?): T = entity.also { writeThroughAccessor(it, "version", version) }

    /**
     * Hydrates the invoice-level seller override. No production code path sets it any more; it only
     * exists on rows written before the invoice carried its own seller reference, and the
     * effective-seller rules must keep honouring it when it is loaded.
     */
    fun assignSelectedSeller(invoice: Invoice, seller: SenderProfile?): Invoice =
        invoice.also { write(it, "selectedSeller", seller) }

    /**
     * Hydrates the stored totals independently of the items, the way a row with corrupt or legacy
     * amounts is loaded. `recalculateTotals` is the only operation that writes them, and it always
     * derives them from the items, so a test of the validation that guards against inconsistent
     * stored amounts cannot reach that state any other way.
     */
    fun assignTotals(invoice: Invoice, totals: InvoiceTotals): Invoice = invoice.also { write(it, "totals", totals) }

    /** Flags the totals as stale, the way the maintenance job's bulk update leaves them. */
    fun flagNeedsRecalculation(invoice: Invoice): Invoice = invoice.also { write(it, "needsRecalculation", true) }

    /**
     * Hydrates the reference composite as `null`, which is what Hibernate does when all eight
     * columns are null. The accessor must then hand out a usable value without making the row dirty.
     */
    fun hydrateNullReferences(invoice: Invoice): Invoice = invoice.also { write(it, "referenceFields", null) }

    /** The raw reference composite as stored, without the accessor's materialisation. */
    fun storedReferences(invoice: Invoice): InvoiceReferences? = read(invoice, "referenceFields") as InvoiceReferences?

    /**
     * Puts an invoice into [status] without walking the state machine, for tests of rules that
     * depend only on the status. Production code reaches a status through `transitionTo` or the
     * initial status of a new document.
     */
    /**
     * Runs [block] while [invoice] looks like an open draft and restores its stored status and type
     * afterwards: fixtures compose documents that a persisted row already carries as issued or
     * cancelled, which the aggregate itself refuses to edit.
     */
    fun <T> whileEditable(invoice: Invoice, block: () -> T): T {
        val status = invoice.status
        val typeCode = invoice.typeCode
        write(invoice, "status", InvoiceStatus.DRAFT)
        write(invoice, "typeCode", InvoiceTypeCode.COMMERCIAL_INVOICE)
        try {
            return block()
        } finally {
            write(invoice, "status", status)
            write(invoice, "typeCode", typeCode)
        }
    }

    fun forceStatus(invoice: Invoice, status: InvoiceStatus): Invoice = invoice.also { write(it, "status", status) }

    /**
     * Hydrates the stored line amounts independently of quantity, price and tax rate, the way a
     * row whose amounts were written by an older calculation is loaded. `calculateAmounts` is the
     * only operation that writes them, and it always derives them from the line values.
     */
    fun assignAmounts(item: InvoiceItem, net: Money, vat: Money, gross: Money): InvoiceItem = item.also {
        write(it, "netAmount", net)
        write(it, "vatAmount", vat)
        write(it, "grossAmount", gross)
    }

    /**
     * Hydrates the BT-158 classification of a line. The columns do not exist: the values are
     * transient and no operation sets them, yet the DTO mapper still carries them through.
     */
    fun assignProductClassification(item: InvoiceItem, scheme: String?, code: String?): InvoiceItem = item.also {
        write(it, "productClassificationScheme", scheme)
        write(it, "productClassificationCode", code)
    }

    /** Hydrates the transient language override of the invoice header; no operation sets it. */
    fun assignLanguageId(invoice: Invoice, languageId: String?): Invoice =
        invoice.also { write(it, "languageId", languageId) }

    /** Hydrates the transient document status reason code (BT-3 qualifier); no operation sets it. */
    fun assignDocumentStatusReasonCode(invoice: Invoice, code: String?): Invoice =
        invoice.also { write(it, "documentStatusReasonCode", code) }

    /**
     * Hydrates BT-26 on an invoice that is not born through the credit-note factory. Only that
     * factory sets it, and it fixes the type code and status, so a row of any other combination
     * can only be loaded.
     */
    fun assignOriginalInvoiceDate(invoice: Invoice, date: LocalDate?): Invoice =
        invoice.also { write(it, "originalInvoiceDate", date) }

    /**
     * Writes [field] of [entity] as a row loaded from the database would carry it, for a state no
     * domain operation produces any more (a stored status, type or amount that a persisted row
     * still holds). Tests that use it pin how code reacts to loaded data, not how it was produced.
     * The write bypasses Hibernate's enhanced dirty tracking: never use it to change a managed or
     * merged entity whose update a test expects in the database; use the domain operation instead.
     */
    fun <T : Any> hydrate(entity: T, field: String, value: Any?): T = entity.also { write(it, field, value) }

    /**
     * Writes [property] of [entity] through its closed setter, the way a property-access
     * persistence provider would; falls back to the field when the entity declares no setter.
     * Catalogue and system rows whose stored values no intent method produces (identity,
     * version, imported reference data) are arranged this way.
     */
    fun <T : Any> assign(entity: T, property: String, value: Any?): T =
        entity.also { writeThroughAccessor(it, property, value) }

    private fun read(target: Any, fieldName: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(target)
            }
            type = type.superclass
        }
        error("${target.javaClass.name} has no persistence field '$fieldName'")
    }

    /**
     * Identifier and lock version go through the entity's own non-public setter when it declares
     * one, as a provider using property access would; entities without one get the field write.
     */
    private fun writeThroughAccessor(target: Any, property: String, value: Any?) {
        // Kotlin names the setter of a boolean `isX` property `setX`, not `setIsX`.
        val setterNames = listOfNotNull(
            "set" + property.replaceFirstChar(Char::uppercaseChar),
            IS_PREFIXED.matchEntire(property)?.let { "set" + it.groupValues[1] },
        )
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val setter = type.declaredMethods.firstOrNull { method ->
                method.parameterCount == 1 &&
                    setterNames.any { method.name == it || method.name.startsWith("$it-") }
            }
            if (setter != null) {
                setter.isAccessible = true
                setter.invoke(target, unboxedFor(setter.parameterTypes[0], value))
                return
            }
            type = type.superclass
        }
        write(target, property, value)
    }

    private fun write(target: Any, fieldName: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                field.set(target, unboxedFor(field.type, value))
                return
            }
            type = type.superclass
        }
        error("${target.javaClass.name} has no persistence field '$fieldName'")
    }

    /** An inline value class (e.g. `PostalCode`) is stored as its single underlying field. */
    private fun unboxedFor(fieldType: Class<*>, value: Any?): Any? {
        if (value == null || fieldType.isPrimitive || fieldType.isInstance(value)) return value
        val inner = value.javaClass.declaredFields.singleOrNull { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            ?: return value
        inner.isAccessible = true
        return inner.get(value)
    }
}
