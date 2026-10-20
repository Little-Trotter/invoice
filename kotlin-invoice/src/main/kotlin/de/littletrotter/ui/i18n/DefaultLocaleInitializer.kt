package de.littletrotter.ui.i18n

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.Locale

/**
 * German locale by default, English as backup (PO decision 2026-09-02,
 * .
 *
 * Pins the JVM-wide default [Locale] to [Locale.GERMANY] once at startup so number/percent
 * formatting that reads [Locale.getDefault] — e.g. the plain `String.format("%.1f%%", ...)` call
 * in `InvoiceStatisticsView` — renders deterministically ("50,0%") on every host instead of
 * depending on whatever locale the JVM happened to inherit from the OS/container ("50.0%" on a
 * non-German host).
 *
 * This is the JVM-mode counterpart to `quarkus.default-locale`/`quarkus.locales` in
 * `application.properties`, which only affect native-image locale-data bundling and do not call
 * [Locale.setDefault] themselves. Vaadin's own UI/session locale — which governs
 * ResourceBundle-backed i18n lookups, not plain unlocalized JDK formatting — is set separately in
 * [de.littletrotter.ui.MainLayout.ensureLocale]; the two are independent mechanisms that both
 * need to agree on German as the default.
 */
@ApplicationScoped
class DefaultLocaleInitializer {

    // The StartupEvent parameter is required by the CDI observer contract (it's what makes ARC
    // invoke this method at startup) even though the body never reads it — named `ignored` (not
    // `event`) so detekt's UnusedParameter allowedNames convention recognizes the omission as
    // deliberate; same shape as DevDataFixtures.onStart's own @Observes parameter.
    fun onStart(@Observes ignored: StartupEvent) {
        Locale.setDefault(Locale.GERMANY)
    }
}
