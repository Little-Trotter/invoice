package de.littletrotter.infrastructure.classpath

import de.littletrotter.application.system.port.outbound.LibraryInfoService
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ClasspathLibraryInfoService : LibraryInfoService {

    private companion object {
        private const val VERSION_PROPERTY = "version"
        private const val UNKNOWN_VERSION = "unbekannt"
    }

    private val cached: List<LibraryInfoService.LibraryInfo> by lazy {
        val mustang = LibraryInfoService.LibraryInfo(
            name = "ZUGFeRD (Mustang)",
            version = readMustangVersion(),
            description = "ZUGFeRD / XRechnung implementation",
        )

        val kosit = LibraryInfoService.LibraryInfo(
            name = "KoSIT Validator",
            version = readKositScenarioVersion(),
            description = "KoSIT validation scenario",
        )

        listOf(mustang, kosit)
    }

    override fun listLibraries(): List<LibraryInfoService.LibraryInfo> = cached

    private fun readMustangVersion(): String {
        return try {
            val props = java.util.Properties()
            val resource = Thread.currentThread().contextClassLoader
                .getResourceAsStream("META-INF/maven/org.mustangproject/library/pom.properties")
            resource?.use { props.load(it) }
            props.getProperty(VERSION_PROPERTY) ?: UNKNOWN_VERSION
        } catch (_: Exception) {
            UNKNOWN_VERSION
        }
    }

    private fun readKositScenarioVersion(): String {
        val fromScenario = try {
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("scenarios.xml")
                ?: return readKositFallbackVersion()

            val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val dBuilder = dbFactory.newDocumentBuilder()
            stream.use { s ->
                val doc = dBuilder.parse(s)
                doc.documentElement.normalize()
                val nodes = doc.getElementsByTagName("version")
                if (nodes.length > 0) {
                    nodes.item(0).textContent.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }

        return fromScenario ?: readKositFallbackVersion()
    }

    private fun readKositFallbackVersion(): String {
        // Fallback: use version from KoSIT/Mustang validator dependency metadata
        return try {
            val props = java.util.Properties()
            val resource = Thread.currentThread().contextClassLoader
                .getResourceAsStream("META-INF/maven/org.mustangproject/validator/pom.properties")
            resource?.use { props.load(it) }
            props.getProperty(VERSION_PROPERTY) ?: UNKNOWN_VERSION
        } catch (_: Exception) {
            UNKNOWN_VERSION
        }
    }
}
