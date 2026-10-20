package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.ListDataProvider
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.renderer.ComponentRenderer

/**
 * Casts a loosely-typed value — a reflectively-read field, a `Grid`/`DataProvider` found only by
 * its erased class, or a renderer only known through its base `Renderer` type — to [T]. Reified,
 * so the cast targets the type parameter itself: the compiler always accepts a cast to its own
 * reified type parameter through that parameter's class token, instead of having to verify a
 * literal parameterized type piece by piece, which is what forces an unchecked cast everywhere
 * this helper replaces one.
 */
inline fun <reified T : Any> Any?.requireAs(): T =
    this as? T
        ?: error("expected ${T::class.simpleName}, got ${if (this == null) "null" else this::class.simpleName}")

/**
 * Every item a [DataProvider] currently holds, ignoring paging. A `Grid`'s data provider is only
 * ever known as `DataProvider<T, *>` — its filter type is existential — so this captures that
 * filter type under its own name instead of naming it, needing no cast, checked or not.
 */
fun <T : Any> DataProvider<T, *>.fetchAll(): List<T> = fetchCapturingFilter(this)

private fun <T : Any, F> fetchCapturingFilter(provider: DataProvider<T, F>): List<T> = provider.fetch(Query<T, F>()).toList()

/** Item count of a [DataProvider], same existential-capture idiom as [fetchAll]. */
fun <T : Any> DataProvider<T, *>.countAll(): Int = countCapturingFilter(this)

private fun <T : Any, F> countCapturingFilter(provider: DataProvider<T, F>): Int = provider.size(Query<T, F>())

/** Every row a [Grid] currently holds — [fetchAll] on its own data provider. */
fun <T : Any> Grid<T>.allRows(): List<T> = dataProvider.fetchAll()

/** Row count of a [Grid] — [countAll] on its own data provider. */
fun <T : Any> Grid<T>.rowCount(): Int = dataProvider.countAll()

/**
 * The raw backing collection of a Grid's in-memory [ListDataProvider], bypassing any active
 * client-side filter — for assertions about the whole underlying set, not about what a search or
 * filter currently narrows the grid to.
 */
inline fun <reified T : Any> DataProvider<T, *>.listItems(): Collection<T> = requireAs<ListDataProvider<T>>().items

/** [listItems] straight off a [Grid], for the common case of asserting on its full row set. */
inline fun <reified T : Any> Grid<T>.listRows(): Collection<T> = dataProvider.listItems()

/**
 * The component a Grid column's [ComponentRenderer] currently produces for [item]. Grid itself
 * only ever invokes an `addComponentColumn` renderer while painting an actual row on the client,
 * so tests call it directly instead of forcing a real client repaint.
 */
inline fun <reified T : Any> Grid.Column<T>.componentFor(item: T): Component =
    renderer.requireAs<ComponentRenderer<Component, T>>().createComponent(item)
