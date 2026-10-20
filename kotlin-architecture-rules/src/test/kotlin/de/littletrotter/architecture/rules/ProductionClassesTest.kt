package de.littletrotter.architecture.rules

import com.tngtech.archunit.core.importer.Location
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class ProductionClassesTest {

    @Test
    fun `main output is imported and test output is not`() {
        val classes = ProductionClasses.of("de.littletrotter.architecture.rules")
        assertTrue(classes.contain(TimeRules::class.java))
        assertFalse(classes.contain(ProductionClassesTest::class.java))
    }

    @Test
    fun `classes from a sibling's test-jar are left out`() {
        val testJar = Location.of(URI("jar:file:/repo/quote-service/target/quote-service-1.0-tests.jar!/a/Fixture.class"))
        val mainJar = Location.of(URI("jar:file:/repo/quote-service/target/quote-service-1.0.jar!/a/Service.class"))
        assertFalse(ProductionClasses.doNotIncludeTestJars.includes(testJar))
        assertTrue(ProductionClasses.doNotIncludeTestJars.includes(mainJar))
    }
}
