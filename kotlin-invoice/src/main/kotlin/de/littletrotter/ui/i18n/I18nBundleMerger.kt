package de.littletrotter.ui.i18n

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Resolves the flat key-to-value map for the runtime-facing `/api/i18n/{locale}` endpoint: the
 * English base bundle (`i18n/messages.properties`) overlaid with the requested locale's override
 * bundle (`i18n/messages_<locale>.properties`) when one exists on the classpath. Kept free of any
 * JAX-RS/CDI dependency so the merge behavior is unit-testable without booting Quarkus;
 * [I18nBundleResource] is a thin adapter on top of it.
 */
object I18nBundleMerger {

    private const val DEFAULT_BUNDLE_PATH = "i18n/messages.properties"
    private val VALID_LOCALE = Regex("^[a-z]{2}$")

    /**
     * A [locale] failing the two-lowercase-letter check is treated as "no override" (base bundle
     * only) rather than used to probe the classpath with an unvalidated file name — this also
     * covers an unknown-but-valid locale (e.g. "fr"), for which no override file exists either.
     */
    fun resolve(locale: String, classLoader: ClassLoader = javaClass.classLoader): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(loadBundle(DEFAULT_BUNDLE_PATH, classLoader, required = true))
        if (VALID_LOCALE.matches(locale)) {
            merged.putAll(loadBundle("i18n/messages_$locale.properties", classLoader, required = false))
        }
        return merged
    }

    private fun loadBundle(resourcePath: String, classLoader: ClassLoader, required: Boolean): Map<String, String> {
        val stream = classLoader.getResourceAsStream(resourcePath) ?: run {
            check(!required) { "Required i18n bundle '$resourcePath' not found on the classpath" }
            return emptyMap()
        }
        // Properties.load(InputStream) assumes ISO-8859-1; the bundle files are UTF-8, so the
        // stream must be wrapped in a Reader for non-ASCII values (umlauts, etc.) to survive.
        val properties = Properties()
        stream.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader -> properties.load(reader) }
        }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }
}
