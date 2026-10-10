package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.UI
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.RouteConfiguration
import com.vaadin.flow.router.RouteParameters
import de.littletrotter.application.quote.port.inbound.QuoteB2bValue
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteParty
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteSummary
import de.littletrotter.application.quote.port.inbound.QuoteTotals
import de.littletrotter.application.quote.port.inbound.QuoteVatGroup
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.ui.view.invoice.InvoiceListView
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

internal val ALL_QUOTE_PERMISSIONS = arrayOf(AppPermission.QUOTE_VIEW, AppPermission.QUOTE_EDIT, AppPermission.QUOTE_SEND, AppPermission.QUOTE_SETTINGS_EDIT, AppPermission.QUOTE_LIBRARY_EDIT, AppPermission.QUOTE_LIBRARY_ARCHIVE, AppPermission.INVOICE_FULL_EDIT)

/** A role provider that grants exactly [granted]. */
internal fun rolesWith(vararg granted: AppPermission): AppRoleProvider {
    val roles: AppRoleProvider = mock()
    whenever(roles.hasPermission(org.mockito.kotlin.any())).thenAnswer { it.getArgument<AppPermission>(0) in granted }
    return roles
}

internal fun quoteParty() = QuoteParty(
    customerId = "c-1",
    name = "Muster GmbH",
    countryCode = "DE",
    customerType = "B2B",
    documentLanguage = "de",
    customerNumber = "K-100",
    street = "Hauptstraße 1",
    postalCode = "50667",
    city = "Köln",
    vatId = "DE123456789",
)

internal fun quoteItem(position: Int = 1, id: String = "i-$position") = QuoteItem(
    id = id,
    position = position,
    description = "Beratung $position",
    quantity = BigDecimal("2.50"),
    category = InvoiceItemCategory.SERVICE,
    unit = InvoiceItemUnit.HOUR,
    unitPrice = Money.of("100.00"),
    vatCategory = VatCategory.STANDARD,
    vatRate = BigDecimal("19.00"),
    net = Money.of("250.00"),
    articleId = "a-1",
    articleNumber = "A-1",
    customerReference = "K-77",
    customerArticleName = "Kundenwerk",
)

internal fun quoteDetail(
    status: QuoteStatus = QuoteStatus.DRAFT,
    number: String? = "AN-2026-0001",
    id: String = "q-1",
    modify: (QuoteDetail) -> QuoteDetail = { it },
): QuoteDetail = modify(
    QuoteDetail(
        id = id,
        version = 3,
        status = status,
        party = quoteParty(),
        priceDisplay = QuotePriceDisplay.AUTO,
        quoteDate = LocalDate.of(2026, 10, 1),
        validUntil = LocalDate.of(2026, 10, 31),
        totals = QuoteTotals(
            net = Money.of("250.00"),
            vat = Money.of("47.50"),
            gross = Money.of("297.50"),
            groups = listOf(QuoteVatGroup(VatCategory.STANDARD, BigDecimal("19.00"), Money.of("250.00"), Money.of("47.50"))),
        ),
        number = number,
        subject = "Beratung Oktober",
        introText = "Einleitung",
        closingText = "Schluss",
        noteText = "Hinweis",
        items = listOf(quoteItem(1), quoteItem(2)),
        b2bValues = listOf(QuoteB2bValue(13, "B-4711")),
    ),
)

internal fun quoteSummary(id: String = "q-1", status: QuoteStatus = QuoteStatus.SENT) = QuoteSummary(
    id = id,
    customerName = "Muster GmbH",
    status = status,
    quoteDate = LocalDate.of(2026, 10, 1),
    validUntil = LocalDate.of(2026, 10, 31),
    netTotal = Money.of("250.00"),
    number = "AN-2026-0001",
    subject = "Beratung",
)

/** A navigation event for the view under test; [id] becomes the `:id` route parameter. */
internal fun quoteEvent(id: String? = "q-1", path: String = "quotes"): BeforeEnterEvent {
    val event = mock<BeforeEnterEvent>()
    whenever(event.routeParameters).thenReturn(if (id == null) RouteParameters(emptyMap()) else RouteParameters("id", id))
    whenever(event.location).thenReturn(Location(path))
    return event
}

internal fun registerQuoteRoutes() {
    val config = RouteConfiguration.forSessionScope()
    config.setAnnotatedRoute(InvoiceListView::class.java)
    config.setAnnotatedRoute(QuoteListView::class.java)
    config.setAnnotatedRoute(QuoteDetailView::class.java)
    config.setAnnotatedRoute(QuoteEditorView::class.java)
}

/** Fires the click listeners even on a disabled button, which a real browser could never do. */
internal fun fireClick(button: Button) {
    ComponentUtil.fireEvent(button, ClickEvent(button))
}

internal fun currentPath(): String = UI.getCurrent().internals.activeViewLocation.path

internal fun descendants(root: Component): List<Component> =
    root.children.toList().flatMap { listOf(it) + descendants(it) }

/** Everything the user currently reads in open notifications. */
internal fun notificationTexts(): List<String> {
    MockVaadin.clientRoundtrip()
    return getNotifications().map { notification ->
        de.littletrotter.ui.toastText(notification)
    }
}

internal fun visibleText(root: Component): String = (listOf(root) + descendants(root)).joinToString(" ") { it.element.text.orEmpty() }

/** A translation context for components that are not views themselves, such as the item dialogs. */
internal fun testHost(): de.littletrotter.ui.i18n.TranslationProvider = object : de.littletrotter.ui.i18n.TranslationProvider {
    override fun getComponent(): Component? = UI.getCurrent()
}

/** What a dialog's footer holds; the footer is not a component itself, so its children are read from the element tree. */
internal fun footerComponents(dialog: com.vaadin.flow.component.dialog.Dialog): List<Component> =
    dialog.footer.element.children.map { it.component.orElseThrow() }.toList()
