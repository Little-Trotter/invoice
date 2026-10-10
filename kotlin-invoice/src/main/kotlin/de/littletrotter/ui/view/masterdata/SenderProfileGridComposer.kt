package de.littletrotter.ui.view.masterdata

import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.view.createSortableGridDataProvider

/**
 * [SenderProfileView]'s grid/list section: the toolbar (filter + "new" button) and the
 * grid's columns/sorting/lazy data provider. Operates on the [grid] [SenderProfileView] still
 * constructs and owns (so the existing field-name reflection in the Karibu tests keeps working);
 * [nameCellRenderer] stays on the view for the same reason ("nameCell" is reflected by name).
 */
class SenderProfileGridComposer(
    private val grid: DataTable<SenderProfile>,
    private val sellerProfileService: SellerProfileService,
    private val tr: (String) -> String,
    private val nameCellRenderer: (SenderProfile) -> Component,
    private val openEditor: (SenderProfile?) -> Unit,
) {

    private val filterField = TextField()

    fun buildFilter(): TextField {
        filterField.placeholder = tr("senderProfile.filter.placeholder")
        filterField.isClearButtonVisible = true
        filterField.addValueChangeListener { refreshGrid() }
        return filterField
    }

    fun buildNewButton(): Button = AppButtons.primary(tr("button.new")) { openEditor(null) }

    fun configureGrid() {
        grid.setSizeFull()
        // anbieter-menu-ergonomie — the default profile is marked inline on its Name cell (a star
        // icon) instead of a dedicated "Standard" column. Component column, so there is no
        // automatic natural-order comparator the way a `Comparable`-typed ValueProvider column
        // gets for free — set explicitly to keep the header's sort affordance working.
        grid.addComponentColumn { sender -> nameCellRenderer(sender) }
            .setHeader(tr("senderProfile.grid.name"))
            .setKey("name").setSortProperty("name")
            .asTextColumn(TextColumnWidth.MEDIUM)
            .setSortable(true)
            .setComparator(compareBy { it.name })
        grid.addColumn {
            val billingAddress = it.billingAddress
            de.littletrotter.ui.notification.UiNotifications.formatCityCountry(billingAddress?.city, billingAddress?.country)
        }
            .setHeader(tr("senderProfile.grid.cityCountry"))
            .setKey("cityCountry").setSortProperty("cityCountry")
            .asTextColumn(TextColumnWidth.SMALL)
            .isSortable = true
        grid.addColumn(SenderProfile::invoicePrefix)
            .setHeader(tr("senderProfile.grid.prefix"))
            .setKey("invoicePrefix").setSortProperty("invoicePrefix")
            .asCompactColumn()
            .isSortable = true
        grid.addColumn { it.sellerNumber ?: "—" }
            .setHeader(tr("senderProfile.grid.sellerNumber"))
            .asCompactColumn()
        grid.addColumn { it.shortHash ?: "—" }
            .setHeader(tr("senderProfile.grid.shortHash"))
            .asCompactColumn()
        grid.asSingleSelect().addValueChangeListener { event ->
            event.value?.let { openEditor(it) }
        }
        refreshGrid()
    }

    fun refreshGrid() {
        val filter = filterField.value?.trim()?.lowercase().orEmpty()
        val provider = createSortableGridDataProvider(
            fetch = { pageIndex, limit, f, field, direction ->
                if (field == null) {
                    sellerProfileService.findAll(pageIndex, limit, f)
                } else {
                    sellerProfileService.findAllSorted(pageIndex, limit, f, GridSort(field, direction))
                }
            },
            count = { f -> sellerProfileService.count(f) },
            filterSupplier = { filter.blankToNull() },
        )
        grid.dataProvider = provider
    }
}
