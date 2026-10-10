package de.littletrotter.infrastructure.persistence

import java.sql.Connection

/**
 * Applies the baseline DDL directly via JDBC, for tests that stand up a Testcontainers Postgres
 * without going through Flyway/Quarkus. Loads the same file Flyway would apply — kept in one
 * place instead of duplicated per test: the former single-file version of this loader was
 * copy-pasted into 14 IT classes, each hardcoding the migration file name, so every re-cut of the
 * DDL would otherwise have required 14 separate edits. squashed the grown DDL history
 * (former V001-V014) into one `V1__baseline_schema.sql`, auth bootstrap included — its role grant
 * is itself a no-op when no `authentik_user` role exists, which is always true in these tests'
 * throwaway containers, so no exclusion is needed anymore.
 *
 * Statement splitting is lexical, not a naive `split(";")`: the baseline carries every
 * `COMMENT ON …` text of the schema, and those German sentences are full of semicolons inside
 * string literals ("… zur Auswahl; Altzeilen bleiben …"). A naive split cut them in half and every
 * IT using this loader failed with "Unterminated string literal". [splitStatements] therefore
 * skips over single-quoted literals (including `''` escapes), `--` line comments and
 * dollar-quoted blocks, and only treats a `;` outside all of them as a statement boundary. That
 * also means the SQL files stay free to be written for Postgres, not for this loader.
 */
object TestSchema {

    private val ddlFiles = listOf(
        "V1__baseline_schema.sql",
    )

    fun apply(conn: Connection) {
        ddlFiles.forEach { name ->
            val stream = TestSchema::class.java.classLoader.getResourceAsStream("db/migration/ddl/$name")
                ?: error("Missing migration resource: db/migration/ddl/$name")
            splitStatements(stream.bufferedReader().readText())
                .forEach { conn.createStatement().execute(it) }
        }
    }

    /**
     * `payment_term` no longer has a plain `name`/`english_name` column: `name_group_id`
     * points at a `localized_text_group` row instead (`PaymentTerm.nameGroup`,
     * `V1__baseline_schema.sql`). Every raw-JDBC IT that used to
     * `INSERT INTO payment_term (name, due_in_days, created_at) VALUES (...)` directly now goes
     * through this helper instead of repeating the group-then-text-then-row sequence six times.
     * `language.id` 1 ('de', the fallback) / 2 ('en') are schema rows from [apply]'s own DDL file,
     * not seed data, so they exist right after [apply] runs — see that file's own comment on why.
     *
     * Returns the new `payment_term.id`.
     */
    fun insertPaymentTerm(
        conn: Connection,
        germanName: String,
        dueInDays: Int = 14,
        englishName: String? = null,
        description: String? = null,
        discountPercent: java.math.BigDecimal = java.math.BigDecimal.ZERO,
        discountDays: Int? = null,
        isDefault: Boolean = false,
    ): Long {
        val groupId = conn.prepareStatement("INSERT INTO localized_text_group (purpose) VALUES (1) RETURNING id").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        conn.prepareStatement("INSERT INTO localized_text (group_id, language_id, value) VALUES (?, 1, ?)").use { ps ->
            ps.setLong(1, groupId)
            ps.setString(2, germanName)
            ps.executeUpdate()
        }
        englishName?.let { text ->
            conn.prepareStatement("INSERT INTO localized_text (group_id, language_id, value) VALUES (?, 2, ?)").use { ps ->
                ps.setLong(1, groupId)
                ps.setString(2, text)
                ps.executeUpdate()
            }
        }
        val insertSql = """
            INSERT INTO payment_term (name_group_id, description, due_in_days, discount_percent, discount_days, is_default, created_at)
            VALUES (?, ?, ?, ?, ?, ?, now()) RETURNING id
        """.trimIndent()
        return conn.prepareStatement(insertSql).use { ps ->
            ps.setLong(1, groupId)
            ps.setString(2, description)
            ps.setInt(3, dueInDays)
            ps.setBigDecimal(4, discountPercent)
            if (discountDays != null) ps.setInt(5, discountDays) else ps.setNull(5, java.sql.Types.INTEGER)
            ps.setBoolean(6, isDefault)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** The German (fallback) name of the payment term [id] — the counterpart read helper to
     *  [insertPaymentTerm], for the rare IT that needs to assert on it (`PaymentTermIT`). */
    fun germanPaymentTermName(conn: Connection, id: Long): String? =
        conn.prepareStatement(
            "SELECT lt.value FROM payment_term p JOIN localized_text lt ON lt.group_id = p.name_group_id AND lt.language_id = 1 WHERE p.id = ?",
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /** Updates the German (fallback) name of the payment term [id] in place — the counterpart
     *  write helper to [germanPaymentTermName]. */
    fun updateGermanPaymentTermName(conn: Connection, id: Long, newName: String) {
        conn.prepareStatement(
            "UPDATE localized_text SET value = ? WHERE language_id = 1 AND group_id = (SELECT name_group_id FROM payment_term WHERE id = ?)",
        ).use { ps ->
            ps.setString(1, newName)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
    }

    /** Splits a migration file into executable statements at top-level `;` boundaries. */
    internal fun splitStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    val end = sql.indexOf('\n', i).takeIf { it >= 0 } ?: sql.length
                    current.append(sql, i, end)
                    i = end
                }
                c == '\'' -> {
                    val end = endOfQuotedLiteral(sql, i)
                    current.append(sql, i, end)
                    i = end
                }
                c == '$' -> {
                    val tag = dollarQuoteTag(sql, i)
                    if (tag == null) {
                        current.append(c)
                        i++
                    } else {
                        val close = sql.indexOf(tag, i + tag.length)
                        val end = if (close < 0) sql.length else close + tag.length
                        current.append(sql, i, end)
                        i = end
                    }
                }
                c == ';' -> {
                    statements.addStatement(current)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        statements.addStatement(current)
        return statements
    }

    /** Index just past the closing quote of the literal that starts at [start]. */
    private fun endOfQuotedLiteral(sql: String, start: Int): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == '\'') {
                // '' is an escaped quote, not the end of the literal.
                if (i + 1 < sql.length && sql[i + 1] == '\'') i += 2 else return i + 1
            } else {
                i++
            }
        }
        return sql.length
    }

    /** The `$…$` opening tag at [start], or null if that `$` does not open a dollar-quoted block. */
    private fun dollarQuoteTag(sql: String, start: Int): String? {
        var i = start + 1
        while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
        return if (i < sql.length && sql[i] == '$') sql.substring(start, i + 1) else null
    }

    /**
     * Appends [current] as a statement if it holds anything but whitespace and comments, then
     * clears it. Trailing comment lines behind the last `;` of a file would otherwise be sent to
     * the server as an empty statement.
     */
    private fun MutableList<String>.addStatement(current: StringBuilder) {
        val statement = current.toString().trim()
        current.setLength(0)
        val hasCode = statement.lineSequence().any { it.isNotBlank() && !it.trimStart().startsWith("--") }
        if (hasCode) add(statement)
    }
}
