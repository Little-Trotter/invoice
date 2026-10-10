package de.littletrotter.test

import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import jakarta.persistence.TupleElement
import jakarta.persistence.TypedQuery
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

typealias Row = Array<Any?>

/**
 * Minimal [Tuple] backed by a [Row] and the alias list parsed out of the
 * JPQL (`... AS alias`) by [MockEntityManager]. Lets `InvoiceReadRepositoryImplTest` build its
 * fixture as a plain positional array (readable, matches the SELECT's column order) while the
 * production code under test reads it back exclusively via [Tuple.get] by alias, same as against
 * a real `EntityManager`.
 */
private class RowTuple(private val row: Row, private val aliases: List<String>) : Tuple {
    override fun <X> get(tupleElement: TupleElement<X>): X = throw UnsupportedOperationException("not used by InvoiceReadRepositoryImpl")
    override fun get(alias: String): Any? {
        val idx = aliases.indexOf(alias)
        check(idx >= 0) { "Test fixture has no column aliased '$alias' — aliases: $aliases" }
        return row[idx]
    }
    override fun <X> get(alias: String, type: Class<X>): X = type.cast(get(alias))
    override fun get(i: Int): Any? = row[i]
    override fun <X> get(i: Int, type: Class<X>): X = type.cast(row[i])
    override fun toArray(): Array<Any?> = row
    override fun getElements(): List<TupleElement<*>> = emptyList()
}

/** Extracts `AS alias` column names from a JPQL SELECT clause, in column order. */
private val TUPLE_ALIAS_REGEX = Regex("""\bAS\s+(\w+)""", RegexOption.IGNORE_CASE)

/**
 * A [TypedQuery] test double built as a Mockito mock rather than a hand-written class
 * implementing the full interface: `TypedQuery` declares several `setParameter(..., Calendar/Date,
 * TemporalType)` overloads that the JPA API itself marks `@Deprecated`, and a source-level `class
 * ... : TypedQuery<T>` would have to override every one of them, carrying the deprecation into
 * this file. A Mockito proxy implements the interface at the bytecode level instead — nothing here
 * declares (let alone overrides) the deprecated members. [Answers.RETURNS_SELF] reproduces the
 * fake's original fluent `= this` behaviour for every `set*`/`with*` call (deprecated overloads
 * included) without stubbing each one individually; only the handful of methods
 * [InvoiceReadRepositoryImplTest] actually observes are stubbed explicitly.
 */
fun <T> inMemoryTypedQuery(results: List<T>): TypedQuery<T> {
    val query: TypedQuery<T> = mock(defaultAnswer = Answers.RETURNS_SELF)
    whenever(query.resultList).thenReturn(results)
    whenever(query.singleResult).thenAnswer { results.first() }
    whenever(query.singleResultOrNull).thenAnswer { results.firstOrNull() }
    whenever(query.cacheRetrieveMode).thenReturn(jakarta.persistence.CacheRetrieveMode.BYPASS)
    whenever(query.cacheStoreMode).thenReturn(jakarta.persistence.CacheStoreMode.USE)
    return query
}

class MockEntityManager(
    val header: List<Row> = emptyList(),
    val items: List<Row> = emptyList(),
    val receipts: List<Row> = emptyList(),
    val travels: List<Row> = emptyList(),
    val jobs: List<Row> = emptyList()
) : EntityManager by Mockito.mock(EntityManager::class.java) {

    private fun resolveList(qlString: String): List<Row> = when {
        qlString.contains("JOIN i.items item") -> items
        qlString.contains("FROM Invoice i") -> header
        qlString.contains("FROM InvoiceItem item") -> receipts
        qlString.contains("FROM TravelExpenseEntry te") -> travels
        qlString.contains("FROM GenerationJob j") -> jobs
        else -> emptyList()
    }

    // Every 2-arg createQuery call in InvoiceReadRepositoryImpl (and every stub in
    // InvoiceReadRepositoryImplTest) passes Tuple::class.java — RowTuple is the only projection
    // this fake ever needs to produce. Class<T>.cast() gives a real, checked narrowing to T
    // (ClassCastException on any other resultClass, not a silent unchecked reinterpretation).
    override fun <T> createQuery(qlString: String, resultClass: Class<T>): TypedQuery<T> {
        val aliases = TUPLE_ALIAS_REGEX.findAll(qlString).map { it.groupValues[1] }.toList()
        val result = resolveList(qlString).map { resultClass.cast(RowTuple(it, aliases)) }
        return inMemoryTypedQuery(result)
    }

    // untyped variant used by InvoiceReadRepositoryImpl
    override fun createQuery(qlString: String): jakarta.persistence.Query =
        inMemoryTypedQuery(resolveList(qlString))
}
