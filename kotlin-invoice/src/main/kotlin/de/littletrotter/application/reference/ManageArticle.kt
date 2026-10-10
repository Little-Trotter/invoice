package de.littletrotter.application.reference

import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.masterdata.ArticleManagementService
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Implements [ManageArticleUseCase] on top of [ArticleManagementService].
 *
 * The draft becomes a fresh, unsaved article carrying the draft's id and revision; the service
 * then compares that revision with the stored one and copies the edit onto the stored article, or
 * numbers and inserts a new one. The flag marking the shipped default article is not editable,
 * so it is taken over from the stored article.
 */
@ApplicationScoped
class ManageArticle @Inject constructor(
    private val articleManagementService: ArticleManagementService,
    private val taxRateRepository: TaxRateRepository,
    private val customerManagementService: CustomerManagementService,
    private val time: TimeSource,
) : ManageArticleUseCase {

    private val references = ArticleDraftReferences(
        taxRate = { id ->
            taxRateRepository.findById(id) ?: throw InvalidParameterException("Unbekannter Steuersatz (id=$id).")
        },
        recipient = { id ->
            customerManagementService.findById(id) ?: throw InvalidParameterException("Unbekannter Kunde (id=$id).")
        },
    )

    override fun saveArticle(draft: ArticleDraft): ArticleDto {
        val stored = draft.id?.let(articleManagementService::findById)
        val article = Article().apply { id = draft.id }
        if (stored != null) {
            article.version = draft.version ?: stored.version
            article.isDefault = stored.isDefault
        }
        draft.applyTo(article, stored, references, time.today())
        return articleManagementService.save(article, draft.proposedArticleNumber).toDto()
    }

    override fun deleteArticle(id: Long, expectedVersion: Long?) = articleManagementService.delete(id, expectedVersion)
}
