package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import de.littletrotter.domain.shared.likeFilter

private const val P_NAME = "p.name"

@ApplicationScoped
class PanacheB2bProfileRepository : BasePanacheRepository(), B2bProfileRepository {

    @Transactional
    override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortDir: SortDirection): List<B2bProfile> {
        val base = StringBuilder("SELECT DISTINCT p FROM B2bProfile p LEFT JOIN FETCH p.countries")
        if (!filterText.isNullOrBlank()) base.append(" WHERE ${likeFilter(P_NAME)}")
        // fix-grid-sorting — the tab's one sortable column (name) is already the query's default
        // order; only the direction ever varies. An id tiebreak keeps paging deterministic.
        base.append(" ORDER BY p.name ${sortDir.name}, p.id ${sortDir.name}")
        return em.createQuery(base.toString(), B2bProfile::class.java)
            .applyLikeParam(filterText)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList
    }

    override fun count(filterText: String?): Long {
        val base = StringBuilder("SELECT COUNT(p) FROM B2bProfile p")
        if (!filterText.isNullOrBlank()) base.append(" WHERE ${likeFilter(P_NAME)}")
        return em.createQuery(base.toString(), Long::class.java)
            .applyLikeParam(filterText)
            .singleResult
    }

    override fun countInactive(filterText: String?): Long {
        val base = StringBuilder("SELECT COUNT(p) FROM B2bProfile p WHERE p.active = false")
        if (!filterText.isNullOrBlank()) base.append(" AND ${likeFilter(P_NAME)}")
        return em.createQuery(base.toString(), Long::class.java)
            .applyLikeParam(filterText)
            .singleResult
    }

    @Transactional
    override fun findById(id: Long): B2bProfile? =
        em.createQuery("SELECT p FROM B2bProfile p LEFT JOIN FETCH p.countries WHERE p.id = :id", B2bProfile::class.java)
            .setParameter("id", id)
            .resultList
            .firstOrNull()

    @Transactional
    override fun findAllActive(): List<B2bProfile> =
        em.createQuery("SELECT DISTINCT p FROM B2bProfile p LEFT JOIN FETCH p.countries WHERE p.active = true ORDER BY p.name", B2bProfile::class.java)
            .resultList

    override fun findByName(name: String): B2bProfile? =
        em.createQuery("SELECT p FROM B2bProfile p WHERE lower(p.name) = :n", B2bProfile::class.java)
            .setParameter("n", name.trim().lowercase())
            .resultList
            .firstOrNull()

    override fun save(profile: B2bProfile): B2bProfile = saveEntity(profile) { profile.id }

    override fun delete(id: Long) { deleteById(id, B2bProfile::class.java) }
}
