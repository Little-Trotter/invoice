package de.littletrotter.application.settings

import de.littletrotter.domain.repository.SystemSettingRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Reads the output-intent setting rows ([KEY], or `[KEY].<suffix>`) for the export job.
 *
 * **Fail-open to `null`.** An unreadable setting must never block an export; a blank value counts
 * as absent. There is deliberately no setter and no cache: every export reads the current state.
 */
@ApplicationScoped
class OutputIntentSettingGate @Inject constructor(
    private val repository: SystemSettingRepository,
) {

    /** The trimmed values joined by a space, or `null` when there is none or the rows cannot be read. */
    fun currentToken(): String? =
        runCatching { repository.findFamily(KEY) }.getOrNull()
            ?.map { it.value.trim() }?.filter { it.isNotBlank() }
            ?.joinToString(" ")?.takeIf { it.isNotBlank() }

    companion object {
        const val KEY = "xpdf.output-intent"
    }
}
