package de.littletrotter.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * PO 30.08.2026 — pins the product identity after the edition split's removal: the
 * user-visible name is exactly "Little Trotter", never again the historical "Haflinger" edition
 * suffix. Ordered after a live report of "Little Trotter Haflinger" on the /system page (which
 * turned out to be a stale browser/PWA cache — the guard below makes sure the string can also
 * never silently return in code or i18n).
 */
class ApplicationIdentityTest {

    private val identity = ApplicationIdentity(version = "9.9.9-test")

    @Test
    fun `displayName is exactly Little Trotter`() {
        assertEquals("Little Trotter", identity.displayName)
    }

    @Test
    fun `displayName never carries the edition suffix - branding lives in edition()`() {
        assertFalse(identity.displayName.contains("Haflinger"))
    }

    @Test
    fun `version passes the configured value through unchanged`() {
        assertEquals("9.9.9-test", identity.version())
    }

    // ── Guard — the string must not resurface in any user-visible i18n value ───────────

    private fun bundle(name: String): Properties {
        val stream = javaClass.classLoader.getResourceAsStream("i18n/$name")
            ?: error("Missing bundle i18n/$name")
        return Properties().also { props -> stream.use { props.load(it.reader(Charsets.UTF_8)) } }
    }

    /** PO 2026-08-30 (abends) — the edition name is sanctioned branding again, but ONLY on the System area's own keys; everywhere else it stays banned (no gating, no scattered branding). German locale by default, English as backup: the one bundle is split into `messages.properties` (English backup) and `messages_de.properties` (German, moved verbatim) — the rule applies identically to both. */
    @Test
    fun `Haflinger appears in i18n values only on the sanctioned System-area keys`() {
        val sanctionedKeys = setOf<String>()
        listOf("messages.properties", "messages_de.properties").forEach { name ->
            val offenders = bundle(name).entries
                .filter { (_, v) -> (v as String).contains("Haflinger") }
                .filterNot { (k, _) -> k in sanctionedKeys }
            assertFalse(
                offenders.isNotEmpty(),
                "$name may carry the edition name only on $sanctionedKeys, found: ${offenders.map { it.key }}",
            )
        }
    }

    /** Same resource-parse drift-test shape as `B2bTermSchemaRangeTest`: scans the module's main sources so a scattered "Haflinger" literal breaks plain `mvn test`. PO 2026-08-30 (abends): the sanctioned locations are [ApplicationIdentity] (edition() + history KDoc), the System-Status edition card ([de.littletrotter.ui.view.system.StatusPanel]) and the System-area i18n keys — edition BRANDING lives exactly there, feature gating stays abolished and the name stays banned everywhere else. German locale by default, English as backup: the i18n bundle is split into `messages.properties` (English backup) and `messages_de.properties` (German, moved verbatim) — both carry the same sanctioned `system.title` key, so both are exempt here. */
    @Test
    fun `no main source outside the sanctioned edition-branding spots mentions Haflinger`() {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(dir, "kotlin-invoice/pom.xml").isFile) return@repeat
            dir = dir.parentFile ?: return@repeat
        }
        val mainRoot = File(dir, "kotlin-invoice/src/main")
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "properties" || it.extension == "sql") }
            .filter { it.name !in setOf("ApplicationIdentity.kt", "StatusPanel.kt", "messages.properties", "messages_de.properties") }
            .filter { it.readText().contains("Haflinger") }
            .map { it.relativeTo(mainRoot).path }
            .toList()
        assertFalse(offenders.isNotEmpty(), "Haflinger must not resurface in main sources, found: $offenders")
    }

    /** PO 2026-08-30 (abends) — the edition NAME is back as pure branding (System area shows
     *  "Edition: … Haflinger"); feature gating stays gone, displayName stays plain. */
    @org.junit.jupiter.api.Test
    fun `edition is exactly Haflinger`() {
        assertEquals("Haflinger", identity.edition)
    }

    // ── productLine() composition ──────────────────────────────────────────────────────

    @Test
    fun `productLine composes display name, edition and version, space-separated`() {
        assertEquals("Little Trotter Haflinger (9.9.9-test)", identity.productLine())
    }

    @Test
    fun `productLine changes with the configured version`() {
        val other = ApplicationIdentity(version = "2.0.0")
        assertEquals("Little Trotter Haflinger (2.0.0)", other.productLine())
    }
}
