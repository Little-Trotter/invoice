package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.application.reference.port.outbound.B2bFieldValueStore
import de.littletrotter.shared.b2b.B2bTerm
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PanacheB2bFieldValueStore : BasePanacheRepository(), B2bFieldValueStore {

    private companion object {
        private const val PARAM_OWNER_TYPE = "ownerType"
    }

    override fun findByOwner(ownerType: B2bFieldOwnerType, ownerId: Long): List<B2bFieldValue> =
        em.createQuery(
            "SELECT v FROM B2bFieldValue v WHERE v.ownerType = :ownerType AND v.ownerId = :ownerId",
            B2bFieldValue::class.java,
        ).setParameter(PARAM_OWNER_TYPE, ownerType).setParameter("ownerId", ownerId).resultList

    override fun findByOwnerAndBtCode(ownerType: B2bFieldOwnerType, ownerId: Long, term: B2bTerm): B2bFieldValue? =
        em.createQuery(
            "SELECT v FROM B2bFieldValue v WHERE v.ownerType = :ownerType AND v.ownerId = :ownerId AND v.term = :term",
            B2bFieldValue::class.java,
        ).setParameter(PARAM_OWNER_TYPE, ownerType).setParameter("ownerId", ownerId).setParameter("term", term)
            .resultList.firstOrNull()

    override fun findByOwners(ownerType: B2bFieldOwnerType, ownerIds: List<Long>): List<B2bFieldValue> {
        if (ownerIds.isEmpty()) return emptyList()
        return em.createQuery(
            "SELECT v FROM B2bFieldValue v WHERE v.ownerType = :ownerType AND v.ownerId IN :ownerIds",
            B2bFieldValue::class.java,
        ).setParameter(PARAM_OWNER_TYPE, ownerType).setParameter("ownerIds", ownerIds).resultList
    }

    override fun save(value: B2bFieldValue): B2bFieldValue = saveEntity(value) { value.id }

    override fun delete(id: Long) { deleteById(id, B2bFieldValue::class.java) }
}
