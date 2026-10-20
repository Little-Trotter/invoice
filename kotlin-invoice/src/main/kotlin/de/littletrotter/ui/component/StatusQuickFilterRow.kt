package de.littletrotter.ui.component

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.dom.Element

/**
 * How one status appears as a quick-filter chip: its visible [label], the colour tone
 * [tone] shared with the status chips elsewhere, the [glyph] and the spoken form of the
 * count. The glyph doubles as the legend of the list: a colour-blind user still tells states apart.
 */
internal data class ChipSpec(
    val label: String,
    val tone: ChipTone,
    val glyph: VaadinIcon,
    val countAriaLabel: (Long) -> String,
)

/**
 * A row of status chips above a list: one chip per status with its count, the toggled status
 * highlighted. A persistent container re-rendered by [render] on every refresh, so its click
 * listeners survive data refreshes. Zero-count statuses get no chip: a status nothing is in is
 * not a filter worth offering.
 */
internal class StatusQuickFilterRow<T : Enum<T>>(
    private val statuses: List<T>,
    ariaLabel: String,
    private val chip: (T) -> ChipSpec,
    private val onToggle: (T) -> Unit,
) : Div() {

    init {
        addClassName("validation-quick-filters")
        element.setAttribute("aria-label", ariaLabel)
    }

    /** Re-renders every chip from one batched grouped-count result. */
    fun render(counts: Map<T, Long>, active: T?) {
        removeAll()
        statuses.forEach { st ->
            val count = counts[st] ?: 0L
            if (count > 0L) add(buildChip(st, count, st == active))
        }
    }

    private fun buildChip(st: T, count: Long, active: Boolean): NativeButton {
        val spec = chip(st)
        return NativeButton().apply {
            applyChipTone(spec.tone)
            addClassName("quick-filter-chip")
            if (active) addClassName("quick-filter-chip--active")
            element.setAttribute("aria-pressed", active.toString())
            // Screen readers get the count spelled out; "Entwurf 12" alone reads as an identifier.
            element.setAttribute("aria-label", spec.countAriaLabel(count))
            add(
                spec.glyph.create().apply {
                    setSize("0.85rem")
                    element.setAttribute("aria-hidden", "true")
                },
            )
            // A direct text-node child keeps NativeButton.text == "$label $count".
            element.appendChild(Element.createText("${spec.label} $count"))
            addClickListener { onToggle(st) }
        }
    }
}
