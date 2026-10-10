package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto

/** Input port for maintaining the article catalogue. */
interface ManageArticleUseCase : CommandEntryPoint {
    /** Saves [draft] (a new article when its id is `null`) and returns the stored article. */
    fun saveArticle(draft: ArticleDraft): ArticleDto

    /** WP2 (0.9.10) — [expectedVersion] is the article's own version the editor dialog captured
     *  on open; `null` (internal/non-UI callers) skips the staleness check. */
    fun deleteArticle(id: Long, expectedVersion: Long? = null)
}
