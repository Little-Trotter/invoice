package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.repository.RecipientB2bFieldValueRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PanacheRecipientB2bFieldValueRepository : BasePanacheRepository(), RecipientB2bFieldValueRepository {

    override fun findByRecipientId(recipientId: Long): List<RecipientB2bFieldValue> =
        em.createQuery(
            "SELECT v FROM RecipientB2bFieldValue v WHERE v.recipientId = :recipientId",
            RecipientB2bFieldValue::class.java,
        ).setParameter("recipientId", recipientId).resultList

    override fun findByRecipientIdAndProfileFieldId(recipientId: Long, profileFieldId: Long): RecipientB2bFieldValue? =
        em.createQuery(
            "SELECT v FROM RecipientB2bFieldValue v WHERE v.recipientId = :recipientId AND v.profileFieldId = :profileFieldId",
            RecipientB2bFieldValue::class.java,
        ).setParameter("recipientId", recipientId).setParameter("profileFieldId", profileFieldId)
            .resultList.firstOrNull()

    override fun save(value: RecipientB2bFieldValue): RecipientB2bFieldValue = saveEntity(value) { value.id }

    override fun delete(id: Long) { deleteById(id, RecipientB2bFieldValue::class.java) }
}
