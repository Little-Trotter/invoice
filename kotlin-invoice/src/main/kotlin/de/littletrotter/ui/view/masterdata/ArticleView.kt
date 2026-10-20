package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.Component
import de.littletrotter.ui.i18n.UiTranslations
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.Shortcuts
import com.vaadin.flow.component.UI
import java.math.BigDecimal
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import com.vaadin.flow.data.binder.ValidationException
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.HasDynamicTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.Command
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.masterdata.port.inbound.ArticleRecipientRefsExtensionQueryPort
import de.littletrotter.application.masterdata.port.inbound.ArticleTaxVariantsExtensionQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.LeistungsverzeichnisRules
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.ArticleRecipientRefsSection
import de.littletrotter.ui.component.ArticleTaxVariantsSection
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.view.AbstractCrudView
import de.littletrotter.ui.view.referencedata.ArticleFormData
import de.littletrotter.application.error.ApplicationConflictException
import jakarta.inject.Inject
import de.littletrotter.application.error.isOptimisticLockFailure
import de.littletrotter.ui.component.ActionIcons

private const val ARTICLE_OPTIMISTIC_LOCK_KEY = "article.error.optimisticLock"

private const val SERVICE_CATALOG_ACTION_NEW_KEY = "serviceCatalog.action.new"
private const val SERVICE_CATALOG_ACTION_EDIT_KEY = "serviceCatalog.action.edit"
private const val ARTICLE_NUMBER_KEY = "articleNumber"
private const val DEFAULT_UNIT_KEY = "defaultUnit"
internal const val ARTICLE_ACTIONS_KEY = "actions"
private const val FILTER_VALUE_CHANGE_TIMEOUT_MS = 300

@Route(value = "articles", layout = MainLayout::class)
class ArticleView
@Inject
constructor(
    private val articleQueries: ArticleQueryPort,
    private val manageArticle: ManageArticleUseCase,
    private val taxRateQueries: TaxRateQueryPort,
    private val numberRangeQueries: NumberRangeQueryPort,
    articleTaxVariantsRegistry: ArticleTaxVariantsExtensionQueryPort,
    articleRecipientRefsRegistry: ArticleRecipientRefsExtensionQueryPort,
    // Optional default keeps the view's focused Karibu tests independent from seller master data.
    private val senderQueryPort: SenderQueryPort? = null,
    time: TimeSource,
) : AbstractCrudView<ArticleDto, ArticleFormData>(), HasDynamicTitle {

    // The page title follows the same wording profile as the
    // navigation entry ("nav.articles": Material/Artikel/Produkte); @PageTitle cannot hold a
    // dynamic value, hence HasDynamicTitle instead of the annotation.
    override fun getPageTitle(): String = tr("nav.serviceCatalog")

    /** The per-country MWST variants list. Empty/absent when no extension
     *  contributes any. */
    private val taxVariantsSection: ArticleTaxVariantsSection = ArticleTaxVariantsSection(
        articleTaxVariantsRegistry.enabledExtension(),
        taxRateQueries.findAllTaxRates(),
    ) { key -> tr(key) }

    /** The per-recipient identifier mappings list. Empty/absent when no extension
     *  contributes any. */
    private val recipientRefsSection: ArticleRecipientRefsSection = ArticleRecipientRefsSection(
        articleRecipientRefsRegistry.enabledExtension(),
    ) { key -> tr(key) }

    /** Compact Nr.-only filter (contains semantics, "36" finds
     *  ART-2026-00036); ANDs with [filterField] and categoryFilter server-side. */
    private val numberFilterField = TextField()
    private val filterField = TextField()
    private val categoryTabs = Tabs()
    private var activeCategory = InvoiceItemCategory.PRODUCT
    private lateinit var newButton: Button

    // internal, not private: Karibu tests drive and assert the grid/combo boxes with their real
    // generic item type. Reflection erases that type, so a reflected field can only be cast back
    // to it unchecked; same-module access keeps the type and needs no cast at all.
    internal val grid = DataTable(ArticleDto::class.java, false)
    private val binder = Binder(ArticleFormData::class.java)

    /** Proposed article number on dialog open; passed to save handler for collision detection. */
    private var proposedArticleNumber: String? = null

    private var currentSortField: ArticleSortField = ArticleSortField.NAME
    private var currentSortDir: SortDirection = SortDirection.ASC

    /** Units grouped by domain, in stable display order (matches the "Einheiten" reference tab). */
    private val unitsByDomain: List<InvoiceItemUnit> =
        InvoiceItemUnit.entries.sortedBy { it.domain.ordinal }

    /** the dialog fields, extracted to [ArticleDialogFields] to keep this class
     *  under detekt's `LargeClass` threshold; see that class's own KDoc. */
    private val dialogFields = ArticleDialogFields(
        tr = { key -> tr(key) },
        taxRateQueries = taxRateQueries,
        unitsByDomain = unitsByDomain,
        sectionLabel = ::sectionLabel,
        categories = ServiceCatalogSection.catalogueEntries.map { it.category },
        today = time.today(),
    )
    private val nameField = dialogFields.nameField
    private val descriptionArea = dialogFields.descriptionArea
    internal val categoryBox = dialogFields.categoryBox
    internal val unitBox = dialogFields.unitBox
    private val quantityField = dialogFields.quantityField
    private val priceField = dialogFields.priceField
    internal val taxRateCombo = dialogFields.taxRateCombo

    /** Article number proposal from ARTICLE circle; editable like customer number. Empty field uses auto-assignment on save. */
    private val articleNumberField = dialogFields.articleNumberField

    /** Short hash technical identifier; editable. Empty field uses auto-assignment on save. */
    private val shortHashField = dialogFields.shortHashField

    override var currentFormData = ArticleFormData()

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        configureGrid()
        configureDialog()
        val gridWrapper = GridWrapper(grid)
        val filters = buildFilters()
        val categories = buildCategoryTabs()
        add(
            PageShell(tr("serviceCatalog.title"))
                .actions(newButton)
                .filters(*filters)
                .content(categories, gridWrapper),
        )
        refresh()
        updatePageTitle()
    }

    /** Shows the ZUGFeRD/UN-ECE code alongside the label, e.g. "piece (H87)". */
    private fun unitLabelWithCode(unit: InvoiceItemUnit): String = "${tr(unit.labelKey)} (${unit.uneceCode})"

    private fun buildCategoryTabs(): Tabs {
        ServiceCatalogSection.catalogueEntries.forEach { section ->
            categoryTabs.add(Tab(tr(section.labelKey)))
        }
        categoryTabs.setWidthFull()
        categoryTabs.addSelectedChangeListener {
            activeCategory = ServiceCatalogSection.catalogueEntries[categoryTabs.selectedIndex].category
            newButton.text = tr(SERVICE_CATALOG_ACTION_NEW_KEY, sectionLabel(activeCategory))
            refresh()
        }
        return categoryTabs
    }

    private fun sectionLabel(category: InvoiceItemCategory): String =
        tr(ServiceCatalogSection.forCategory(category).labelKey)

    private fun configureGrid() {
        grid.setSizeFull()
        addArticleGridColumns()
        wireArticleGridSelectionAndSort()
        applyDefaultNameSort()
    }

    private fun addArticleGridColumns() {
        // Article number and short name (technical identifier).
        // The number column is sortable: search by number must be able to order the grid too.
        grid.addColumn { it.articleNumber ?: "—" }
            .setHeader(tr("serviceCatalog.positionNumber"))
            .asCompactColumn()
            .setSortable(true)
            .setSortProperty(ARTICLE_NUMBER_KEY)
            .key = ARTICLE_NUMBER_KEY
        grid.addColumn { it.shortHash ?: "—" }
            .setHeader(tr("serviceCatalog.shortHash"))
            .asCompactColumn()

        grid.addColumn(ArticleDto::name)
            .setHeader(tr("serviceCatalog.name"))
            .asTextColumn(TextColumnWidth.MEDIUM)
            .setSortProperty("name")
            .setKey("name")
            .isSortable = true

        // CSS ellipsis + full-text tooltip instead of a manual 50-char cutoff, so the
        // whole description stays reachable via mouse-over.
        grid.ellipsizedTextColumn { it.description?.takeIf { d -> d.isNotBlank() } ?: "—" }
            .setHeader(tr("serviceCatalog.description.short"))
            .asTextColumn(TextColumnWidth.LARGE)

        grid.addColumn { unitLabelWithCode(it.defaultUnit) }
            .setHeader(tr("serviceCatalog.unit"))
            .setSortable(true)
            .setSortProperty(DEFAULT_UNIT_KEY)
            .setKey(DEFAULT_UNIT_KEY)
            .asCompactColumn()

        grid.addColumn { it.defaultQuantity.stripTrailingZeros().toPlainString() }
            .setHeader(tr("serviceCatalog.quantity"))
            .asNumericColumn()

        grid.addColumn { MoneyFormatter.format(it.defaultUnitPriceNet) }
            .setHeader(tr("serviceCatalog.unitPriceNet"))
            .asNumericColumn()

        grid.addColumn { it.defaultTaxRatePercent?.value?.stripTrailingZeros()?.toPlainString()?.plus(" %") ?: "—" }
            .setHeader(tr("serviceCatalog.taxRate"))
            .asNumericColumn()

        grid.addComponentColumn { article ->
            AppButtons.icon(ActionIcons.EDIT, tr(SERVICE_CATALOG_ACTION_EDIT_KEY, sectionLabel(article.category))) { openEditor(article) }
        }.asActionColumn()
            .key = ARTICLE_ACTIONS_KEY
    }

    private fun wireArticleGridSelectionAndSort() {
        grid.asSingleSelect().addValueChangeListener { event ->
            event.value?.let { openEditor(it) }
        }
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            if (order == null) {
                currentSortField = ArticleSortField.NAME
                currentSortDir = SortDirection.ASC
            } else {
                currentSortField = when (order.sorted.key) {
                    ARTICLE_NUMBER_KEY -> ArticleSortField.NUMBER
                    DEFAULT_UNIT_KEY -> ArticleSortField.UNIT
                    else -> ArticleSortField.NAME
                }
                currentSortDir = if (order.direction == com.vaadin.flow.data.provider.SortDirection.ASCENDING) SortDirection.ASC else SortDirection.DESC
            }
            refresh()
        }
    }

    // Make the default NAME/ASC order visible as a header
    // indicator. Without it, the first click on "Name" selects ASC — the order the grid
    // already shows — and looks like dead sorting.
    private fun applyDefaultNameSort() {
        grid.getColumnByKey("name")?.let { nameColumn ->
            grid.sort(
                listOf(
                    com.vaadin.flow.component.grid.GridSortOrder(
                        nameColumn,
                        com.vaadin.flow.data.provider.SortDirection.ASCENDING,
                    ),
                ),
            )
        }
    }

    private fun buildFilters(): Array<Component> {
        numberFilterField.placeholder = tr("serviceCatalog.filter.number.placeholder")
        numberFilterField.width = "9rem"
        numberFilterField.isClearButtonVisible = true
        numberFilterField.valueChangeMode = ValueChangeMode.LAZY
        numberFilterField.valueChangeTimeout = FILTER_VALUE_CHANGE_TIMEOUT_MS
        numberFilterField.addValueChangeListener { refresh() }
        // Keyboard-first: Enter opens the edit dialog directly when
        // the current combined filter has exactly ONE server-side match ("36 ⏎ and you are
        // editing"). Resetting the focus first flushes the LAZY field value to the server, so a
        // fast "type + Enter" never races the 300 ms value sync.
        Shortcuts.addShortcutListener(this, Command { openSingleNumberMatch() }, Key.ENTER)
            .listenOn(numberFilterField)
            .apply { isResetFocusOnActiveElement = true }

        filterField.placeholder = tr("serviceCatalog.filter.placeholder")
        filterField.prefixComponent = VaadinIcon.SEARCH.create()
        filterField.isClearButtonVisible = true
        filterField.valueChangeMode = ValueChangeMode.LAZY
        filterField.valueChangeTimeout = FILTER_VALUE_CHANGE_TIMEOUT_MS
        filterField.addValueChangeListener { refresh() }

        newButton = AppButtons.primary(tr(SERVICE_CATALOG_ACTION_NEW_KEY, sectionLabel(activeCategory)), ActionIcons.ADD) { openEditor(null) }

        return arrayOf(numberFilterField, filterField)
    }

    private fun configureDialog() {
        dialogFields.bind(binder) { key -> tr(key) }
        buildDialogLayout()
    }

    private fun buildDialogLayout() {
        initCrudDialogButtons()

        useDialogSize(AppDialog.Size.L)
        installCrudFooter()

        val form = FormLayout(nameField, articleNumberField, shortHashField, descriptionArea, categoryBox, unitBox, quantityField, priceField, taxRateCombo).apply {
            setWidthFull()
            setResponsiveSteps(
                FormLayout.ResponsiveStep("0", 1),
                FormLayout.ResponsiveStep("480px", 2),
            )
            setColspan(nameField, 2)
            setColspan(descriptionArea, 2)
        }
        // Per-country MWST variants, only added when the extension actually
        // contributes something, exactly like SenderProfileView's einvoiceSection/euVatSection
        // sections.
        val sections = mutableListOf<Component>(form)
        if (taxVariantsSection.hasContent) {
            sections.add(Section(tr("serviceCatalog.taxVariant.section"), "sp-section-bar--amber", taxVariantsSection))
        }
        // Per-recipient buyer article number/name, same "only when it has content"
        // rendering as the tax-variant section above.
        if (recipientRefsSection.hasContent) {
            sections.add(Section(tr("serviceCatalog.recipientRef.section"), "sp-section-bar--blue", recipientRefsSection))
        }
        val masterDataContent = VerticalLayout(*sections.toTypedArray()).apply { isPadding = false; isSpacing = true; setWidthFull() }

        // There is no article-level B2B template tab:
        // its only two reachable terms, BT-155/BT-156, moved to the invoice item dialogs
        // (BT-155 now auto-derives from articleNumber, BT-156 is a plain always-visible item
        // field — see ItemDialogB2bSupport.kt) instead of a second, manually-maintained value per
        // article.
        editorDialog.add(VerticalLayout(masterDataContent).apply { isPadding = false; isSpacing = true; setWidthFull() })
    }

    override fun idOf(formData: ArticleFormData): Long? = formData.id

    override fun formDataFrom(entity: ArticleDto?): ArticleFormData =
        entity?.let { ArticleFormData.from(it) } ?: ArticleFormData(
            category = activeCategory,
            defaultUnit = ServiceCatalogSection.forCategory(activeCategory).defaultUnit,
        )

    override fun createWritableForm(id: Long?): ArticleFormData = ArticleFormData(id = id, version = currentFormData.version)

    override fun expectedRevision(formData: ArticleFormData): Long? = formData.version

    override fun optimisticLockMessageKey(): String = ARTICLE_OPTIMISTIC_LOCK_KEY

    override fun currentRevision(entity: ArticleDto): Long? = entity.version

    /** The catalogue base rate is always German; foreign registrations belong to variants. */
    private fun defaultTaxRateForNewArticle(allTaxRates: List<TaxRateDto>): TaxRateDto? {
        val germanRates = allTaxRates.filter {
            it.country == de.littletrotter.shared.country.Country.DE &&
                it.isActive && it.isSelectable && it.category == "S" && !it.isHotelRate
        }
        return germanRates.find { it.isReference }
            ?: germanRates.find { it.isDefault }
            ?: germanRates.find { it.rate.value.compareTo(BigDecimal("19")) == 0 }
    }

    override fun readFormData(formData: ArticleFormData) {
        // The active tab determines new entries; existing entries retain their category.
        categoryBox.isReadOnly = true
        // setItems clears the ComboBox value. Populate choices BEFORE reading the persisted unit.
        unitBox.setItems(LeistungsverzeichnisRules.allowedUnits(formData.category).sortedBy { it.ordinal })
        binder.readBean(formData)
        val allTaxRates = taxRateQueries.findAllTaxRates()
        // The stored article carries its owned rows and identifiers; a new article (no id) has none.
        val stored = formData.id?.let { articleQueries.findArticleById(it) }
        val selectedTaxRateId = formData.defaultTaxRateId ?: stored?.defaultTaxRateId
        val selectedTaxRate = selectedTaxRateId?.let { id -> allTaxRates.find { it.id == id } }
            ?.takeIf { it.country == de.littletrotter.shared.country.Country.DE }
            ?: defaultTaxRateForNewArticle(allTaxRates)
        taxRateCombo.value = selectedTaxRate

        // The tax-variant rows are not part of the binder-managed form data.
        taxVariantsSection.readFrom(stored?.taxVariants.orEmpty())
        if (formData.id == null) {
            val countries = senderQueryPort?.findAllSenders()?.firstOrNull { it.isDefault }
                ?.additionalVatRegistrations?.map { it.countryAlpha2 }.orEmpty()
            taxVariantsSection.suggestCountries(countries)
        }
        // The recipient-mapping rows, same as the tax variants above.
        recipientRefsSection.readFrom(stored?.recipientRefs.orEmpty())

        // Existing articles keep their numbers (no proposal). Field stays visible/editable.
        if (formData.id == null) {
            proposedArticleNumber = numberRangeQueries.proposeArticleNumber(formData.category)
            articleNumberField.value = proposedArticleNumber ?: ""
            shortHashField.value = ""
        } else {
            proposedArticleNumber = null
            articleNumberField.value = stored?.articleNumber ?: ""
            shortHashField.value = stored?.shortHash ?: ""
        }
    }

    override fun writeValidatedForm(targetFormData: ArticleFormData): Boolean {
        try {
            binder.writeBean(targetFormData)
            return true
        } catch (ex: ValidationException) {
            if (!UiNotifications.focusFirstInvalid(ex)) UiNotifications.error(tr("validation.correctMarkedFields"))
            return false
        }
    }

    override fun findEntityById(id: Long): ArticleDto? = articleQueries.findArticleById(id)

    override fun persistForm(formData: ArticleFormData, current: ArticleDto?) {
        val selectedTaxRate = taxRateCombo.value ?: defaultTaxRateForNewArticle(taxRateQueries.findAllTaxRates())
        val draft = formData.toDraft(current?.version).copy(
            // Both identifiers are freely editable; a blank field lets the save assign one, a typed
            // value is never overwritten (mirrors the recipient form's customer number).
            articleNumber = articleNumberField.value.trim().ifBlank { null },
            shortHash = shortHashField.value.trim().ifBlank { null },
            defaultTaxRateId = selectedTaxRate?.id,
            taxVariants = taxVariantsSection.submittedRows(),
            recipientRefs = recipientRefsSection.submittedRows(),
            proposedArticleNumber = proposedArticleNumber,
        )
        try {
            manageArticle.saveArticle(draft)
        } catch (e: jakarta.persistence.PersistenceException) {
            throw conflictOrRethrow(e, ARTICLE_OPTIMISTIC_LOCK_KEY)
        } catch (e: jakarta.transaction.RollbackException) {
            // Commit-time conflicts arrive wrapped (ARJUNA016053), see isOptimisticLockFailure.
            throw conflictOrRethrow(e, ARTICLE_OPTIMISTIC_LOCK_KEY)
        }
    }

    override fun deleteEntityById(id: Long) {
        // WP2 (0.9.10) — the version the editor dialog captured on open, so the service rejects a
        // delete against a row already edited concurrently since then (see [ArticleManagementService.delete]).
        manageArticle.deleteArticle(id, currentFormData.version)
    }

    override fun refreshGridData() {
        refresh()
    }

    override fun newHeaderTitle(): String = tr(SERVICE_CATALOG_ACTION_NEW_KEY, sectionLabel(currentFormData.category))

    override fun editHeaderTitle(): String = tr(SERVICE_CATALOG_ACTION_EDIT_KEY, sectionLabel(currentFormData.category))

    private fun currentSortFieldName(): String = when (currentSortField) {
        ArticleSortField.NAME -> "name"
        ArticleSortField.NUMBER -> "articleNumber"
        ArticleSortField.CATEGORY -> "category"
        ArticleSortField.UNIT -> "defaultUnit"
    }

    private fun refresh() {
        // Nr. and text are two independent fields, ANDed in the
        // QUERY together with the category (page and count from the same predicate, no
        // client-side filtering).
        val numberFilter = numberFilterField.value?.trim()?.ifBlank { null }
        val textFilter = filterField.value?.trim()?.ifBlank { null }
        // The category constrains the QUERY (fetch and count alike):
        // filtering the fetched page client-side corrupted paging, exactly the defect
        // ReceiptRepository.findAssignable documents for receipts.
        val cat = activeCategory
        val provider = createGridDataProvider(
            fetch = { pageIndex, limit, f ->
                articleQueries.findArticles(pageIndex, limit, numberFilter, f, currentSortFieldName(), currentSortDir, cat)
            },
            count = { f -> articleQueries.countArticles(numberFilter, f, cat) },
            filterSupplier = { textFilter }
        )
        grid.dataProvider = provider
    }

    /** Enter in the Nr. field: exactly ONE match under the CURRENT
     *  combined filter (server count, not the rendered page) opens that article's edit dialog
     *  via the same [openEditor] path a row click uses. With 0 or >1 matches this does nothing —
     *  the grid already shows the situation, no error noise. */
    private fun openSingleNumberMatch() {
        val numberFilter = numberFilterField.value?.trim()?.ifBlank { null }
        val textFilter = filterField.value?.trim()?.ifBlank { null }
        val cat = activeCategory
        if (articleQueries.countArticles(numberFilter, textFilter, cat) != 1L) return
        articleQueries.findArticles(0, 1, numberFilter, textFilter, currentSortFieldName(), currentSortDir, cat)
            .singleOrNull()
            ?.let { openEditor(it) }
    }

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        ui.page.setTitle("${tr("serviceCatalog.title")} | ${tr("app.title")}")
    }
}

private fun Component.conflictOrRethrow(e: Exception, key: String): Exception =
    if (e.isOptimisticLockFailure()) ApplicationConflictException(UiTranslations.tr(this, key), e) else e
