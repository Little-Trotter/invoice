package de.littletrotter.application.mail.port.inbound

/** Input port answering whether the mail component is deployed, so the UI offers its entry points only then. */
interface MailAvailabilityQueryPort {
    /** `true` when the mail component is deployed behind the same reverse proxy. */
    val enabled: Boolean
}
