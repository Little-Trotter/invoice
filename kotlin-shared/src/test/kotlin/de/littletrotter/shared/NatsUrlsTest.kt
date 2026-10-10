package de.littletrotter.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * AP-M10 — kotlin-shared owns [normalizeNatsServers] (compiles to the top-level class
 * `NatsUrlsKt`); coverage measured on kotlin-invoice's own reuse of this function (see
 * kotlin-invoice's `NatsUrlsTest`) does not count towards *this* module's JaCoCo run — each
 * module's `check` goal only sees its own `target/jacoco.exec`. Full line+branch coverage of
 * the function therefore needs its own test here too.
 */
class NatsUrlsTest {

    @Test
    fun `null input falls back to localhost`() {
        assertThat(normalizeNatsServers(null)).isEqualTo("nats://localhost:4222")
    }

    @Test
    fun `blank input falls back to localhost`() {
        assertThat(normalizeNatsServers("   ")).isEqualTo("nats://localhost:4222")
    }

    @Test
    fun `adds scheme to a plain host-port value`() {
        assertThat(normalizeNatsServers("nats:4222")).isEqualTo("nats://nats:4222")
    }

    @Test
    fun `keeps an existing nats scheme`() {
        assertThat(normalizeNatsServers("nats://nats:4222")).isEqualTo("nats://nats:4222")
    }

    @Test
    fun `normalizes a comma-separated multi-server list and trims whitespace`() {
        assertThat(normalizeNatsServers("nats://nats:4222, localhost:14222"))
            .isEqualTo("nats://nats:4222,nats://localhost:14222")
    }

    @Test
    fun `also splits on semicolons`() {
        assertThat(normalizeNatsServers("nats:4222;localhost:14222"))
            .isEqualTo("nats://nats:4222,nats://localhost:14222")
    }

    @Test
    fun `drops a trailing path segment after the host-port`() {
        assertThat(normalizeNatsServers("nats://nats:4222/extra/path")).isEqualTo("nats://nats:4222")
    }

    @Test
    fun `blank entries between separators are dropped, not turned into an empty server`() {
        assertThat(normalizeNatsServers("nats:4222,,localhost:14222"))
            .isEqualTo("nats://nats:4222,nats://localhost:14222")
    }

    @Test
    fun `an entry that normalizes to an empty host-port falls back to localhost when it is the only one`() {
        // "://" is non-blank after trim (passes the first filter) but resolves to an empty
        // host-port (substringAfter("://") on itself yields ""), so the second filter drops it —
        // entries end up empty even though raw wasn't blank, hitting the entries.isEmpty() branch.
        assertThat(normalizeNatsServers("://")).isEqualTo("nats://localhost:4222")
    }
}
