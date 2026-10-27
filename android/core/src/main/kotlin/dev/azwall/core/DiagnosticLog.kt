package dev.azwall.core

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What went wrong lately, kept on the device and shared only when the user exports it (Settings): no crash or
 * analytics service sees a private wallet's addresses, amounts or habits. Warnings and errors only, the last
 * [CAPACITY] lines, with what could identify the user cut down ([redact]). The same format on iOS.
 */
class DiagnosticLog(private val now: () -> Instant = Instant::now) {
    enum class Level { WARN, ERROR }

    private val lines = ArrayDeque<String>()
    private var file: File? = null

    /** Keeps the log in [file] and reads what an earlier launch left there. */
    @Synchronized
    fun attach(file: File) {
        this.file = file
        lines.clear()
        // None yet on a first launch.
        val previous = runCatching { file.readLines() }.getOrDefault(emptyList())
        lines.addAll(previous.filter { it.isNotBlank() }.takeLast(CAPACITY))
    }

    fun record(level: Level, scope: String, message: String) {
        val line = "${now().truncatedTo(ChronoUnit.SECONDS)} ${level.name} [$scope] ${redact(message)}"
        val (text, target) = synchronized(this) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
            lines.joinToString("\n") to file
        }
        // Losing a diagnostic line must never fail what was being done: the line stays in memory.
        target?.let { runCatching { it.writeText(text) } }
    }

    /** An error caught where the app carries on without it: kept for the export. */
    fun report(scope: String, error: Throwable) = record(Level.WARN, scope, error.message ?: error.toString())

    val entries: List<String> @Synchronized get() = lines.toList()

    /** The text the user shares: what app and device it comes from, then the lines, oldest first. */
    fun export(app: String, system: String): String {
        val header = listOf("Azwall diagnostic log", "App: $app", "System: $system", "Exported: ${now().truncatedTo(ChronoUnit.SECONDS)}", "")
        return (header + entries).joinToString("\n") + "\n"
    }

    @Synchronized
    fun clear() {
        lines.clear()
        // Already gone is as good.
        file?.delete()
    }

    companion object {
        const val CAPACITY = 500

        /** The app's log; [attach] makes it last across launches. */
        val shared = DiagnosticLog()

        private val URL = Regex("""(https?|wss?)://([^/\s"'?#]+)[^\s"']*""")
        private val HEX = Regex("""0x([0-9a-fA-F]{6})[0-9a-fA-F]{28,}([0-9a-fA-F]{4})""")

        /** Hex values (addresses, hashes, keys) cut to their ends, URLs to their host (an RPC URL may carry an API key). */
        fun redact(message: String): String =
            message.replace('\n', ' ').replace(URL, "$1://$2").replace(HEX, "0x$1…$2")
    }
}
