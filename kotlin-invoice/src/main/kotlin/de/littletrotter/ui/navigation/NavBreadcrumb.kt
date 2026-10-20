package de.littletrotter.ui.navigation

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Nav
import com.vaadin.flow.component.html.OrderedList
import com.vaadin.flow.component.html.Span
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.navigation.NavItem.Companion.drawerParent
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import de.littletrotter.ui.view.referencedata.ReferenceDataTab

private const val TITLE = "title"

/**
 * MEN-4 — breadcrumb navigation in the navbar showing the current location derived from
 * [NavItem] and the current route. Renders as a nav landmark with an ordered list of
 * segments, each clickable except the terminal one (marked with aria-current="page").
 *
 * Segments are computed from the current path via the pure function [pathToSegments]:
 * - Top-level views: section label (plain text) + item label (link)
 * - Reference data: "Referenzdaten" (link) / group label (link) / sub-tab label (current, plain)
 * - Unknown route: renders nothing
 *
 * Long labels are truncated with CSS ellipsis; full text appears in title attribute.
 */
class NavBreadcrumb(
    currentPath: String,
    currentGroup: ReferenceDataGroup? = null,
    currentTab: ReferenceDataTab? = null,
) : Div(), TranslationProvider {

    private val segments = pathToSegments(currentPath, currentGroup, currentTab)

    init {
        addClassName("nav-breadcrumb")
        val nav = Nav().apply {
            element.setAttribute("aria-label", tr("nav.breadcrumb.label"))
            add(renderSegments(segments))
        }
        add(nav)
    }

    override fun getComponent(): Component = this

    private fun renderSegments(segments: List<BreadcrumbSegment>): OrderedList {
        val ol = OrderedList()
        segments.forEach { segment ->
            val segmentDiv = Div().apply {
                addClassName("breadcrumb-segment")
            }

            when (segment) {
                is BreadcrumbSegment.Link -> {
                    val label = resolveLabel(segment.labelKey)
                    val link = Anchor(segment.routePath, label).apply {
                        element.setAttribute(TITLE, label)
                    }
                    segmentDiv.add(link)
                }
                is BreadcrumbSegment.Current -> {
                    val label = resolveLabel(segment.labelKey)
                    val span = Span(label).apply {
                        element.setAttribute(TITLE, label)
                    }
                    segmentDiv.add(span)
                    segmentDiv.element.setAttribute("aria-current", "page")
                }
                is BreadcrumbSegment.Text -> {
                    val label = resolveLabel(segment.labelKey)
                    val span = Span(label).apply {
                        element.setAttribute(TITLE, label)
                    }
                    segmentDiv.add(span)
                }
            }
            ol.add(segmentDiv)
        }
        ol.addClassName("breadcrumb-list")
        return ol
    }

    // MEN-5 — label resolution itself lives in the top-level [resolveLabel] function
    // (`LabelKey.kt`), shared with `CommandPaletteDialog`; this class only calls it.

    companion object {
        /**
         * Pure function to compute breadcrumb segments from the current path, optional reference-data
         * group, and optional reference-data tab. Returns an empty list for unknown routes.
         * Segment labels are encoded as [LabelKey] objects, resolved to i18n strings at render time.
         */
        internal fun pathToSegments(
            path: String,
            refDataGroup: ReferenceDataGroup? = null,
            refDataTab: ReferenceDataTab? = null,
        ): List<BreadcrumbSegment> {
            val normalizedPath = path.removeSuffix("/")

            // Check if this is a reference-data route
            if (normalizedPath.startsWith("referenzdaten")) {
                return buildReferenceDataSegments(refDataGroup, refDataTab)
            }

            // Check for top-level nav item. NavItem.REFERENCE_DATA can never be the match here:
            // its own routePath is "referenzdaten", so any path resolving to it would already have
            // returned above — the `item != REFERENCE_DATA` guard this used to carry was dead code.
            val item = NavItem.activeItemFor(normalizedPath)
            if (item != null) {
                val section = item.section
                // SFS — a child entry (e.g. SETTINGS under SYSTEM) gets its parent as a link
                // segment: "Administration › System › Einstellungen", matching the drawer depth.
                // A plain two-level path (Text section + Current item) would flatten that.
                val parent = item.drawerParent()
                val mid = if (parent != null) {
                    listOf(BreadcrumbSegment.Link(LabelKey.NavItemKey(parent), parent.routePath))
                } else {
                    emptyList()
                }
                return listOf(BreadcrumbSegment.Text(LabelKey.NavSectionKey(section))) +
                    mid +
                    listOf(BreadcrumbSegment.Current(LabelKey.NavItemKey(item)))
            }

            // Unknown route: render nothing
            return emptyList()
        }

        private fun buildReferenceDataSegments(
            group: ReferenceDataGroup?,
            tab: ReferenceDataTab?,
        ): List<BreadcrumbSegment> {
            val result = mutableListOf<BreadcrumbSegment>()

            // "Referenzdaten" link at the top level
            result.add(BreadcrumbSegment.Link(LabelKey.ReferenceDataRoot, "referenzdaten"))

            if (group != null) {
                // Group link
                val groupPath = "referenzdaten/${group.slug}"
                result.add(BreadcrumbSegment.Link(LabelKey.RefDataGroupKey(group), groupPath))

                if (tab != null) {
                    // Tab label as current (no link)
                    result.add(BreadcrumbSegment.Current(LabelKey.RefDataTabKey(tab)))
                }
            }

            return result
        }
    }
}

/**
 * A single segment in the breadcrumb trail, which can be a link, current page, or plain text.
 */
sealed class BreadcrumbSegment(open val labelKey: LabelKey) {
    data class Link(override val labelKey: LabelKey, val routePath: String) : BreadcrumbSegment(labelKey)
    data class Current(override val labelKey: LabelKey) : BreadcrumbSegment(labelKey)
    data class Text(override val labelKey: LabelKey) : BreadcrumbSegment(labelKey)
}
