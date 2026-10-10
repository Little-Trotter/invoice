package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.spi.masterdata.ArticleRecipientRefOption
import de.littletrotter.spi.masterdata.ArticleRecipientRefsExtension

/**
 * Generic rendering for an article's per-recipient identifier mappings (0..n rows of
 * recipient + buyer's article number/name), mirroring [ArticleTaxVariantsSection]'s repeatable-
 * row pattern: the section only knows *that* an article may have such rows; the owning
 * [extension] serves the recipient picker, the rows come from and go back to the article's read
 * model and write command.
 *
 * Whether [extension] is present decides everything: `null` renders nothing — the case where no
 * such extension bean is on the classpath.
 */
class ArticleRecipientRefsSection(
    private val extension: ArticleRecipientRefsExtension?,
    private val tr: (String) -> String,
) : VerticalLayout() {

    val hasContent: Boolean get() = extension != null

    // internal, not private: Karibu tests drive a row's recipient ComboBox — and its lazy data
    // provider — directly by their real generic type; the row bar only exposes the combo through
    // its Vaadin Component children (needing an unchecked cast back to
    // ComboBox<ArticleRecipientRefOption>), and the combo's own dataProvider getter erases the
    // filter type to `DataProvider<ArticleRecipientRefOption, *>` regardless.
    internal data class Row(
        val recipient: ComboBox<ArticleRecipientRefOption>,
        val buyerArticleNumber: TextField,
        val buyerArticleName: TextField,
        val bar: HorizontalLayout,
        val recipientDataProvider: com.vaadin.flow.data.provider.DataProvider<ArticleRecipientRefOption, String>,
    )

    internal val rows: MutableList<Row> = mutableListOf()
    private val rowsContainer = VerticalLayout().apply { isPadding = false; isSpacing = true; setWidthFull() }
    private val addButton = Button(tr("serviceCatalog.recipientRef.add"), ActionIcons.ADD.create())

    init {
        isPadding = false
        isSpacing = true
        setWidthFull()
        if (extension != null) {
            addButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            addButton.addClickListener { addRow(null, "", "") }
            add(rowsContainer, addButton)
        }
    }

    /** Shows [refs] (the article's stored rows; empty for a new article). Each row's
     *  recipient option is resolved individually via [ArticleRecipientRefsExtension.recipientOption]
     *  — the picker itself is now a lazy, server-paged [ComboBox] (see [addRow]), so there is
     *  no eagerly-loaded full option list to search a stored id against any more. */
    fun readFrom(refs: List<ArticleRecipientRefDto>) {
        rowsContainer.removeAll()
        rows.clear()
        val extension = this.extension ?: return
        refs.forEach { row ->
            addRow(extension.recipientOption(row.recipientId), row.buyerArticleNumber.orEmpty(), row.buyerArticleName.orEmpty())
        }
    }

    /** The current, in-flight rows to save, or `null` when no extension offers the section (the
     *  stored rows then stay as they are). A row without a chosen recipient (picked-then-abandoned
     *  add) is dropped silently. */
    fun submittedRows(): List<ArticleRecipientRefDto>? {
        if (extension == null) return null
        return rows.mapNotNull { row ->
            val recipientId = row.recipient.value?.recipientId ?: return@mapNotNull null
            ArticleRecipientRefDto(
                recipientId,
                row.buyerArticleNumber.value.trim().ifBlank { null },
                row.buyerArticleName.value.trim().ifBlank { null },
            )
        }
    }

    private fun addRow(recipient: ArticleRecipientRefOption?, buyerArticleNumber: String, buyerArticleName: String) {
        // Both call sites (the add button's listener, registered only when extension != null; and
        // readFrom, which returns early when extension is null) already gate on a present
        // extension — the null case is structurally unreachable here.
        val extension = this.extension!!
        lateinit var recipientDataProvider: com.vaadin.flow.data.provider.DataProvider<ArticleRecipientRefOption, String>
        val recipientField = ComboBox<ArticleRecipientRefOption>().apply {
            label = tr("serviceCatalog.recipientRef.recipient")
            helperText = tr("serviceCatalog.recipientRef.recipient.helper")
            // Lazy, server-paged: an eager, page-capped load would make every recipient beyond the
            // first page's alphabetical position unpickable here. Same idiom as every other lazy
            // recipient combo, just riding the extension's own
            // [ArticleRecipientRefsExtension.searchRecipients]/[countRecipients] instead of a
            // recipient input port directly — the section must not know how (or whether) the
            // owning extension actually sources its recipients.
            recipientDataProvider = lazyItems(
                count = { filter -> extension.countRecipients(filter) },
                fetch = { filter, offset, limit -> extension.searchRecipients(filter, offset, limit) },
            )
            setItemLabelGenerator { it.label }
            value = recipient
            setWidthFull()
            addClassName("lt-article-ref-recipient")
        }
        val numberField = TextField().apply {
            label = tr("serviceCatalog.recipientRef.buyerArticleNumber")
            value = buyerArticleNumber
            setWidthFull()
            addClassName("lt-article-ref-number")
        }
        val nameField = TextField().apply {
            label = tr("serviceCatalog.recipientRef.buyerArticleName")
            value = buyerArticleName
            setWidthFull()
            addClassName("lt-article-ref-name")
        }
        val removeButton = Button(ActionIcons.DELETE.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR)
            element.setAttribute("aria-label", tr("serviceCatalog.recipientRef.remove"))
            addClassName("lt-article-ref-remove")
        }
        val bar = HorizontalLayout(recipientField, numberField, nameField, removeButton).apply {
            setWidthFull()
            isSpacing = true
            // Use the dialog width on desktop; wrap before inputs become cramped on mobile.
            addClassName("lt-flex-wrap")
            alignItems = FlexComponent.Alignment.BASELINE
        }
        val row = Row(recipientField, numberField, nameField, bar, recipientDataProvider)
        removeButton.addClickListener {
            rows.remove(row)
            rowsContainer.remove(bar)
        }
        rows.add(row)
        rowsContainer.add(bar)
    }
}
