package de.littletrotter.ui.view.mail

import de.littletrotter.application.mail.port.inbound.MailPlaceholderCatalogQueryPort
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewQueryPort
import de.littletrotter.application.mail.port.inbound.MailTemplateQueryPort
import de.littletrotter.application.mail.port.inbound.MailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.ResetMailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.SendMailTemplateBccTestUseCase

/**
 * The input ports the template list and its dialogs share. A plain holder, not a facade: it adds no
 * behaviour and only spares each dialog a six-argument constructor.
 */
internal class MailTemplatePorts(
    val query: MailTemplateQueryPort,
    val catalog: MailPlaceholderCatalogQueryPort,
    val preview: MailRenderPreviewQueryPort,
    val templates: MailTemplateUseCase,
    val reset: ResetMailTemplateUseCase,
    val bccTest: SendMailTemplateBccTestUseCase,
)
