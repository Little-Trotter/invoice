package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager

@ApplicationScoped
class RecipientFieldValueReadRepositoryImpl @Inject constructor(
    private val em: EntityManager,
) : RecipientFieldValueReadRepository {

    override fun findValues(recipientId: Long): Map<Long, String?> =
        em.createQuery(
            "SELECT v.profileFieldId, v.value FROM RecipientB2bFieldValue v WHERE v.recipientId = :recipientId",
            Array<Any?>::class.java,
        ).setParameter("recipientId", recipientId)
            .resultList
            .associate { (it[0] as Long) to (it[1] as String?) }
}
