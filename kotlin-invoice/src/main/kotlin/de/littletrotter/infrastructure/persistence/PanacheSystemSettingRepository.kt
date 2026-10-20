package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.repository.SystemSettingRepository
import de.littletrotter.domain.settings.SystemSetting
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class PanacheSystemSettingRepository : BasePanacheRepository(), SystemSettingRepository {
    override fun findById(key: String): SystemSetting? = em.find(SystemSetting::class.java, key)

    // Criteria rather than JPQL: `key` is a reserved word in JPQL.
    override fun findFamily(key: String): List<SystemSetting> {
        val cb = em.criteriaBuilder
        val query = cb.createQuery(SystemSetting::class.java)
        val root = query.from(SystemSetting::class.java)
        val keyPath = root.get<String>("key")
        query.select(root)
            .where(cb.or(cb.equal(keyPath, key), cb.like(keyPath, "$key.%")))
            .orderBy(cb.asc(keyPath))
        return em.createQuery(query).resultList
    }

    @Transactional
    override fun save(setting: SystemSetting): SystemSetting = em.merge(setting)
}
