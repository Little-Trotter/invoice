package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment

private const val DEFAULT_PAGE_SIZE = 20

class Pager : HorizontalLayout() {
    private val prev = Button("←")
    private val next = Button("→")
    private val label = Span()
    private var current = 0
    private var totalPages = 1
    private var pageSize = DEFAULT_PAGE_SIZE
    private var totalItems = 0
    private var listener: ((page: Int, pageSize: Int) -> Unit)? = null

    init {
        addClassName("sp-pager")
        alignItems = Alignment.CENTER
        // No current>0 / current<totalPages-1 guard here: update() keeps prev/next's own
        // isEnabled in lockstep with exactly that condition, and Vaadin's Button.click() only
        // fires the listener at all when the button isEnabled — the guard the label/index math
        // below still needs is already enforced one layer up, by the button itself.
        prev.addClickListener { current--; fire() }
        next.addClickListener { current++; fire() }
        add(prev, label, next)
        update()
    }

    private fun update() {
        // totalPages is only ever assigned in setTotalItems, which always normalizes it to >= 1
        // (the totalItems<=0 branch there sets exactly 1) — no <=0 fallback needed here.
        label.text = "${current + 1} / $totalPages"
        prev.isEnabled = current > 0
        next.isEnabled = current < totalPages - 1
    }

    private fun fire() {
        update()
        listener?.invoke(current, pageSize)
    }

    fun setTotalItems(totalItems: Int) {
        this.totalItems = totalItems
        // pageSize is only ever assigned here (the 20 default) or in setPageSize, which already
        // normalizes it to >= 1 — no second <=0 fallback needed on this read.
        totalPages = if (totalItems <= 0) 1 else (totalItems + pageSize - 1) / pageSize
        if (current >= totalPages) current = totalPages - 1
        update()
    }

    fun setPageSize(size: Int) {
        pageSize = if (size <= 0) DEFAULT_PAGE_SIZE else size
        setTotalItems(totalItems)
    }

    fun onPageChange(listener: (page: Int, pageSize: Int) -> Unit) {
        this.listener = listener
    }

    fun goToPage(pageIndex: Int, fireListener: Boolean = true) {
        if (pageIndex !in 0 until totalPages) return
        if (current == pageIndex && !fireListener) return

        current = pageIndex
        update()
        if (fireListener) {
            listener?.invoke(current, pageSize)
        }
    }
}
