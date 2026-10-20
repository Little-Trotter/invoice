package de.littletrotter.application.quote.port.inbound

/** Input port answering whether offers are deployed and switched on, so menu, routes and write calls share one rule. */
interface QuoteAvailabilityQueryPort {
    /** The deployment fact: the offer component runs behind the same reverse proxy. */
    val enabled: Boolean

    /** Offers are shown and may be changed: deployed and not switched off in the settings. */
    val offered: Boolean
}
