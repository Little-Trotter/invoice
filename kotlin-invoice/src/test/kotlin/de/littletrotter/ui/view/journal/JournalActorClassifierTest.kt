package de.littletrotter.ui.view.journal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [JournalActorClassifier] cannot tell a real OIDC `sub` apart from a Postgres role name by
 *  string shape alone — both are just opaque strings (see the classifier's own KDoc on why it
 *  doesn't try to, and why it only ever needs to: action 0/UNSPECIFIED and a real OIDC sub never
 *  co-occur in practice, since any write an app method annotated already set both GUCs together). */
class JournalActorClassifierTest {

    @Test
    fun `the literal system fallback is not a database role`() {
        assertFalse(JournalActorClassifier.isDatabaseRole("system"))
    }

    @Test
    fun `a connecting Postgres role name is a database role`() {
        assertTrue(JournalActorClassifier.isDatabaseRole("lt_owner"))
        assertTrue(JournalActorClassifier.isDatabaseRole("postgres"))
    }
}
