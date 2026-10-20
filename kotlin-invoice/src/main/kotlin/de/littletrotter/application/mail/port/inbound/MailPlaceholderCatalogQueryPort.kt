package de.littletrotter.application.mail.port.inbound

/** Reads the placeholders the mail component can fill for one kind of mail. */
fun interface MailPlaceholderCatalogQueryPort {

    /** Every placeholder usable in templates of [kind], in the order the component lists them. */
    fun placeholders(kind: MailTemplateKind): List<MailPlaceholderEntry>
}
