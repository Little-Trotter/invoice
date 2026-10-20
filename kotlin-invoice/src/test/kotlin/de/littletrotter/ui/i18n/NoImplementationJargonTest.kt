package de.littletrotter.ui.i18n

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class NoImplementationJargonTest {

    private fun load(resourceName: String): Properties {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)) {
            "Bundle resource '$resourceName' not found on the classpath"
        }
        return Properties().also { props -> stream.use { props.load(it) } }
    }

    @Test
    fun `no user-visible message mentions Kotlin`() {
        val hits = listOf("messages.properties", "messages_de.properties").flatMap { file ->
            val props = load("i18n/$file")
            props.stringPropertyNames()
                .filter { key -> props.getProperty(key).contains("Kotlin", ignoreCase = true) }
                .map { key -> "$file: $key" }
        }

        assertTrue(hits.isEmpty(), "implementation jargon in user-visible text: $hits")
    }
}
