package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.repository.ValidationProfileRepository
import de.littletrotter.domain.compliance.ValidationProfile
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PanacheValidationProfileRepository : BasePanacheRepository(), ValidationProfileRepository {

    override fun findCurrentByProfileId(profileId: String): ValidationProfile? =
        em.createQuery(
            "SELECT p FROM ValidationProfile p WHERE p.profileId = :profileId ORDER BY p.validFrom DESC NULLS LAST, p.id DESC",
            ValidationProfile::class.java,
        ).setParameter("profileId", profileId).setMaxResults(1).resultList.firstOrNull()

    override fun findAll(): List<ValidationProfile> =
        em.createQuery("SELECT p FROM ValidationProfile p ORDER BY p.profileId, p.validFrom", ValidationProfile::class.java).resultList

    override fun save(profile: ValidationProfile): ValidationProfile = saveEntity(profile) { profile.id }
}
