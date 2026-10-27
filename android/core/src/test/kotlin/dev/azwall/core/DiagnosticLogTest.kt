package dev.azwall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class DiagnosticLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun whatCouldIdentifyTheUserIsCutDown() {
        val key = "0x" + "ab".repeat(32)
        assertEquals("sent from 0xababab…abab", DiagnosticLog.redact("sent from $key"))
        assertEquals("node https://lb.drpc.org is down", DiagnosticLog.redact("node https://lb.drpc.org/aztec/SECRETKEY?x=1 is down"))
        assertEquals("block 0x1f 12", DiagnosticLog.redact("block 0x1f 12"))
        assertEquals("two lines", DiagnosticLog.redact("two\nlines"))
    }

    @Test
    fun onlyTheLastLinesAreKeptAndTheyLastAcrossLaunches() {
        val file = tmp.root.resolve("diagnostics.log")
        val log = DiagnosticLog { Instant.EPOCH }.apply { attach(file) }
        repeat(DiagnosticLog.CAPACITY + 5) { log.record(DiagnosticLog.Level.WARN, "test", "line $it") }
        assertEquals(DiagnosticLog.CAPACITY, log.entries.size)
        assertEquals("1970-01-01T00:00:00Z WARN [test] line 5", log.entries.first())
        val next = DiagnosticLog().apply { attach(file) }
        assertEquals(log.entries, next.entries)
        next.clear()
        assertTrue(next.entries.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun theExportSaysWhereItComesFrom() {
        val log = DiagnosticLog { Instant.EPOCH }
        log.report("feeJuice", IllegalStateException("bridge deposits could not be read"))
        val text = log.export("Azwall 0.1.0 (1)", "Android 16")
        assertTrue(text.startsWith("Azwall diagnostic log\nApp: Azwall 0.1.0 (1)\nSystem: Android 16\n"))
        assertTrue(text.contains("WARN [feeJuice] bridge deposits could not be read"))
    }
}
