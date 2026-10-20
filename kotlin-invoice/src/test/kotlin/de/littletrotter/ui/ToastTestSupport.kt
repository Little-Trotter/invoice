package de.littletrotter.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification

private fun spansOf(component: Component): List<Span> =
    component.children.toList().flatMap { (if (it is Span) listOf(it) else emptyList()) + spansOf(it) }

/** The message a user reads in [notification]: every toast carries it in the first span of its
 *  row, while the repeat marker and the "more" link are separate components. */
internal fun toastText(notification: Notification): String =
    spansOf(notification).firstOrNull()?.text.orEmpty()
