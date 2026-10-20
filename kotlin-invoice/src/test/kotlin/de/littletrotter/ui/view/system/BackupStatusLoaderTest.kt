package de.littletrotter.ui.view.system

import java.nio.file.Files
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupStatusLoaderTest {

    private val full = """
        {"schema":1,"instance":"prod","run_id":"lt-1","started_at":"2026-10-12T03:00:00+02:00",
         "finished_at":"2026-10-12T03:40:00+02:00","result":"ok",
         "steps":{"inventory":{"result":"ok","duration_s":12},"backup":{"result":"ok","duration_s":900}},
         "inventory":{"result":"ok","findings":{"object_missing":0,"index_bloat":3},"db_checksum_failures":1},
         "last_success_at":"2026-10-12T03:40:00+02:00","last_deep_verify_at":"2026-10-12T03:35:00+02:00",
         "next_run_at":"2026-10-13T03:00:00+02:00","estimate_s":7200,"schedule_conflict":false,"shutdown_deferred":true}
    """.trimIndent()

    private fun req(s: BackupStatus?): BackupStatus = s ?: throw AssertionError("expected a parsed status")

    @Test
    fun `parse maps all contract fields and drops zero findings`() {
        val s = req(BackupStatusLoader.parse(full, alarmPresent = true))

        assertEquals(BackupResult.OK, s.result)
        assertEquals("2026-10-12T03:40+02:00", s.finishedAt.toString())
        assertEquals("2026-10-13T03:00+02:00", s.nextRunAt.toString())
        assertEquals("2026-10-12T03:35+02:00", s.lastDeepVerifyAt.toString())
        assertEquals(mapOf("index_bloat" to 3L), s.findings)
        assertEquals(1L, s.dbChecksumFailures)
        assertTrue(s.shutdownDeferred)
        assertFalse(s.scheduleConflict)
        assertTrue(s.alarmPresent)
        assertEquals(listOf("inventory", "backup"), s.steps.map { it.name })
    }

    @Test
    fun `parse tolerates missing optional fields and bad timestamps`() {
        val s = req(BackupStatusLoader.parse("""{"result":"error","next_run_at":"soon"}""", false))

        assertEquals(BackupResult.ERROR, s.result)
        assertNull(s.nextRunAt)
        assertTrue(s.findings.isEmpty())
    }

    @Test
    fun `parse returns null for invalid or foreign json`() {
        assertNull(BackupStatusLoader.parse("not-json", false))
        assertNull(BackupStatusLoader.parse("[]", false))
        assertNull(BackupStatusLoader.parse("""{"foo":1}""", false))
    }

    @Test
    fun `load returns null for blank dir, missing file and broken file`() {
        val dir = Files.createTempDirectory("backup-status")

        assertNull(BackupStatusLoader(Optional.empty()).load())
        assertNull(BackupStatusLoader(Optional.of(dir.toString())).load())
        Files.writeString(dir.resolve("status.json"), "{")
        assertNull(BackupStatusLoader(Optional.of(dir.toString())).load())
    }

    @Test
    fun `load reads status json and detects ALARM txt next to it`() {
        val dir = Files.createTempDirectory("backup-status")
        Files.writeString(dir.resolve("status.json"), full)

        assertFalse(req(BackupStatusLoader(Optional.of(dir.toString())).load()).alarmPresent)
        Files.writeString(dir.resolve("ALARM.txt"), "x")
        assertTrue(req(BackupStatusLoader(Optional.of(dir.toString())).load()).alarmPresent)
    }

    private val blocks = """
        {"result":"ok",
         "copies":{"schema":1,"state":"ok","banner":false,"stale_after_days":14,"workstations":[
           {"computer":"pc-anna","date":"2026-10-10T09:15","result":"ok","detail":"files 42","age_days":4},
           {"computer":"pc-bert","date":"2026-09-01T08:00","result":"error","detail":"","age_days":43}]},
         "usb":{"schema":1,"state":"ok","last_ok_at":"2026-10-12T03:50:00+0200","message":"","smart":"failed"}}
    """.trimIndent()

    @Test
    fun `parse maps copies and usb blocks including shell offsets`() {
        val s = req(BackupStatusLoader.parse(blocks, false))
        val copies = s.copies!!
        assertEquals("ok", copies.state)
        assertEquals(listOf("pc-anna", "pc-bert"), copies.workstations.map { it.computer })
        assertEquals(listOf(true, false), copies.workstations.map { it.ok })
        val usb = s.usb!!
        assertEquals("2026-10-12T03:50+02:00", usb.lastOkAt.toString())
        assertTrue(usb.smartFailed)
        assertTrue(usb.warning)
    }

    @Test
    fun `absent or malformed blocks yield null`() {
        val s = req(BackupStatusLoader.parse("""{"result":"ok","copies":"x","usb":[]}""", false))
        assertNull(s.copies)
        assertNull(s.usb)
        assertNull(req(BackupStatusLoader.parse("""{"result":"ok"}""", false)).copies)
    }

    @Test
    fun `usb without smart failure and absent state is neither warning nor in use`() {
        val usb = req(BackupStatusLoader.parse("""{"result":"ok","usb":{"state":"absent","smart":null}}""", false)).usb!!
        assertFalse(usb.warning)
        assertFalse(usb.inUse)
        assertTrue(UsbStatus("error", null, "x", null).warning)
    }

    @Test
    fun `banner rule uses producer flag or more than stale days since newest ok copy`() {
        val ws = listOf(WorkstationCopy("pc", java.time.LocalDateTime.parse("2026-10-01T10:00"), true, ""))
        val base = CopiesStatus("ok", false, 14, ws)
        assertFalse(base.bannerActive(java.time.LocalDateTime.parse("2026-10-15T10:00")))
        assertTrue(base.bannerActive(java.time.LocalDateTime.parse("2026-10-15T10:01").plusDays(1)))
        assertTrue(base.copy(banner = true).bannerActive(java.time.LocalDateTime.parse("2026-10-02T10:00")))
        assertFalse(CopiesStatus("none", false, 14, emptyList()).bannerActive(java.time.LocalDateTime.parse("2026-12-01T10:00")))
    }
}
