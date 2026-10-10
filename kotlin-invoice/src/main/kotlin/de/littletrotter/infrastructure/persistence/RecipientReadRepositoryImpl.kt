package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.RecipientReadRepository
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.shared.filterTokens
import de.littletrotter.domain.shared.likeFilterIndexed
import de.littletrotter.domain.shared.likePattern
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Tuple
import jakarta.persistence.TypedQuery

private const val R_NAME = "r.name"

private const val RECIPIENT_SELECT =
    "SELECT r.id AS id, r.name AS name, r.customerNumber AS customerNumber, r.shortHash AS shortHash, " +
        "r.contactPerson AS contactPerson, r.routingId AS routingId, r.vatId AS vatId, r.customerType AS customerType, " +
        "r.vatIdVerificationStatus AS vatIdVerificationStatus, r.reverseChargePreference AS reverseChargePreference " +
        "FROM Recipient r"

/**
 * [RecipientReadRepository] backed by two scalar projections, no entity/lazy-collection load:
 * [findScalarRows] for the paged/filtered/sorted recipient fields (same allowlist/token-filter
 * shape as [PanacheRecipientRepository.findAll], since the recipient grid/combos need the exact
 * same search behavior it already had), then one batch query over the resulting page's ids for
 * the preferred billing/delivery address — replacing the per-row lazy `recipient.addresses.size`
 * initialization [PanacheRecipientRepository.findAll]/`findById` forced.
 */
@ApplicationScoped
class RecipientReadRepositoryImpl : BasePanacheRepository(), RecipientReadRepository {

    private companion object {
        private val SORT_ALLOWLIST = mapOf(
            "customerNumber" to "r.customerNumber",
            "name" to R_NAME,
            "city" to "(SELECT MIN(a.city) FROM Address a WHERE a.recipient = r" +
                $$" AND a.addressType = de.littletrotter.domain.masterdata.Address$AddressType.BILLING)",
            "contactPerson" to "r.contactPerson",
        )
    }

    override fun findRecipients(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<RecipientSummaryDto> {
        val (sortExpr, dir) = resolveSortExpr(sortField, sortDir, SORT_ALLOWLIST, R_NAME)
        val tokens = filterTokens(filterText)
        val jpql = StringBuilder(RECIPIENT_SELECT)
        if (tokens.isNotEmpty()) jpql.append(" WHERE ${tokenFilterClause(tokens)}")
        jpql.append(" ORDER BY $sortExpr $dir, r.id $dir")
        val rows = em.createQuery(jpql.toString(), Tuple::class.java)
            .applyTokenParams(tokens)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList
        return mapRows(rows)
    }

    override fun findRecipientById(id: Long): RecipientSummaryDto? {
        val rows = em.createQuery("$RECIPIENT_SELECT WHERE r.id = :id", Tuple::class.java)
            .setParameter("id", id)
            .resultList
        return mapRows(rows).firstOrNull()
    }

    /** One AND-ed group per token; inside a group the token may match any of the four fields —
     *  mirrors [PanacheRecipientRepository.tokenFilterClause]. */
    private fun tokenFilterClause(tokens: List<String>): String =
        tokens.indices.joinToString(" AND ") { i ->
            "(${likeFilterIndexed(R_NAME, i)} OR ${likeFilterIndexed("r.customerNumber", i)}" +
                " OR ${likeFilterIndexed("r.shortHash", i)}" +
                " OR EXISTS (SELECT a FROM Address a WHERE a.recipient = r" +
                " AND a.addressType = :bt AND ${likeFilterIndexed("a.city", i)}))"
        }

    private fun <T> TypedQuery<T>.applyTokenParams(tokens: List<String>): TypedQuery<T> {
        if (tokens.isEmpty()) return this
        tokens.forEachIndexed { i, token -> setParameter("$FILTER_PARAM$i", likePattern(token)) }
        setParameter("bt", Address.AddressType.BILLING)
        return this
    }

    private fun mapRows(rows: List<Tuple>): List<RecipientSummaryDto> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { (it.get("id") as Number).toLong() }
        val addressesByRecipientId = fetchAddresses(ids)
        return rows.map { row ->
            val id = (row.get("id") as Number).toLong()
            val addresses = addressesByRecipientId[id].orEmpty()
            val billing = preferredAddress(addresses, Address.AddressType.BILLING)
            val delivery = preferredAddress(addresses, Address.AddressType.DELIVERY)
            RecipientSummaryDto(
                id = id,
                name = row.get("name") as String,
                customerNumber = parseString(row.get("customerNumber")),
                shortHash = parseString(row.get("shortHash")),
                contactPerson = parseString(row.get("contactPerson")),
                routingId = parseString(row.get("routingId")),
                vatId = parseString(row.get("vatId")),
                customerType = parseEnum<CustomerType>(row.get("customerType")),
                vatIdVerificationStatus = parseEnum<VatIdVerificationStatus>(row.get("vatIdVerificationStatus")),
                reverseChargePreference = parseEnum<ReverseChargePreference>(row.get("reverseChargePreference")) ?: ReverseChargePreference.ASK,
                billingCity = billing?.let { parseString(it.get("city")) },
                billingCountryAlpha2 = billing?.let { countryAlpha2(it.get("country")) },
                deliveryCountryAlpha2 = delivery?.let { countryAlpha2(it.get("country")) },
            )
        }
    }

    private fun fetchAddresses(ids: Collection<Long>): Map<Long, List<Tuple>> {
        if (ids.isEmpty()) return emptyMap()
        val rows: List<Tuple> = em.createQuery(
            "SELECT a.recipient.id AS recipientId, a.id AS id, a.city AS city, a.country AS country, " +
                "a.addressType AS addressType, a.isDefault AS isDefault " +
                "FROM Address a WHERE a.recipient.id IN :ids",
            Tuple::class.java,
        ).setParameter("ids", ids).resultList
        return rows.groupBy { (it.get("recipientId") as Number).toLong() }
    }

    private fun preferredAddress(addresses: List<Tuple>, type: Address.AddressType): Tuple? = addresses
        .filter { (it.get("addressType") as Address.AddressType) == type }
        .sortedWith(compareByDescending<Tuple> { it.get("isDefault") as Boolean }.thenBy { (it.get("id") as Number).toLong() })
        .firstOrNull()

    private fun parseString(raw: Any?): String? = raw?.toString()

    private inline fun <reified T : Enum<T>> parseEnum(raw: Any?): T? = when (raw) {
        null -> null
        is Enum<*> -> raw as T
        is String -> enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        else -> null
    }

    private fun countryAlpha2(raw: Any?): String? = when (raw) {
        null -> null
        is Country -> raw.alpha2
        else -> raw.toString()
    }
}
