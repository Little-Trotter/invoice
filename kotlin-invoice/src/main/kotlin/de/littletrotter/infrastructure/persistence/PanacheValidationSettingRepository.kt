package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.reference.ValidationSetting
import de.littletrotter.domain.repository.ValidationSettingRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class PanacheValidationSettingRepository : BasePanacheRepository(), ValidationSettingRepository {

    override fun findAll(): List<ValidationSetting> =
        em.createQuery("SELECT s FROM ValidationSetting s ORDER BY s.capabilityId", ValidationSetting::class.java).resultList

    override fun findById(capability: ValidationCapabilityId): ValidationSetting? =
        em.find(ValidationSetting::class.java, capability.name)

    /**
     * `merge` instead of `saveEntity`: the primary key is the check's name, set by the caller,
     * not assigned by the database — the base class's id-based "new or existing" distinction
     * does not apply here. `merge` inserts or updates depending on whether the row already
     * exists, which is exactly what's wanted here.
     */
    @Transactional
    override fun save(setting: ValidationSetting): ValidationSetting = em.merge(setting)
}
