package de.littletrotter.shared

/**
 * Single source for the names and versions of the validation toolchain, shown to users in the
 * combined findings dialog's provenance hint (kotlin-invoice) and reported with findings by the
 * worker-side validators (kotlin-xpdf). Every number here is pinned against its real source by a
 * test, so a dependency or resource upgrade fails loudly instead of letting a UI copy rot:
 *
 * - [KOSIT_VALIDATOR_VERSION] — the `org.kosit:validator` dependency (kotlin-xpdf/pom.xml).
 *   `ValidationToolchainPinTest` (kotlin-xpdf) reads the resolved jar's `pom.properties` from the
 *   classpath and fails when it differs.
 * - [XRECHNUNG_VERSION] / [XRECHNUNG_CONFIG_RELEASE] — the KoSIT validator-configuration release.
 *   `EmbeddedKositValidator.SCENARIO_ZIP` (kotlin-xpdf) builds its scenario resource path from
 *   these two constants, and `ValidationToolchainPinTest` asserts that resource actually exists
 *   on the classpath — a config upgrade that renames the ZIP forces both constants along.
 * - [PDFA_CONFORMANCE_LEVEL] — the project's deliberate conformance target (PD-01), a constant
 *   by design, not a version to be sourced from anywhere.
 * - [VERAPDF_DEFAULT_VERSION] — the pinned veraPDF CLI default. `VeraPdfCliValidator`
 *   (kotlin-xpdf) consumes this same constant as its `DEFAULT_VERSION`, so the version it
 *   reports with every finding and the one the UI hint names can never drift apart. The
 *   `VERAPDF_VERSION` env can still override the worker's effective pin at runtime.
 * - [BUSINESS_VALIDATION_NAME] — the product name; a proper noun, never translated.
 */
object ValidationToolchain {
    const val KOSIT_VALIDATOR_VERSION = "1.6.3"
    const val XRECHNUNG_VERSION = "3.0.2"
    const val XRECHNUNG_CONFIG_RELEASE = "2026-01-31"
    const val PDFA_CONFORMANCE_LEVEL = "PDF/A-3b"
    const val VERAPDF_DEFAULT_VERSION = "1.30.2"
    const val BUSINESS_VALIDATION_NAME = "Little Trotter"
}
