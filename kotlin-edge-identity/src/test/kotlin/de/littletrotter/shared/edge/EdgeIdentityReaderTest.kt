package de.littletrotter.shared.edge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **PO decision 2026-09-07 — the identity arrives as headers from the trusted edge, and this is the
 * table of everything this component will and will not believe.**
 *
 * The header parser is where "treat them as untrusted input" is either true or a slogan, so every
 * cell of it is asserted here rather than inferred from an end-to-end scenario: a booted server
 * proves that a *valid* header works and that a *missing* one is refused, but it cannot economically
 * enumerate the malformed shapes, and the malformed shapes are the interesting ones.
 *
 * Three groups of assertions, in the order the risk runs:
 *
 *  1. **absent is refused** — the whole model is fail-closed or it is nothing;
 *  2. **duplicated is refused** — a second value is what a forged header looks like when the edge
 *     forgot to strip the client's copy, and picking a winner between the proxy and the attacker is
 *     not a decision anybody should be making;
 *  3. **malformed is refused, unknown is not** — a group name this component does not recognise is
 *     the normal case for a real directory and must stay harmless, while a value that is not
 *     something the edge produces must never be repaired into one.
 */
class EdgeIdentityReaderTest {

    private fun read(user: List<String>, groups: List<String> = emptyList()) = EdgeIdentityReader.read(user, groups)

    private fun accepted(user: List<String>, groups: List<String> = emptyList()): EdgeIdentity =
        assertIs<EdgeIdentityOutcome.Accepted>(read(user, groups)).identity

    private fun rejected(user: List<String>, groups: List<String> = emptyList()): String =
        assertIs<EdgeIdentityOutcome.Rejected>(read(user, groups)).reason

    // ── 1: absent ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no user header at all is Absent — the fail-closed case, not an error`() {
        assertEquals(EdgeIdentityOutcome.Absent, read(user = emptyList()))
        // Groups without a user is still Absent: the edge states both or neither, and a groups-only
        // request is one that never went through it.
        assertEquals(EdgeIdentityOutcome.Absent, read(user = emptyList(), groups = listOf("admin")))
    }

    @Test
    fun `a present but blank or whitespace-only user header is refused, not treated as absent`() {
        assertTrue(rejected(listOf("")).contains(EdgeIdentityHeaders.USER))
        assertTrue(rejected(listOf("   ")).contains("blank"))
    }

    // ── 2: duplicated ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a user header that arrived twice is refused — that is what a forgery looks like`() {
        val reason = rejected(listOf("anna", "mallory"))

        assertTrue(reason.contains("arrived 2 times"), reason)
        assertTrue(reason.contains("strips any inbound copy"), reason)
        // And the refusal names neither value: both are attacker-controlled and this string is logged.
        assertTrue(!reason.contains("anna") && !reason.contains("mallory"), reason)
    }

    @Test
    fun `a groups header that arrived twice is refused for the same reason`() {
        val reason = rejected(listOf("anna"), listOf("invoicing", "admin"))

        assertTrue(reason.contains(EdgeIdentityHeaders.GROUPS), reason)
        assertTrue(!reason.contains("invoicing"), reason)
    }

    // ── 3: the accepted shapes ──────────────────────────────────────────────────────────────────

    @Test
    fun `a well-formed pair is accepted, trimmed, and its groups keep their order`() {
        val identity = accepted(listOf(" anna "), listOf(" invoicing , admin "))

        assertEquals("anna", identity.user)
        assertEquals(listOf("invoicing", "admin"), identity.groups)
    }

    @Test
    fun `no groups header, an empty one and a comma-only one all mean the same thing — no groups`() {
        listOf(emptyList(), listOf(""), listOf("   "), listOf(",,")).forEach { groups ->
            assertEquals(emptyList<String>(), accepted(listOf("anna"), groups).groups, "groups=$groups")
        }
    }

    @Test
    fun `an email address is a valid user name — a username is not always a short word`() {
        assertEquals("anna.muster@example.invalid", accepted(listOf("anna.muster@example.invalid")).user)
    }

    @Test
    fun `a group name with a space is accepted, because real directories produce them`() {
        // "authentik Admins" is a group every Authentik ships with. It grants nothing here, but it
        // must not make the whole request unusable for a person who happens to be in it.
        val identity = accepted(listOf("olaf"), listOf("authentik Admins,admin"))

        assertEquals(listOf("authentik Admins", "admin"), identity.groups)
    }

    // ── 3: the refused shapes ───────────────────────────────────────────────────────────────────

    @Test
    fun `a user name carrying inner whitespace or a control character is refused`() {
        val forgedUserNames = listOf(
            "an na",
            "anna" + NEWLINE + "X-Lt-Groups: admin",
            "anna" + TAB + "muster",
            "anna" + NUL,
        )

        forgedUserNames.forEach { forged ->
            assertTrue(rejected(listOf(forged)).contains("printable"), "must be refused: ${forged.map(Char::code)}")
        }
    }

    @Test
    fun `an oversized user name is refused rather than truncated`() {
        val reason = rejected(listOf("a".repeat(EdgeIdentityHeaders.MAX_USER_LENGTH + 1)))

        assertTrue(reason.contains("the limit is ${EdgeIdentityHeaders.MAX_USER_LENGTH}"), reason)
    }

    @Test
    fun `an oversized groups header, too many groups, or an oversized group name are all refused`() {
        assertTrue(
            rejected(listOf("anna"), listOf("g".repeat(EdgeIdentityHeaders.MAX_GROUPS_LENGTH + 1)))
                .contains("the limit is ${EdgeIdentityHeaders.MAX_GROUPS_LENGTH}"),
        )
        assertTrue(
            rejected(listOf("anna"), listOf((1..EdgeIdentityHeaders.MAX_GROUP_COUNT + 1).joinToString(",") { "g$it" }))
                .contains("more than ${EdgeIdentityHeaders.MAX_GROUP_COUNT}"),
        )
        assertTrue(
            rejected(listOf("anna"), listOf("admin," + "g".repeat(EdgeIdentityHeaders.MAX_GROUP_LENGTH + 1)))
                .contains("too long"),
        )
    }

    @Test
    fun `a group entry carrying a control character is refused — the whole header, not just that entry`() {
        // Dropping the bad entry and keeping the rest would be repairing a header the edge did not
        // produce, which is exactly the guessing this boundary must not do.
        val reason = rejected(listOf("anna"), listOf("invoicing,ad" + NUL + "min"))

        assertTrue(reason.contains("printable ASCII"), reason)
    }

    @Test
    fun `a refusal never quotes what the caller sent`() {
        val secret = "payload-that-must-not-reach-a-log"

        listOf(
            rejected(listOf(secret + "a".repeat(EdgeIdentityHeaders.MAX_USER_LENGTH))),
            rejected(listOf("anna"), listOf(secret.repeat(3) + ",admin")),
            rejected(listOf("anna", secret)),
        ).forEach { reason -> assertTrue(!reason.contains("payload"), "a reason leaked the caller's value: $reason") }
    }

    private companion object {
        // Written as escapes rather than typed: a raw control character in a source file is a
        // hazard to every tool that reads it, and these are exactly the bytes under test.
        const val NUL: Char = '\u0000'
        const val TAB: Char = '\t'
        const val NEWLINE: Char = '\n'
    }
}
