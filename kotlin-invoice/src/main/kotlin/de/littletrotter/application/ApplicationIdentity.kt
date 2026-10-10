package de.littletrotter.application

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Identifies the running application for display and reporting. "Haflinger" was the codename of
 * what used to be a separately built edition (always free and open source); that split is gone,
 * and the whole feature set is unconditionally part of the product, so a single bean is enough.
 */
@ApplicationScoped
class ApplicationIdentity @Inject constructor(
    // `@param:` pins this to the constructor parameter only; Kotlin 2.4's default
    // annotation-use-site-target policy would otherwise also copy `@ConfigProperty` onto the
    // backing final field, which Qodana's CDI inspector misreads as an illegal final-field
    // injection even though CDI never reads the field (no `@Inject` there).
    @param:ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    private val version: String,
) {

    /** Human-facing product name. The "Haflinger" suffix left with the edition split (see class
     *  KDoc) — the product carries the plain name since 22.08.2026. */
    val displayName: String = DISPLAY_NAME

    /** The application release version (single source: `quarkus.application.version`). */
    fun version(): String = version

    /** PO 2026-08-30 (abends) — the edition NAME returns as pure branding: the System area
     *  presents the product as edition "Haflinger". This deliberately does NOT reintroduce
     *  edition-based feature gating (the removal of the split stands; the full feature set
     *  stays unconditional) and [displayName] stays the plain product name. */
    val edition: String = EDITION

    /** The single composed identity string every export/report titles itself with (the
     *  "Export" cover sheet's own title row, and the workbook's own OOXML core properties):
     *  `"Little Trotter Haflinger (0.7.0-SNAPSHOT)"`. PO decision P1 (2026-09-10): no hyphen. */
    fun productLine(): String = "$displayName $edition (${version()})"

    private companion object {
        private const val DISPLAY_NAME = "Little Trotter"
        private const val EDITION = "Haflinger"
    }
}
