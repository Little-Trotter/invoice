package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.inject.Inject

/**
 * MVP-prod cleanup — this used to take nine constructor parameters (seven services plus two
 * `@ConfigProperty` strings) purely to hand-build the panels, i.e. it acted as a manual DI
 * container and duplicated that wiring into every test. The panels are CDI beans now and inject
 * their own dependencies, so the view only needs the panels themselves. That is what removed the
 * long-standing `LongParameterList` entry from `detekt-baseline.xml` rather than rewriting it.
 */
@Route(value = "system", layout = MainLayout::class)
@PageTitle("System")
class SystemView @Inject constructor(
    statusPanel: StatusPanel,
    natsPanel: NatsPanel,
    cachePanel: CachePanel,
    sbomPanel: SbomPanel,
    private val journalPanel: JournalPanel,
) : VerticalLayout(), TranslationProvider {

    init {
        addClassName("system-view")
        isPadding = true
        isSpacing = true
        addClassName("lt-page-centered")

        val content: Map<SystemTab, Component> = mapOf(
            SystemTab.STATUS to statusPanel,
            SystemTab.NATS to natsPanel,
            SystemTab.CACHE to cachePanel,
            SystemTab.SBOM to sbomPanel,
            SystemTab.JOURNAL to journalPanel,
        )

        // Order and labels come from SystemTab, not from this call site — see its KDoc.
        val panels: LinkedHashMap<Tab, Component> = SystemTab.entries.associateTo(LinkedHashMap()) {
            Tab(tr(it.labelKey)) to content.getValue(it)
        }

        val tabs = Tabs(*panels.keys.toTypedArray()).apply { setWidthFull() }
        panels.values.drop(1).forEach { it.isVisible = false }

        // Applied once here for whichever tab renders first (today always STATUS, since
        // Tabs selects index 0; a future deep link onto the Journal tab needs this to already
        // have run), not only from the listener below. One function, not two copies of the rule.
        applyJournalLayout(panels[tabs.selectedTab])
        tabs.addSelectedChangeListener { event ->
            panels.forEach { (tab, panel) -> panel.isVisible = (tab == event.selectedTab) }
            applyJournalLayout(panels[event.selectedTab])
        }

        add(tabs, *panels.values.toTypedArray())
    }

    override fun getComponent(): Component = this

    /** The journal is a wide, dense table, and the one panel meant to fill
     *  the viewport height too; every other panel keeps the 1400px reading column and its natural
     *  height. [SystemView]'s own `display:flex; flex-direction:column` (styles.css) only stretches
     *  a child that asks for it, so [JournalPanel]'s own `system-journal-panel` class carries the
     *  `flex: 1 1 auto` — this function only ever touches `maxWidth`. */
    private fun applyJournalLayout(selected: Component?) {
        maxWidth = if (selected === journalPanel) "100%" else DEFAULT_MAX_WIDTH
    }

    private companion object {
        /** The reading-column width every panel but the journal is laid out for. */
        const val DEFAULT_MAX_WIDTH = "1400px"
    }
}
