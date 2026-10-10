package de.littletrotter.application.shared

import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.domain.compliance.InvoiceAuditAction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager

/**
 * Journals a block of writes under a more specific business action than the outermost
 * [SetsAuditGuc] one, and attaches structured context (reason, source, hashes) that the capture
 * trigger stores under the `_context` key of the journal detail.
 *
 * A document recovery runs inside a transaction that was opened for another purpose (serving a
 * download, sending a mail); its row changes must still be attributed to the recovery. The
 * interceptor's outermost-wins rule is deliberately left alone: this override is explicit,
 * scoped to one block, and restores the previous values afterwards so later writes of the same
 * transaction keep the outer action and carry no context.
 *
 * Must be called inside an active transaction: `set_config(..., true)` is transaction-local and
 * has no effect (and no later reset) outside one.
 */
@ApplicationScoped
class AuditActionOverride @Inject constructor(
    private val em: EntityManager,
    private val objectMapper: ObjectMapper,
) {

    fun <T> withAction(action: InvoiceAuditAction, context: Map<String, Any?>, block: () -> T): T {
        val previousAction = currentSetting(ACTION_GUC)
        val previousContext = currentSetting(CONTEXT_GUC)
        setGuc(ACTION_GUC, action.ordinal.toString())
        setGuc(CONTEXT_GUC, objectMapper.writeValueAsString(context.filterValues { it != null }))
        try {
            val result = block()
            // The capture trigger fires when the row change reaches the database; flushing here
            // keeps that moment inside the override instead of at the transaction's end.
            em.flush()
            return result
        } finally {
            setGuc(ACTION_GUC, previousAction)
            setGuc(CONTEXT_GUC, previousContext)
        }
    }

    private fun currentSetting(name: String): String =
        (em.createNativeQuery(READ_QUERY).setParameter("name", name).singleResult as String?).orEmpty()

    private fun setGuc(name: String, value: String) {
        em.createNativeQuery(SET_QUERY)
            .setParameter("name", name)
            .setParameter("value", value)
            .singleResult
    }

    companion object {
        const val ACTION_GUC = "app.audit_action"
        const val CONTEXT_GUC = "app.audit_context"
        private const val READ_QUERY = "SELECT current_setting(:name, true)"
        private const val SET_QUERY = "SELECT set_config(:name, :value, true)"
    }
}
