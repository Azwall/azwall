package dev.azwall.core.wallet

/** Error text for people, the same on both apps. */
object Errors {
    /**
     * A `method: ` prefix the PXE bridge adds to a failed call's error, or the `detail=` the Rust core's errors carry
     * (the generated bindings' message is their field list).
     */
    private val PREFIX = Regex("^(?:[A-Za-z][A-Za-z0-9]*: |detail=)")

    /**
     * What the PXE's web view says when a request never got an answer (Chromium, WebKit, Firefox): the connection
     * dropped, often because the app went to the background while proving. It can drop after the node received the
     * transaction: the outcome is unknown, and a retry could send it twice.
     */
    private val LOST_CONNECTION = setOf("Failed to fetch", "Load failed", "NetworkError when attempting to fetch resource.", "The network connection was lost.")
    const val NETWORK = "The connection to the network was lost before it answered, so the transaction may have been sent or not: check the activity list, and wait a few minutes before trying again, with the app kept open while it proves."

    /** The first line, without the bridge's method prefix: no stack trace, no URL scheme cut in half. */
    fun friendly(message: String?): String =
        message.orEmpty().lineSequence().first().replaceFirst(PREFIX, "").trim().let { if (it in LOST_CONNECTION) NETWORK else it }
            .ifBlank { "Something went wrong" }
}
