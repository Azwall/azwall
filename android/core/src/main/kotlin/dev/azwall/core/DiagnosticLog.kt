package dev.azwall.core

import java.io.File
import java.time.Duration
import java.util.concurrent.CancellationException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What went wrong lately, kept on the device and shared only when the user exports it (Settings): no crash or
 * analytics service sees a private wallet's addresses, amounts or habits. Warnings and errors only, the last
 * [CAPACITY] lines, with what could identify the user cut down ([redact]). A line said again within [REPEAT_WINDOW]
 * (a node down fails every refresh) is counted, not written: it would push out the lines that tell what happened.
 * The same format on iOS.
 */
class DiagnosticLog(private val now: () -> Instant = Instant::now) {
    enum class Level { WARN, ERROR }

    private val lines = ArrayDeque<String>()
    private var file: File? = null
    /** Each line recently written (without its time): when, and how often it was said again since. */
    private val recent = HashMap<String, Pair<Instant, Int>>()

    /** Keeps the log in [file] and reads what an earlier launch left there. */
    @Synchronized
    fun attach(file: File) {
        this.file = file
        lines.clear()
        // None yet on a first launch.
        val previous = runCatching { file.readLines() }.getOrDefault(emptyList())
        lines.addAll(previous.filter { it.isNotBlank() }.takeLast(CAPACITY))
    }

    /** Written under the lock: two lines recorded at once must not land on disk in the wrong order. */
    @Synchronized
    fun record(level: Level, scope: String, message: String) {
        val at = now()
        val said = "${level.name} [$scope] ${redact(message)}"
        val seen = recent[said]
        if (seen != null && Duration.between(seen.first, at) < REPEAT_WINDOW) {
            recent[said] = seen.first to seen.second + 1
            return
        }
        if (recent.size >= CAPACITY) recent.clear()
        recent[said] = at to 0
        val again = seen?.second?.takeIf { it > 0 }?.let { " (also $it more times before)" } ?: ""
        lines.addLast("${at.truncatedTo(ChronoUnit.SECONDS)} $said$again")
        while (lines.size > CAPACITY) lines.removeFirst()
        // Losing a diagnostic line must never fail what was being done: the line stays in memory.
        file?.let { f -> runCatching { f.writeText(lines.joinToString("\n")) } }
    }

    /**
     * An error caught where the app carries on without it: kept for the export. A cancellation is not one: it is
     * how a read ends when the wallet locks or switches network, and every catch-all around a suspend call sees it.
     */
    fun report(scope: String, error: Throwable) {
        if (error is CancellationException) return
        record(Level.WARN, scope, error.message ?: error.toString())
    }

    val entries: List<String> @Synchronized get() = lines.toList()

    /** The text the user shares: what app and device it comes from, then the lines, oldest first. */
    fun export(app: String, system: String): String {
        val header = listOf("Azwall diagnostic log", "App: $app", "System: $system", "Exported: ${now().truncatedTo(ChronoUnit.SECONDS)}", "")
        return (header + entries).joinToString("\n") + "\n"
    }

    @Synchronized
    fun clear() {
        lines.clear()
        recent.clear()
        // Already gone is as good.
        file?.delete()
    }

    companion object {
        const val CAPACITY = 500

        /** How long a line said again is only counted. */
        val REPEAT_WINDOW: Duration = Duration.ofMinutes(10)

        /** The app's log; [attach] makes it last across launches. */
        val shared = DiagnosticLog()

        private val URL = Regex("""(https?|wss?)://([^/\s"'?#]+)[^\s"']*""")
        private val HEX = Regex("""0x([0-9a-fA-F]{6})[0-9a-fA-F]{28,}([0-9a-fA-F]{4})""")
        // A key or hash written without 0x, and long base64 (a sealed blob, a proof): never words, cut the same way.
        private val BARE_HEX = Regex("""\b([0-9a-fA-F]{6})[0-9a-fA-F]{28,}([0-9a-fA-F]{4})\b""")
        private val BASE64 = Regex("""([A-Za-z0-9+/]{6})[A-Za-z0-9+/]{34,}={0,2}""")

        /** Hex values (addresses, hashes, keys) cut to their ends, URLs to their host (an RPC URL may carry an API key). */
        fun redact(message: String): String =
            message.replace('\n', ' ').replace(URL, "$1://$2").replace(HEX, "0x$1…$2").replace(BARE_HEX, "$1…$2").replace(BASE64, "$1…")
    }
}
