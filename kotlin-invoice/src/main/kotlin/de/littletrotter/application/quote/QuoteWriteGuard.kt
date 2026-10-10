package de.littletrotter.application.quote

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Refuses offer-changing calls while the administrator has switched the module off.
 *
 * Hiding the UI alone does not protect: a deep link or an already open session would still reach
 * the write use cases. Reading stays allowed, so existing offers remain visible once switched
 * back on, and background integration (master-data replica, accepted offers) does not pass here.
 */
@ApplicationScoped
class QuoteWriteGuard @Inject constructor(
    private val availability: QuoteAvailability,
) {

    fun requireEnabled() {
        if (!availability.offered) {
            throw QuoteModuleDisabledException()
        }
    }
}
