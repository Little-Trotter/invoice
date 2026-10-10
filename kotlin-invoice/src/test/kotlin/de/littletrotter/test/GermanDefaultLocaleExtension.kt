package de.littletrotter.test

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.Locale

/**
 * German locale by default, English as backup (PO decision 2026-09-02).
 *
 * Pins the JVM-wide default [Locale] to [Locale.GERMANY] before any test class runs, so
 * formatting that reads [Locale.getDefault] (e.g. the plain `String.format("%.1f%%", ...)` call
 * in `InvoiceStatisticsView`) renders deterministically ("50,0%") in this module's test suite on
 * every host — CI, a container image without German locale data, a developer machine whose OS
 * locale is English — instead of depending on host-JVM luck.
 *
 * There is no single shared Karibu test base class to hook this into (every `*KaribuTest` in this
 * module declares its own `@BeforeEach { MockVaadin.setup() }`), so rather than sprinkling
 * `Locale.setDefault` into each one, this extension is registered once via
 * `src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` and picked up
 * automatically for every test class through JUnit 5's extension autodetection (enabled in
 * `src/test/resources/junit-platform.properties`) — the "one place" for this module's tests, the
 * counterpart to `DefaultLocaleInitializer` on the production side.
 */
class GermanDefaultLocaleExtension : BeforeAllCallback {

    override fun beforeAll(context: ExtensionContext) {
        Locale.setDefault(Locale.GERMANY)
    }
}
