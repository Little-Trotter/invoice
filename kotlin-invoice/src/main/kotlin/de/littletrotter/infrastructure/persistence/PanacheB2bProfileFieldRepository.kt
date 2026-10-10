package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.repository.B2bProfileFieldRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PanacheB2bProfileFieldRepository : BasePanacheRepository(), B2bProfileFieldRepository {

    override fun findByProfileId(profileId: Long): List<B2bProfileField> =
        em.createQuery(
            "SELECT f FROM B2bProfileField f WHERE f.profile.id = :profileId ORDER BY f.sortOrder, f.term",
            B2bProfileField::class.java,
        ).setParameter("profileId", profileId).resultList

    override fun findById(id: Long): B2bProfileField? = findByIdInternal(id, B2bProfileField::class.java)

    override fun save(field: B2bProfileField): B2bProfileField = saveEntity(field) { field.id }

    override fun delete(id: Long) { deleteById(id, B2bProfileField::class.java) }
}
