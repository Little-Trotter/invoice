package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.RegenerateInvoiceDocumentUseCase
import de.littletrotter.domain.exception.InvoiceDomainException
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.logDomainRejection
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Makes a terminally failed document generation visible on an issued invoice and offers the
 * manual re-trigger. Without it the invoice would silently stay without its document once all
 * retries are spent. Rebuilt on every [render]; empty (no banner) when nothing failed.
 */
@Dependent
class DocumentGenerationFailureBanner @Inject constructor(
    private val regenerateInvoiceDocumentUseCase: RegenerateInvoiceDocumentUseCase,
) : Div(), TranslationProvider {

    private val log = Logger.getLogger(javaClass)

    override fun getComponent(): Component = this

    /** [onRegenerated] runs after the new job is committed, so the caller can reload the invoice. */
    fun render(current: InvoiceDetailDto, onRegenerated: () -> Unit) {
        removeAll()
        val failure = current.documentGenerationFailure
        isVisible = failure != null
        if (failure == null) return
        addClassNames("document-generation-failure-banner", "lt-warning-banner")
        val message = if (failure.reason.isBlank()) {
            tr("documentGeneration.failed.noReason")
        } else {
            tr("documentGeneration.failed", failure.reason)
        }
        lateinit var action: Button
        action = AppButtons.secondary(tr("documentGeneration.regenerate"), ActionIcons.REFRESH) {
            // Disabled right away: a second click before the reload must not reach the use case.
            action.isEnabled = false
            regenerate(current.id, onRegenerated, action)
        }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }
        add(HorizontalLayout().apply {
            isSpacing = true
            setWidthFull()
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            add(VaadinIcon.WARNING.create(), Span(message).apply { addClassName("lt-flex-grow") }, action)
        })
    }

    private fun regenerate(invoiceId: Long, onRegenerated: () -> Unit, button: Button) {
        try {
            val jobId = regenerateInvoiceDocumentUseCase.regenerate(invoiceId)
            UiNotifications.info(tr("documentGeneration.regenerate.started", jobId))
            onRegenerated()
        } catch (e: InvoiceDomainException) {
            refuse(e, invoiceId, button)
        } catch (e: SecurityException) {
            refuse(e, invoiceId, button)
        }
    }

    private fun refuse(e: Exception, invoiceId: Long, button: Button) {
        logDomainRejection(log, "Document regeneration refused for invoiceId=$invoiceId", e)
        UiNotifications.error(UiNotifications.resolveMessage(this, e))
        button.isEnabled = true
    }
}
