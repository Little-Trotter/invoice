package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Span
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * E2 — the oval "N new" indicator above [PrintJobListView]'s grid. Hidden while the count is zero
 * (no "0 new" clutter). Setting the same count again is a no-op, so overlapping push/poll/visibility
 * updates that each recompute the same number never touch the DOM twice — the pill only changes
 * when the number actually changes and never flips visibility on its way to a new value.
 */
internal class PrintJobNewCountBadge : Span(), TranslationProvider {

    private var shownCount = 0L

    init {
        addClassName("new-jobs-badge")
        element.setAttribute("role", "status")
        isVisible = false
    }

    override fun getComponent(): Component = this

    /** The count currently shown; `0` while the badge is hidden. */
    val count: Long get() = shownCount

    fun update(newCount: Long) {
        val target = newCount.coerceAtLeast(0L)
        if (target == shownCount) return
        shownCount = target
        if (target == 0L) {
            isVisible = false
            return
        }
        text = tr("printJobs.newCount", target)
        val label = tr("printJobs.newCount.label", target)
        element.setAttribute("aria-label", label)
        element.setAttribute("title", label)
        isVisible = true
    }
}
