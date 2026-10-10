package de.littletrotter.infrastructure.classpath

import de.littletrotter.application.system.LibraryInfoService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ClasspathLibraryInfoService] reads library metadata off `Thread.currentThread()
 * .contextClassLoader` (Maven pom.properties, KoSIT `scenarios.xml`). Boundary cases swap that
 * context classloader for a temporary, hand-built one so missing/malformed classpath resources
 * are reproducible without touching this module's real classpath.
 */
class ClasspathLibraryInfoServiceTest {

    @TempDir
    lateinit var tmp: Path

    private val svc = ClasspathLibraryInfoService()

    @Test
    fun `happy path - lists known libraries from the real classpath`() {
        val libs = svc.listLibraries()
        assertTrue(libs.size >= 2)
        val names = libs.map { it.name }
        assertTrue(names.contains("ZUGFeRD (Mustang)"))
        assertTrue(names.contains("KoSIT Validator"))
        // versions might be "unbekannt" when resources are missing; ensure non-blank
        assertTrue(libs.all { it.version.isNotBlank() })
    }

    private fun classLoaderFor(vararg resources: Pair<String, String>): ClassLoader {
        val dir = Files.createTempDirectory(tmp, "cov12-classpath-library-info")
        resources.forEach { (path, content) ->
            val file = dir.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
        }
        return URLClassLoader(arrayOf(dir.toUri().toURL()), null)
    }

    private fun listLibrariesWithContext(loader: ClassLoader): List<LibraryInfoService.LibraryInfo> {
        val original = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        return try {
            // A fresh instance per call — `cached` is a per-instance lazy, and each scenario
            // needs its own classpath view.
            ClasspathLibraryInfoService().listLibraries()
        } finally {
            Thread.currentThread().contextClassLoader = original
        }
    }

    @Test
    fun `boundary - missing classpath resources report unbekannt for both libraries`() {
        val libs = listLibrariesWithContext(classLoaderFor())

        assertEquals("unbekannt", libs.single { it.name == "ZUGFeRD (Mustang)" }.version)
        assertEquals("unbekannt", libs.single { it.name == "KoSIT Validator" }.version)
    }

    @Test
    fun `boundary - mustang and kosit versions are read from their own classpath resources when present`() {
        val loader = classLoaderFor(
            "META-INF/maven/org.mustangproject/library/pom.properties" to "version=2.16.0\n",
            "scenarios.xml" to "<?xml version=\"1.0\"?><scenarios><version>2.1.0</version></scenarios>",
        )

        val libs = listLibrariesWithContext(loader)

        assertEquals("2.16.0", libs.single { it.name == "ZUGFeRD (Mustang)" }.version)
        assertEquals("2.1.0", libs.single { it.name == "KoSIT Validator" }.version)
    }

    @Test
    fun `boundary - a scenarios xml without a version element falls back to the validator pom properties`() {
        val loader = classLoaderFor(
            "scenarios.xml" to "<?xml version=\"1.0\"?><scenarios><name>foo</name></scenarios>",
            "META-INF/maven/org.mustangproject/validator/pom.properties" to "version=1.5.0\n",
        )

        val libs = listLibrariesWithContext(loader)

        assertEquals("1.5.0", libs.single { it.name == "KoSIT Validator" }.version)
    }

    @Test
    fun `boundary - malformed scenarios xml is caught and falls back to the validator pom properties`() {
        val loader = classLoaderFor(
            "scenarios.xml" to "not xml at all <<<",
            "META-INF/maven/org.mustangproject/validator/pom.properties" to "version=1.5.1\n",
        )

        val libs = listLibrariesWithContext(loader)

        assertEquals("1.5.1", libs.single { it.name == "KoSIT Validator" }.version)
    }

    @Test
    fun `boundary - a malformed mustang pom properties value is caught and reported as unbekannt`() {
        val loader = classLoaderFor(
            "META-INF/maven/org.mustangproject/library/pom.properties" to "version=\\uZZZZ\n",
        )

        val libs = listLibrariesWithContext(loader)

        assertEquals("unbekannt", libs.single { it.name == "ZUGFeRD (Mustang)" }.version)
    }

    @Test
    fun `boundary - a malformed validator pom properties value during fallback is caught and reported as unbekannt`() {
        val loader = classLoaderFor(
            "META-INF/maven/org.mustangproject/validator/pom.properties" to "version=\\uZZZZ\n",
        )

        val libs = listLibrariesWithContext(loader)

        assertEquals("unbekannt", libs.single { it.name == "KoSIT Validator" }.version)
    }
}
