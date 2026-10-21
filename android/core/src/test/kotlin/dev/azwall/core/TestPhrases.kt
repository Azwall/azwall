package dev.azwall.core

/** Recovery phrases for fake backends: any 24 words, and account i's key is i + 1 (the real scheme is Rust's). */
object TestPhrases {
    val PHRASE = List(23) { "abandon" }.plus("art").joinToString(" ")

    fun normalize(phrase: String): String {
        val words = phrase.trim().lowercase().split(Regex("\\s+"))
        require(words.size == 24) { "this is not a valid recovery phrase: 24 words expected" }
        return words.joinToString(" ")
    }

    fun key(index: Int): String = "0x" + (index + 1).toString(16).padStart(64, '0')
}
