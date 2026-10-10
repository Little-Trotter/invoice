package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexLayout
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.system.CacheStatusService
import de.littletrotter.application.system.ValkeyStatsService
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

@Dependent
class CachePanel @Inject constructor(
    cacheStatusService: CacheStatusService,
    valkeyStatsService: ValkeyStatsService,
) : VerticalLayout(), TranslationProvider {

    override fun getComponent(): Component = this

    init {
        addClassName("system-cache-panel")
        setWidthFull(); isPadding = false; isSpacing = true

        val status = cacheStatusService.fetchStatus()
        val valkeyStats = valkeyStatsService.getStats()

        val header = HorizontalLayout().apply {
            setWidthFull(); isPadding = false
            addClassName("lt-cache-header")
            add(H3("CACHE").apply { addClassName("lt-cache-header__title") })
        }

        val grid = FlexLayout().apply {
            setWidthFull(); flexWrap = FlexLayout.FlexWrap.WRAP; addClassName("lt-gap-100")
        }

        val valkeyCard = SystemUiComponents.infoCard(tr("system.service.valkey.name"), if (status.valkeyOnline) "ONLINE" else "OFFLINE", SystemUiComponents.statusDot(status.valkeyOnline))

        grid.add(
            valkeyCard,
            SystemUiComponents.infoCard("PDF-Cache (DB1)", status.pdfCacheKeys.toString()),
            SystemUiComponents.infoCard("Query-Cache (DB2)", status.queryCacheKeys.toString()),
        )

        if (valkeyStats.reachable) {
            grid.add(SystemUiComponents.infoCard(tr("system.counts.valkeyTotal"), valkeyStats.totalKeys.toString()))
            if (valkeyStats.families.isNotEmpty()) {
                val familiesText = valkeyStats.families.take(MAX_FAMILY_BADGES).joinToString(", ") {
                    "${getFamilyRoleLabel(it.family)}: ${it.count}"
                }
                val moreText = if (valkeyStats.families.size > MAX_FAMILY_BADGES) " +${valkeyStats.families.size - MAX_FAMILY_BADGES}" else ""
                grid.add(SystemUiComponents.infoCard(tr("system.counts.valkeyFamilies"), familiesText + moreText))
            }
            valkeyStats.usedMemoryHuman.blankToNull()?.let {
                grid.add(SystemUiComponents.infoCard(tr("system.counts.valkeyMemory"), it))
            }
        } else {
            grid.add(SystemUiComponents.infoCard(tr("system.counts.valkeyLabel"), tr("system.counts.unreachable")))
        }

        add(header, grid)

        if (valkeyStats.reachable) {
            add(
                Span(tr("system.counts.valkeyTtlHint")).apply { addClassName("lt-cache-hint") },
            )
        }
    }

    /** Ported from the old StatusPanel message-count row: falls back to the raw family name
     *  when no `system.counts.family.<family>` translation exists. */
    private fun getFamilyRoleLabel(family: String): String {
        val i18nKey = "system.counts.family.$family"
        val label = tr(i18nKey)
        return if (label == i18nKey || label == "!{$i18nKey}!") family else label
    }

    private companion object {
        /** Valkey key families shown inline; the rest collapses into a "+N" suffix. */
        const val MAX_FAMILY_BADGES = 3
    }
}
