package de.littletrotter.shared.edge

/**
 * The identity of the person on the other side of the trusted edge, as this component receives it.
 *
 * @property user the value of `X-Lt-User`, trimmed and validated — the human-readable name the
 *   invoicing application authenticated. Carried so a log line or a future audit record can name a
 *   person; **never** used as an authorization input on its own.
 * @property groups the entries of `X-Lt-Groups`, trimmed, in order, duplicates preserved as sent.
 *   Group *names*, not roles: the application's own role mapping ([EdgeRoleMapping]) decides which of them it
 *   recognises, and an unrecognised one is simply not a role here.
 */
data class EdgeIdentity(val user: String, val groups: List<String>)

/**
 * # The trust boundary of this component, in one file
 *
 * **A component behind the edge has no authentication of its own.**
 *
 * Authentication happens in the invoicing application. This component is encapsulated behind it and
 * **must never be reachable from outside the machine**: no published host port, no route to it that
 * does not pass through the reverse proxy, nothing on the public internet that reaches port 8080.
 * The proxy — "the trusted edge" — validates the invoicing application's session and then states the
 * result of that validation in two request headers, which this reader turns back into an identity:
 *
 * ```
 * X-Lt-User:   anna                     the authenticated user's name
 * X-Lt-Groups: invoicing,admin          their groups, comma-separated, the same names as before
 * ```
 *
 * ## Why this is safe here and catastrophic one `ports:` line away
 *
 * A header is not a credential. Anybody who can send this component an HTTP request can send it any
 * header they like, including `X-Lt-Groups: admin`. The **only** thing that makes trusting these
 * headers sound is that nobody but the edge can send this component a request at all, and the edge
 * strips both headers off every inbound request before setting its own. Those two obligations are
 * not implementation details of another repository that this component happens to rely on; they are
 * the premise of its entire authorization model:
 *
 *  - **publish a host port for this container and the model is gone.** Not weakened — gone. Anyone
 *    who can reach that port is `admin`, with no exploit and no credential, by typing one header.
 *  - **let the edge forward a client-supplied `X-Lt-User` and the model is gone** in the same way,
 *    from the internet, through the proxy.
 *
 * That is why this file, rather than a sentence in a README, is where the rule is written: a reader
 * who arrives here while adding a port mapping has to read it.
 *
 * ## What this component does about it anyway
 *
 * It cannot enforce its own unreachability, so it does the two things it can:
 *
 *  1. **Fail closed.** No `X-Lt-User` means no identity, and no identity means no role is held
 *    ([EdgeIdentityMechanism]). A misconfigured edge produces an application nobody can use,
 *    which is the failure mode worth having — the alternative, "no header means trust the caller",
 *    is the one that turns a misconfiguration into an open door.
 *  2. **Treat the headers as untrusted input.** They are validated and normalised here, not
 *    consumed as they arrive, and the raw values are never logged: a rejected header is a security
 *    event, and writing its unvalidated content into a log file is how a header becomes an
 *    injection into whatever reads that file. A rejection is logged with its reason and the header
 *    name, and with nothing the caller supplied.
 *
 * A surface that must work without an identity (for example a machine-facing API) is authorised by
 * whatever the application's own mechanism adds, never by these headers.
 */
object EdgeIdentityHeaders {

    /** The authenticated user's name. Required; its absence is what "fail closed" is about. */
    const val USER: String = "X-Lt-User"

    /**
     * The user's groups, comma-separated. Optional: a user in no group is a legitimate state (they
     * simply hold no permission here), and demanding the header for that case would only push an
     * empty string onto the edge. Absent and empty therefore mean the same thing — no groups.
     */
    const val GROUPS: String = "X-Lt-Groups"

    /**
     * Long enough for an email address as a username, short enough that the header cannot be used
     * to carry a payload.
     */
    const val MAX_USER_LENGTH: Int = 128

    /** Long enough for a person in every group an instance is likely to have. */
    const val MAX_GROUPS_LENGTH: Int = 1024

    /** A guard against a header built to make the split itself expensive. */
    const val MAX_GROUP_COUNT: Int = 32

    /** No group name a real directory produces is longer than this. */
    const val MAX_GROUP_LENGTH: Int = 64
}

/**
 * What [EdgeIdentityReader] made of the headers on one request. Three outcomes, not two, because
 * "the edge said nothing" and "the edge said something impossible" are different operational
 * events even though both end in the same refusal.
 */
sealed interface EdgeIdentityOutcome {

    /** A usable identity. It still holds no permission until the application's role mapping recognises its groups. */
    data class Accepted(val identity: EdgeIdentity) : EdgeIdentityOutcome

    /** No `X-Lt-User` at all: either the edge is not in front of us, or we are not behind it. */
    data object Absent : EdgeIdentityOutcome

    /**
     * The headers were there and were not something the edge produces.
     *
     * @property reason a fixed, self-authored sentence. It names *what* was wrong and never quotes
     *   the offending value — the value is attacker-controlled and this string reaches a log file.
     */
    data class Rejected(val reason: String) : EdgeIdentityOutcome
}

/**
 * Parses and validates the two trusted-edge headers. Pure, so the whole decision table is a unit
 * test and not something only a booted server can exercise.
 *
 * Validation is deliberately narrow rather than clever. Every rule below rejects the whole request
 * instead of repairing it, because a header the edge did not produce is not a header worth guessing
 * at — with one exception that is a decision rather than an oversight: a **well-formed group name
 * this component does not know** (`everyone`, `authentik Admins`) is not an error. It is the normal
 * case for a user who is in other groups too, and it simply grants nothing here.
 */
object EdgeIdentityReader {

    /**
     * @param userValues every value the request carried for `X-Lt-User`, in order.
     * @param groupValues every value the request carried for `X-Lt-Groups`, in order.
     *
     * Both are lists rather than single strings for the reason a single string would hide: **a
     * duplicated header is the shape a forgery takes.** An edge that sets its own header without
     * stripping the client's leaves two values on the request, and reading "the first" or "the
     * last" picks a winner between the proxy and the attacker. Neither is acceptable, so two values
     * is a refusal.
     */
    fun read(userValues: List<String>, groupValues: List<String>): EdgeIdentityOutcome =
        rejectDuplicateHeaders(userValues, groupValues) ?: readUserAndGroups(userValues, groupValues)

    /** Extracted from [read] only to stay under detekt's `ReturnCount` threshold. */
    private fun rejectDuplicateHeaders(userValues: List<String>, groupValues: List<String>): EdgeIdentityOutcome.Rejected? = when {
        userValues.size > 1 ->
            EdgeIdentityOutcome.Rejected(
                "${EdgeIdentityHeaders.USER} arrived ${userValues.size} times; the trusted edge sets it exactly once " +
                    "and strips any inbound copy, so more than one value means the request did not come from it",
            )
        groupValues.size > 1 ->
            EdgeIdentityOutcome.Rejected(
                "${EdgeIdentityHeaders.GROUPS} arrived ${groupValues.size} times; see ${EdgeIdentityHeaders.USER}",
            )
        else -> null
    }

    /** Extracted from [read] only to stay under detekt's `ReturnCount` threshold. */
    private fun readUserAndGroups(userValues: List<String>, groupValues: List<String>): EdgeIdentityOutcome {
        val rawUser = userValues.firstOrNull() ?: return EdgeIdentityOutcome.Absent
        val user = rawUser.trim()
        return validateUser(user) ?: acceptedOutcome(user, groupValues)
    }

    private fun validateUser(user: String): EdgeIdentityOutcome.Rejected? = when {
        user.isEmpty() -> EdgeIdentityOutcome.Rejected("${EdgeIdentityHeaders.USER} was present but blank")
        user.length > EdgeIdentityHeaders.MAX_USER_LENGTH ->
            EdgeIdentityOutcome.Rejected(
                "${EdgeIdentityHeaders.USER} was ${user.length} characters, the limit is ${EdgeIdentityHeaders.MAX_USER_LENGTH}",
            )
        !user.all(::isPrintableNonSpaceAscii) ->
            EdgeIdentityOutcome.Rejected(
                "${EdgeIdentityHeaders.USER} contained a character outside printable, non-space ASCII",
            )
        else -> null
    }

    private fun acceptedOutcome(user: String, groupValues: List<String>): EdgeIdentityOutcome {
        val raw = groupValues.firstOrNull().orEmpty()
        return groupsOf(raw)
            ?.let { EdgeIdentityOutcome.Accepted(EdgeIdentity(user, it)) }
            ?: EdgeIdentityOutcome.Rejected(rejectedGroupsReason(raw))
    }

    /** The parsed groups, or `null` when the header is not something the edge produces. */
    private fun groupsOf(raw: String): List<String>? {
        if (raw.length > EdgeIdentityHeaders.MAX_GROUPS_LENGTH) return null
        val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // A space is allowed inside a group name (real directories produce "authentik Admins"); a
        // control character is not, and neither is anything outside printable ASCII.
        val valid = entries.size <= EdgeIdentityHeaders.MAX_GROUP_COUNT &&
            entries.none { it.length > EdgeIdentityHeaders.MAX_GROUP_LENGTH } &&
            entries.all { entry -> entry.all(::isPrintableAscii) }
        return entries.takeIf { valid }
    }

    private fun rejectedGroupsReason(raw: String): String = when {
        raw.length > EdgeIdentityHeaders.MAX_GROUPS_LENGTH ->
            "${EdgeIdentityHeaders.GROUPS} was ${raw.length} characters, the limit is ${EdgeIdentityHeaders.MAX_GROUPS_LENGTH}"
        raw.split(',').count { it.isNotBlank() } > EdgeIdentityHeaders.MAX_GROUP_COUNT ->
            "${EdgeIdentityHeaders.GROUPS} named more than ${EdgeIdentityHeaders.MAX_GROUP_COUNT} groups"
        else ->
            "${EdgeIdentityHeaders.GROUPS} contained an entry that is too long or carries a character " +
                "outside printable ASCII"
    }

    private fun isPrintableAscii(char: Char): Boolean = char.code in PRINTABLE_ASCII_RANGE

    private fun isPrintableNonSpaceAscii(char: Char): Boolean = char.code in PRINTABLE_ASCII_RANGE && char != ' '

    private val PRINTABLE_ASCII_RANGE = 0x20..0x7E
}
