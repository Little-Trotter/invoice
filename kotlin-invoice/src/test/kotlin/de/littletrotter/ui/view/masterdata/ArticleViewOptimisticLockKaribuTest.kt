package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.masterdata.ArticleRecipientRefsExtensionRegistry
import de.littletrotter.application.masterdata.ArticleTaxVariantsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.AbstractCrudView
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * [ArticleView.persistForm]'s own JPA-level guard — the counterpart to
 * [ArticleViewSaveValidationKaribuTest]'s "saving an old article dialog rejects a newer loaded
 * revision before mutation" test, which only pins the UI-level [ArticleView.expectedRevision]/
 * [ArticleView.currentRevision] pre-check (a re-read at save time, not atomic with the actual
 * write). A genuinely concurrent commit landing in the gap between that pre-check and
 * [de.littletrotter.application.masterdata.ArticleManagementService.save]'s own reload+compare
 * surfaces only as a raw [jakarta.persistence.OptimisticLockException] out of the service call —
 * this pins that [ArticleView.persistForm] now catches it and rethrows the translated
 * [de.littletrotter.application.error.ApplicationConflictException] instead, so the raw
 * Hibernate/JPA message never reaches the notification; mirrors
 * [RecipientViewOptimisticLockKaribuTest]/[RecipientView.persistForm]'s identical catch (WP2,
 * 0.9.10 concurrent-editing).
 */
class ArticleViewOptimisticLockKaribuTest {

    private val articleQueries: ArticleQueryPort = mock()
    private val manageArticle: ManageArticleUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val articleTaxVariantsRegistry: ArticleTaxVariantsExtensionRegistry = mock()
    private val articleRecipientRefsRegistry: ArticleRecipientRefsExtensionRegistry = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): ArticleView {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
        whenever(articleQueries.findArticles(any(), any(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull())).thenReturn(emptyList())
        whenever(articleQueries.countArticles(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(0L)
        return ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, articleTaxVariantsRegistry, articleRecipientRefsRegistry, time = FIXED_TIME_SOURCE)
    }

    private fun textField(view: ArticleView, name: String): TextField {
        val f = ArticleView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun bigDecimalField(view: ArticleView, name: String): BigDecimalField {
        val f = ArticleView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as BigDecimalField
    }

    private fun categoryBox(view: ArticleView): ComboBox<InvoiceItemCategory> = view.categoryBox

    private fun unitBox(view: ArticleView): ComboBox<InvoiceItemUnit> = view.unitBox

    private fun fillValidForm(view: ArticleView) {
        textField(view, "nameField").value = "Widget"
        categoryBox(view).value = InvoiceItemCategory.SERVICE
        unitBox(view).value = InvoiceItemUnit.HOUR
        bigDecimalField(view, "quantityField").value = BigDecimal.ONE
        bigDecimalField(view, "priceField").value = BigDecimal("9.99")
    }

    private fun openEditor(view: ArticleView, entity: ArticleDto?) {
        AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
            .also { it.isAccessible = true }.invoke(view, entity)
    }

    private fun clickSave(view: ArticleView) {
        (
            AbstractCrudView::class.java.getDeclaredField("saveButton")
                .also { it.isAccessible = true }.get(view) as Button
            ).click()
    }

    /** Digs the plain-text message out of an [de.littletrotter.ui.notification.UiNotifications
     *  .error]-shaped notification (`Div(Span(message), closeButton)`, see that function's own
     *  KDoc) — the same shape every error toast in this app uses. */
    private fun notificationText(notification: com.vaadin.flow.component.notification.Notification): String {
        val div = notification.children.filter { it is Div }.findFirst().orElse(null) as? Div ?: return ""
        val span = div.children.filter { it is Span }.findFirst().orElse(null) as? Span ?: return ""
        return span.text ?: ""
    }

    @Test
    fun `a JPA-level optimistic-lock race on save shows the translated conflict notification`() {
        val view = newView()
        val opened = ArticleDto(id = 5L, name = "Opened", version = 7L)
        openEditor(view, opened)
        fillValidForm(view)
        // The UI-level pre-check (expectedRevision vs currentRevision) sees the SAME version at
        // save time — no stale-dialog conflict here; the reload it does is stubbed to still match.
        whenever(articleQueries.findArticleById(5L)).thenReturn(opened)
        // The race instead happens INSIDE the service call itself — another commit lands between
        // that reload and the actual DB write, which is exactly what a raw, untranslated
        // OptimisticLockException out of the service call simulates here.
        whenever(manageArticle.saveArticle(any())).thenThrow(
            OptimisticLockException("Row was updated or deleted by another transaction"),
        )

        clickSave(view)

        verify(manageArticle).saveArticle(any())
        val notifications = getNotifications()
        assertTrue(notifications.isNotEmpty(), "a JPA-level conflict must still notify the user")
        val shown = notifications.joinToString(" ") { notificationText(it) }
        assertFalse(
            shown.contains("Row was updated or deleted by another transaction"),
            "the raw Hibernate/JPA message must never reach the user — got: $shown",
        )
        assertTrue(
            shown.contains(UiTranslations.tr(view, "article.error.optimisticLock")),
            "the translated optimistic-lock text must be shown — got: $shown",
        )
    }
}
