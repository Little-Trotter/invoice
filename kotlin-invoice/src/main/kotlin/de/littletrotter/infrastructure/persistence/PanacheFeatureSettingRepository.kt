package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.repository.FeatureSettingRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.settings.FeatureSetting
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class PanacheFeatureSettingRepository : BasePanacheRepository(), FeatureSettingRepository {
    override fun findAll(): List<FeatureSetting> =
        em.createQuery("SELECT s FROM FeatureSetting s ORDER BY s.key", FeatureSetting::class.java).resultList

    override fun findById(flag: FeatureFlag): FeatureSetting? =
        em.find(FeatureSetting::class.java, flag.key)

    @Transactional
    override fun save(setting: FeatureSetting): FeatureSetting = em.merge(setting)
}
