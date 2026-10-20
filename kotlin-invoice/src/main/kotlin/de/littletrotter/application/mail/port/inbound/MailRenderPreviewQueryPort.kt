package de.littletrotter.application.mail.port.inbound

/** Renders a mail exactly as the component would send it, without sending anything. */
fun interface MailRenderPreviewQueryPort {

    /** Renders the current template of the requested kind and document family against the values. */
    fun renderPreview(request: MailRenderPreviewRequest): MailRenderPreview
}
