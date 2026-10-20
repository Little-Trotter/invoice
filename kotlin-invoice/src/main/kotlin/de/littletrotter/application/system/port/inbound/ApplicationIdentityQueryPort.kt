package de.littletrotter.application.system.port.inbound

/** Input port for the product name, edition and release version shown in the UI and in exports. */
interface ApplicationIdentityQueryPort {
    /** Human-facing product name. */
    val displayName: String

    /** The application release version. */
    fun version(): String

    /** The edition name used for branding only. */
    val edition: String

    /** Product name, edition and version composed into the single title string of exports and reports. */
    fun productLine(): String
}
