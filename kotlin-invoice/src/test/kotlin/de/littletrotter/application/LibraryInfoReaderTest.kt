package de.littletrotter.application

import de.littletrotter.infrastructure.classpath.ClasspathLibraryInfoService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryInfoReaderTest {

    @Test
    fun `reads mustang and kosit versions from classpath`() {
        val svc = ClasspathLibraryInfoService()
        val libs = svc.listLibraries()
        val mustang = libs.find { it.name.contains("Mustang") }
        val kosit = libs.find { it.name.contains("KoSIT") }

        assertEquals(true, mustang != null)
        assertEquals(true, kosit != null)
        assertEquals("2.16.3", mustang?.version)
        assertEquals("1.2.3-test", kosit?.version)
    }
}
