package de.littletrotter.ui.view.system

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.util.Optional
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Copy-freshness window in days when status.json omits `stale_after_days`. */
private const val DEFAULT_STALE_AFTER_DAYS = 14L

/** Outcome of a backup run or step; `UNKNOWN` covers absent or unrecognized values. */
enum class BackupResult {
    OK,
    WARNING,
    ERROR,
    SKIPPED,
    UNKNOWN;

    companion object {
        fun parse(value: String?): BackupResult =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** One backup step (`inventory`, `backup`, `verify`, `pickup`, `maintenance`) as reported in status.json. */
data class BackupStep(val name: String, val result: BackupResult, val durationSeconds: Long)

/** Read-only projection of the backup orchestrator's `status.json` (HEUBODEN contract 4). */
data class BackupStatus(
    val result: BackupResult,
    val startedAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
    val inventoryResult: BackupResult,
    /** Finding class to count, only classes with a count above zero. */
    val findings: Map<String, Long>,
    val dbChecksumFailures: Long,
    val lastSuccessAt: OffsetDateTime?,
    val lastDeepVerifyAt: OffsetDateTime?,
    val nextRunAt: OffsetDateTime?,
    val scheduleConflict: Boolean,
    val shutdownDeferred: Boolean,
    val steps: List<BackupStep>,
    /** True when `ALARM.txt` exists next to status.json. */
    val alarmPresent: Boolean,
    /** Workstation copy feedback (`copies` block); `null` when the block is absent. */
    val copies: CopiesStatus? = null,
    /** USB-SSD copy (`usb` block); `null` when absent or not in use. */
    val usb: UsbStatus? = null,
)

/** One workstation's last copy feedback; `date` is local wall-clock time without offset. */
data class WorkstationCopy(
    val computer: String,
    val date: java.time.LocalDateTime?,
    val ok: Boolean,
    val detail: String,
)

/** `copies` block of status.json (HEUBODEN B25); `state` is none|ok|stale|error, `banner` set by the producer. */
data class CopiesStatus(
    val state: String,
    val banner: Boolean,
    val staleAfterDays: Long,
    val workstations: List<WorkstationCopy>,
) {
    /**
     * Banner rule: the producer's flag, or no successful copy within [staleAfterDays] of [now]
     * (re-evaluated here because status.json can be older than the page view).
     */
    fun bannerActive(now: java.time.LocalDateTime): Boolean {
        if (banner) return true
        val newestOk = workstations.filter { it.ok }.mapNotNull { it.date }.maxOrNull() ?: return false
        return java.time.Duration.between(newestOk, now).toDays() > staleAfterDays
    }
}

/** `usb` block of status.json (HEUBODEN B27); `state` is absent|running|ok|error, `smart` is ok|failed|null. */
data class UsbStatus(val state: String, val lastOkAt: OffsetDateTime?, val message: String, val smart: String?) {
    val smartFailed: Boolean get() = smart.equals("failed", ignoreCase = true)

    /** Visible warning: SMART failure or a failed copy. */
    val warning: Boolean get() = smartFailed || state == "error"

    /** Not in use: nothing to show. */
    val inUse: Boolean get() = state != "absent" || lastOkAt != null
}

/**
 * Reads `<base-dir>/status.json` (written only by the backup orchestrator). The application never
 * writes to the backup directory; the compose mount is read-only. Any problem (unset directory,
 * missing or unreadable file, invalid JSON) yields `null`, which the System tab renders as
 * "keine Daten" -- never an error.
 */
@ApplicationScoped
class BackupStatusLoader @Inject constructor(
    @ConfigProperty(name = "littletrotter.backup.base-dir")
    private val baseDir: Optional<String>,
) {
    fun load(): BackupStatus? {
        val configured = baseDir.orElse("")
        if (configured.isBlank()) return null
        return try {
            val dir = Paths.get(configured)
            val file = dir.resolve(STATUS_FILE)
            if (!Files.isRegularFile(file)) return null
            parse(Files.readString(file), Files.exists(dir.resolve(ALARM_FILE)))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val STATUS_FILE = "status.json"
        const val ALARM_FILE = "ALARM.txt"
        private const val RESULT_KEY = "result"
        private val mapper = ObjectMapper()

        /** Returns `null` for anything that is not a status document with at least a `result`. */
        fun parse(json: String, alarmPresent: Boolean): BackupStatus? = try {
            val root = mapper.readTree(json)
            val result = root?.takeIf { it.isObject }?.text(RESULT_KEY)
            if (result == null) {
                null
            } else {
                val inventory = root.get("inventory")
                BackupStatus(
                    result = BackupResult.parse(result),
                    startedAt = root.time("started_at"),
                    finishedAt = root.time("finished_at"),
                    inventoryResult = BackupResult.parse(inventory?.text(RESULT_KEY)),
                    findings = findings(inventory?.get("findings")),
                    dbChecksumFailures = inventory?.get("db_checksum_failures")?.asLong(0) ?: 0,
                    lastSuccessAt = root.time("last_success_at"),
                    lastDeepVerifyAt = root.time("last_deep_verify_at"),
                    nextRunAt = root.time("next_run_at"),
                    scheduleConflict = root.get("schedule_conflict")?.asBoolean(false) ?: false,
                    shutdownDeferred = root.get("shutdown_deferred")?.asBoolean(false) ?: false,
                    steps = steps(root.get("steps")),
                    alarmPresent = alarmPresent,
                    copies = copies(root.get("copies")),
                    usb = usb(root.get("usb")),
                )
            }
        } catch (_: Exception) {
            null
        }

        private fun JsonNode.text(field: String): String? =
            get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

        // Shell `date +%z` writes offsets without a colon (+0200); ISO parsing alone would reject them.
        private val shellOffsetTime = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")

        private fun JsonNode.time(field: String): OffsetDateTime? = text(field)?.let { raw ->
            runCatching { OffsetDateTime.parse(raw) }.recoverCatching { OffsetDateTime.parse(raw, shellOffsetTime) }.getOrNull()
        }

        private fun copies(node: JsonNode?): CopiesStatus? {
            if (node == null || !node.isObject) return null
            val workstations = node.get("workstations")?.takeIf { it.isArray }?.mapNotNull { w ->
                val computer = w.text("computer") ?: return@mapNotNull null
                WorkstationCopy(
                    computer = computer,
                    date = w.text("date")?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() },
                    ok = w.text(RESULT_KEY) == "ok",
                    detail = w.text("detail").orEmpty(),
                )
            }.orEmpty()
            return CopiesStatus(
                state = node.text("state") ?: "none",
                banner = node.get("banner")?.asBoolean(false) ?: false,
                staleAfterDays = node.get("stale_after_days")?.asLong(DEFAULT_STALE_AFTER_DAYS) ?: DEFAULT_STALE_AFTER_DAYS,
                workstations = workstations,
            )
        }

        private fun usb(node: JsonNode?): UsbStatus? =
            if (node == null || !node.isObject) {
                null
            } else {
                UsbStatus(
                    state = node.text("state") ?: "absent",
                    lastOkAt = node.time("last_ok_at"),
                    message = node.text("message").orEmpty(),
                    smart = node.text("smart"),
                )
            }

        private fun findings(node: JsonNode?): Map<String, Long> =
            if (node == null || !node.isObject) {
                emptyMap()
            } else {
                node.properties().associate { it.key to it.value.asLong(0) }.filterValues { it > 0 }
            }

        private fun steps(node: JsonNode?): List<BackupStep> =
            if (node == null || !node.isObject) {
                emptyList()
            } else {
                node.properties().map { (name, step) ->
                    BackupStep(name, BackupResult.parse(step.text(RESULT_KEY)), step.get("duration_s")?.asLong(0) ?: 0)
                }
            }
    }
}
