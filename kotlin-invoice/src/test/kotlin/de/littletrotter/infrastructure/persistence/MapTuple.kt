package de.littletrotter.infrastructure.persistence

import jakarta.persistence.Tuple
import jakarta.persistence.TupleElement

/**
 * minimal [Tuple] test double keyed by an explicit alias-to-value map, for the per-group
 * field-mapper unit tests (`InvoiceDetail*FieldMapperTest`) that build a header row directly
 * instead of going through [de.littletrotter.test.MockEntityManager]'s JPQL-alias parsing (that
 * one stays [InvoiceReadRepositoryImplTest]'s own fixture, keyed off the real query text).
 */
class MapTuple(private val values: Map<String, Any?>) : Tuple {
    override fun <X> get(tupleElement: TupleElement<X>): X = throw UnsupportedOperationException("not used by these mappers")
    override fun get(alias: String): Any? {
        check(values.containsKey(alias)) { "MapTuple fixture has no column aliased '$alias' — known aliases: ${values.keys}" }
        return values[alias]
    }
    override fun <X> get(alias: String, type: Class<X>): X = type.cast(get(alias))
    override fun get(i: Int): Any? = throw UnsupportedOperationException("not used by these mappers")
    override fun <X> get(i: Int, type: Class<X>): X = throw UnsupportedOperationException("not used by these mappers")
    override fun toArray(): Array<Any?> = values.values.toTypedArray()
    override fun getElements(): List<TupleElement<*>> = emptyList()
}
