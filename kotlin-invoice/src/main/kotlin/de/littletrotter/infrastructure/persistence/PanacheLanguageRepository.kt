package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.repository.LanguageRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PanacheLanguageRepository : BasePanacheRepository(), LanguageRepository {

    override fun findActiveOrdered(): List<Language> =
        em.createQuery(
            "SELECT l FROM Language l WHERE l.isActive = true ORDER BY l.sortOrder ASC, l.id ASC",
            Language::class.java,
        ).resultList

    override fun findByCode(code: String): Language? =
        em.createQuery("SELECT l FROM Language l WHERE l.code = :code", Language::class.java)
            .setParameter("code", code)
            .resultList
            .firstOrNull()
}
