package de.littletrotter.application.settings

import de.littletrotter.domain.settings.FeatureFlag

/**
 * SFS — pure AND-compositions of "is it in this deployment" with "did the administrator allow it".
 *
 * Both gates answer independent questions: deploymentMailInstalled (the `mail.enabled` config,
 * see [de.littletrotter.application.mail.MailAvailability]) answers whether the mail component
 * exists at all; [FeatureFlag.EMAIL_SEND] answers whether sending may be offered while it does.
 * A function may therefore be *effectively* off either because nobody installed the component or
 * because an admin switched it off — every consumer that renders or refuses renders the AND, and
 * only the Einstellungen view tells the two causes apart, because its hint says
 * "deployment-seitig deaktiviert" purely on the deployment half.
 *
 * Lives as one object of pure functions, same "test the companion, not the CDI" idiom as
 * [de.littletrotter.ui.MainLayout]'s `visibleNavItems`: the semantics here are the ones worth
 * pinning for every future consumer (the drawer, the palette, the invoice mail tab, the dispatch
 * service), and none of them should get to re-invent an OR or a wrong default.
 */
object FeatureOffers {

    /** Mail sends may be offered/executed: component installed AND the feature flag on. */
    fun mailOffered(deploymentMailInstalled: Boolean, emailSendFlagEnabled: Boolean): Boolean =
        deploymentMailInstalled && emailSendFlagEnabled

    /** Offers may be shown/written: the offer component is deployed AND the feature flag on. The
     *  deployment stays the outer condition; the flag only withdraws the offer from users. */
    fun quoteOffered(deploymentQuoteInstalled: Boolean, quoteFlagEnabled: Boolean): Boolean =
        deploymentQuoteInstalled && quoteFlagEnabled

    /** Per-diem affordances (add buttons, filter, reference data tab) render: flag on. There is
     *  no deployment half — the capability ships with the application. */
    fun perDiemOffered(perDiemFlagEnabled: Boolean): Boolean = perDiemFlagEnabled
}
