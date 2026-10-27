package dev.azwall.core.wallet

/** Error text for people, the same on both apps. */
object Errors {
    /** A `method: ` prefix the PXE bridge adds to a failed call's error. */
    private val PREFIX = Regex("^[A-Za-z][A-Za-z0-9]*: ")

    /**
     * What the PXE's web view says when a request never got an answer (Chromium, WebKit, Firefox): the connection
     * dropped, often because the app went to the background while proving. Nothing reached the network.
     */
    private val LOST_CONNECTION = setOf("Failed to fetch", "Load failed", "NetworkError when attempting to fetch resource.", "The network connection was lost.")
    const val NETWORK = "The network could not be reached, so nothing was sent: check your connection and try again, with the app kept open while it proves."

    /** The first line, without the bridge's method prefix: no stack trace, no URL scheme cut in half. */
    fun friendly(message: String?): String =
        message.orEmpty().lineSequence().first().replaceFirst(PREFIX, "").trim().let { if (it in LOST_CONNECTION) NETWORK else it }
            .ifBlank { "Something went wrong" }
}
