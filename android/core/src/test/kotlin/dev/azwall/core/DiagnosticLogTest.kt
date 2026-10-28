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
        // Store-release audit: a key without 0x, or base64, is cut too; ordinary words are not.
        assertEquals("key ababab…abab", DiagnosticLog.redact("key " + "ab".repeat(32)))
        assertEquals("blob QUJDRE…", DiagnosticLog.redact("blob " + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0NTY3ODk="))
        assertEquals("the wallet engine stopped: the system ended it", DiagnosticLog.redact("the wallet engine stopped: the system ended it"))
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
    fun aLineSaidAgainAndAgainIsWrittenOnceEveryTenMinutes() {
        var at = Instant.EPOCH
        val log = DiagnosticLog { at }
        repeat(31) {
            log.record(DiagnosticLog.Level.WARN, "feeJuice", "the bridge could not be read")
            at = at.plusSeconds(20)
        }
        log.record(DiagnosticLog.Level.ERROR, "send", "proof failed")
        // Every 20 s for 10 minutes: the first, then the one 10 minutes later, which counts the 29 in between.
        assertEquals(
            listOf(
                "1970-01-01T00:00:00Z WARN [feeJuice] the bridge could not be read",
                "1970-01-01T00:10:00Z WARN [feeJuice] the bridge could not be read (also 29 more times before)",
                "1970-01-01T00:10:20Z ERROR [send] proof failed",
            ),
            log.entries,
        )
    }

    @Test
    fun theExportSaysWhereItComesFrom() {
        val log = DiagnosticLog { Instant.EPOCH }
        log.report("feeJuice", IllegalStateException("bridge deposits could not be read"))
        val text = log.export("Azwall 0.1.0 (1)", "Android 16")
        assertTrue(text.startsWith("Azwall diagnostic log\nApp: Azwall 0.1.0 (1)\nSystem: Android 16\n"))
        assertTrue(text.contains("WARN [feeJuice] bridge deposits could not be read"))
    }

    @Test
    fun aCancelledReadIsNotAnError() {
        val log = DiagnosticLog { Instant.EPOCH }
        log.report("sync", kotlinx.coroutines.CancellationException("the wallet locked"))
        assertTrue(log.entries.isEmpty())
    }
}
