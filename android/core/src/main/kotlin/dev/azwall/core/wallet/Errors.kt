package dev.azwall.core.wallet

/** Error text for people, the same on both apps. */
object Errors {
    /** A `method: ` prefix the PXE bridge adds to a failed call's error. */
    private val PREFIX = Regex("^[A-Za-z][A-Za-z0-9]*: ")

    /** The first line, without the bridge's method prefix: no stack trace, no URL scheme cut in half. */
    fun friendly(message: String?): String =
        message.orEmpty().lineSequence().first().replaceFirst(PREFIX, "").trim().ifBlank { "Something went wrong" }
}
