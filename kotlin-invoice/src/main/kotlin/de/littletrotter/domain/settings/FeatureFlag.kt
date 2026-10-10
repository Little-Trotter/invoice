package de.littletrotter.domain.settings

/**
 * Feature flags used to enable/disable application features.
 *
 * Each flag has a unique [key] that is persisted in the database.
 * If no row exists for a flag or the DB is unreadable, [defaultEnabled] applies (fail-open).
 */
enum class FeatureFlag(
    val key: String,
    val labelKey: String,
    val descriptionKey: String,
    val defaultEnabled: Boolean,
) {
    PER_DIEM_UI(
        key = "feature.perdiem.enabled",
        labelKey = "settings.flag.perdiem.label",
        descriptionKey = "settings.flag.perdiem.description",
        defaultEnabled = true,
    ),
    EMAIL_SEND(
        key = "feature.email.enabled",
        labelKey = "settings.flag.email.label",
        descriptionKey = "settings.flag.email.description",
        defaultEnabled = true,
    ),

    QUOTE(
        key = "feature.quote.enabled",
        labelKey = "settings.flag.quote.label",
        descriptionKey = "settings.flag.quote.description",
        defaultEnabled = true,
    ),

    /** PO decision 2026-09-28 — off by default: the KoSIT XRechnung validator cannot validate
     *  the EXTENDED profile at all, so escalating a Skonto invoice to EXTENDED automatically
     *  (the earlier PO decision) left it unvalidatable for every seller who relies on that
     *  validator. Admin opt-in via this flag instead; the default keeps every Skonto invoice on
     *  EN16931, with the cash discount travelling only as text in the payment terms. */
    EXTENDED_PROFILE(
        key = "feature.extended-profile.enabled",
        labelKey = "settings.flag.extendedProfile.label",
        descriptionKey = "settings.flag.extendedProfile.description",
        defaultEnabled = false,
    );

    companion object {
        /**
         * Returns the [FeatureFlag] matching the given persisted [key], or `null` if not found.
         */
        fun fromKey(key: String): FeatureFlag? = entries.firstOrNull { it.key == key }
    }
}
