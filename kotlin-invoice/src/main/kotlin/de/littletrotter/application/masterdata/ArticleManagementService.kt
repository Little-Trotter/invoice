/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.masterdata

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.OptimisticLockException
import jakarta.transaction.Transactional

// StringLiteralDuplication — shared by [ArticleManagementService.save]'s pre-check,
// [ArticleManagementService.delete]'s pre-check, and [ArticleManagementService
// .saveOrReportConcurrentDelete]'s post-failure re-check: all three report the identical,
// already-user-facing wording for "this article no longer exists", so one constant instead of
// three copies of the same German sentence.
private const val ARTICLE_DELETED_MEANWHILE = "Die Leistungsposition wurde inzwischen gelöscht."

@ApplicationScoped
class ArticleManagementService @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val masterDataNumberService: MasterDataNumberService,
    private val time: TimeSource,
) {
    fun findById(id: Long): Article? = articleRepository.findById(id)

    /** article-two-field-search — [numberFilter] matches articleNumber only (contains),
     *  [textFilter] matches name/description; both AND when set. See [ArticleRepository.findAll]. */
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        numberFilter: String? = null,
        textFilter: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<Article> = articleRepository.findAll(pageIndex, pageSize, numberFilter, textFilter, sortField, sortDir)

    fun count(numberFilter: String? = null, textFilter: String? = null): Long =
        articleRepository.count(numberFilter, textFilter)

    /** UI-8a — category-constrained at the query level; carries [findAll]'s sort parameters
     *  since the Artikel view routes its category filter through here (fix-article-search-sort).
     *  See [ArticleRepository.findAllInCategory]. */
    fun findAllInCategory(
        category: InvoiceItemCategory,
        pageIndex: Int = 0,
        pageSize: Int = 60,
        numberFilter: String? = null,
        textFilter: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<Article> =
        articleRepository.findAllInCategory(category, pageIndex, pageSize, numberFilter, textFilter, sortField, sortDir)

    fun countInCategory(category: InvoiceItemCategory, numberFilter: String? = null, textFilter: String? = null): Long =
        articleRepository.countInCategory(category, numberFilter, textFilter)

    /** See [ArticleRepository.findForSelect]. */
    fun findForSelect(
        category: InvoiceItemCategory,
        filter: String? = null,
        pageIndex: Int = 0,
        pageSize: Int = 60,
    ): List<Article> = articleRepository.findForSelect(category, filter, pageIndex, pageSize)

    /** See [ArticleRepository.countForSelect]. */
    fun countForSelect(category: InvoiceItemCategory, filter: String? = null): Long =
        articleRepository.countForSelect(category, filter)

    @RequiresPermission(AppPermission.ARTICLES_EDIT)
    @Transactional
    fun save(article: Article, proposedArticleNumber: String? = null): Article {
        if (article.id == null) masterDataNumberService.assignArticleNumber(article, proposedArticleNumber)
        article.ensureShortHash()
        val today = time.today()
        article.validate(today)

        if (article.id != null) {
            val managed = articleRepository.findById(article.id!!)
                ?: throw InvalidParameterException(ARTICLE_DELETED_MEANWHILE)
            if (managed.version != article.version) {
                throw OptimisticLockException("Die Leistungsposition wurde zwischenzeitlich geändert. Bitte neu laden.")
            }
            managed.copyEditableFieldsFrom(article, today)
            val saved = saveOrReportConcurrentDelete(managed)
            articleRepository.forceVersionIncrement(saved)
            return saved
        }

        return masterDataNumberService.saveArticleWithNumber(
            article,
            proposedArticleNumber,
            isTaken = { articleRepository.isArticleNumberTaken(it) },
        ) {
            articleRepository.saveAndFlush(article)
        }
    }

    /**
     * ST-1 update-vs-delete race — [save]'s own version compare above only catches a recipient/
     * article deleted BEFORE its `findById` reload ran; a delete that commits in the window
     * between that reload and this flush (`PanacheArticleRepository.save` now flushes here, in
     * this same ambient transaction, instead of leaving it to the caller's commit — see that
     * override's own KDoc) makes the UPDATE affect zero rows, which Hibernate reports as the
     * SAME ambiguous [OptimisticLockException] as a genuine version conflict — [ConcurrentCrudStressE2EIT]'s
     * `recovery-after-optlock[article.update]` case (0.9.10) hit exactly this: the row was
     * actually gone, not merely re-versioned, so its "still readable afterwards" check failed.
     * Re-checking existence right after the failure tells the two cases apart and reports the
     * SAME readable "wurde inzwischen gelöscht" rejection [save]'s own pre-check throws for the
     * already-caught case, instead of leaking the ambiguous conflict exception.
     */
    private fun saveOrReportConcurrentDelete(managed: Article): Article =
        try {
            articleRepository.save(managed)
        } catch (ex: OptimisticLockException) {
            if (articleRepository.findById(managed.id!!) == null) {
                throw InvalidParameterException(ARTICLE_DELETED_MEANWHILE, ex)
            }
            throw ex
        }

    /**
     * WP2 (0.9.10, concurrent-editing) — [expectedVersion] (the article's own version, e.g.
     * [de.littletrotter.ui.view.masterdata.ArticleView.currentFormData]'s captured revision) is
     * checked, when given, against a fresh reload the same way [save] already guards an update:
     * a delete against a row changed concurrently since the editor opened is rejected instead of
     * silently discarding the other session's edit. `null` (the default; internal/non-UI callers)
     * skips the check, same convention as [save] would if it ever allowed a null version on an
     * existing row. A row already deleted meanwhile is reported the same way [save] reports it —
     * this delete is not silently treated as already-succeeded.
     */
    @RequiresPermission(AppPermission.ARTICLES_EDIT)
    @Transactional
    fun delete(id: Long, expectedVersion: Long? = null) {
        if (expectedVersion != null) {
            val managed = articleRepository.findById(id)
                ?: throw InvalidParameterException(ARTICLE_DELETED_MEANWHILE)
            if (managed.version != expectedVersion) {
                throw OptimisticLockException("Die Leistungsposition wurde zwischenzeitlich geändert. Bitte neu laden.")
            }
        }
        if (articleRepository.isInUse(id)) {
            throw InvalidParameterException("Eine Leistungsposition, die einer Rechnung zugeordnet ist, kann nicht gelöscht werden.")
        }
        articleRepository.delete(id)
    }

    /**
     * Resolves [articleId]'s per-recipient identifier mapping for [recipientId], or
     * `null` when the article doesn't exist or carries no mapping for that recipient. Used by the
     * item dialogs' article-selection listener to prefill BT-156/the new-position description —
     * lookup logic lives here, not inline in the dialog, so it stays testable without Vaadin.
     */
    fun findRecipientRef(articleId: Long, recipientId: Long): ArticleRecipientRef? =
        articleRepository.findById(articleId)?.recipientRefFor(recipientId)
}
