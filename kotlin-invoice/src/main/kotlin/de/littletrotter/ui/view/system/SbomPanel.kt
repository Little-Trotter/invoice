package de.littletrotter.ui.view.system

import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

private const val SBOM_GRID_PAGE_SIZE = 35

@Dependent
class SbomPanel(
    componentsLoader: () -> List<SbomComponent>,
) : VerticalLayout() {

    /** The bean constructor. The loader cannot be injected, and a default value on the primary
     *  constructor would collide with this one, so the default lives here. */
    @Inject
    constructor() : this(SbomDataLoader::loadComponents)

    init {
        addClassName("system-sbom-panel")
        setWidthFull()
        setSizeFull()
        isPadding = false
        isSpacing = true

        add(H3(UiTranslations.tr("system.tab.sbom")))

        val components = componentsLoader()
        if (components.isEmpty()) {
            add(Span(UiTranslations.tr("system.sbom.empty")))
        } else {
            val grid = DataTable(SbomComponent::class.java, false)
            grid.addColumn(SbomComponent::name)
                .setHeader(UiTranslations.tr("system.sbom.column.name"))
                .setSortable(true)
                .asTextColumn(TextColumnWidth.MEDIUM)
            grid.addColumn(SbomComponent::version)
                .setHeader(UiTranslations.tr("system.sbom.column.version"))
                .setSortable(true)
                .asCompactColumn()
            grid.addColumn(SbomComponent::type)
                .setHeader(UiTranslations.tr("system.sbom.column.type"))
                .setSortable(true)
                .asCompactColumn()
            grid.addColumn(SbomComponent::license)
                .setHeader(UiTranslations.tr("system.sbom.column.license"))
                .setSortable(true)
                .asTextColumn(TextColumnWidth.SMALL)
            grid.addComponentColumn { component -> descriptionCell(component) }
                .setHeader(UiTranslations.tr("system.sbom.column.description"))
                .setSortable(false)
                .asTextColumn(TextColumnWidth.LARGE)
            grid.pageSize = SBOM_GRID_PAGE_SIZE
            grid.isMultiSort = true

            val provider = ListDataProvider(components)
            grid.dataProvider = provider

            val filter = TextField(UiTranslations.tr("system.sbom.filter")).apply {
                setWidthFull()
                isClearButtonVisible = true
                addValueChangeListener { event ->
                    val query = event.value.trim().lowercase()
                    provider.setFilter { component ->
                        component.name.lowercase().contains(query) || component.description.lowercase().contains(query)
                    }
                }
            }

            val wrapper = GridWrapper(grid).apply {
                addClassName("sbom-grid-wrapper")
                setWidthFull()
            }
            add(filter, wrapper)
            setFlexGrow(1.0, wrapper)
        }
    }

    /** The description column's cell: the raw text, with the same text as a mouse-over tooltip
     *  for a row too narrow to show it in full. A named function rather than only an inline
     *  `addComponentColumn` lambda, so a test can render it directly for an arbitrary component
     *  without walking the grid's own column renderer. */
    internal fun descriptionCell(component: SbomComponent): Span = Span(component.description).apply {
        addClassName("sbom-description-cell")
        element.setAttribute("title", component.description)
    }
}
