package dev.azwall.core.wallet

import java.net.URLEncoder

/** A block explorer of a network (networks.json): the start page lists it, a transaction's details link to it. */
data class KnownExplorer(val name: String, val url: String, val txUrl: String) {
    /** The page of the transaction [hash]; null for anything but a 0x hex hash, which could otherwise reshape the URL. */
    fun pageOfTx(hash: String): String? {
        val digits = hash.removePrefix("0x")
        if (!hash.startsWith("0x") || digits.isEmpty() || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return txUrl.replace("{hash}", hash)
    }

    /** The start page shows it as a tile, like the network's dApps. */
    val tile: KnownDapp get() = KnownDapp(name, url, "Blocks, transactions and contracts")
}

/** A site the browser's start page suggests on a network (networks.json). */
data class KnownDapp(val name: String, val url: String, val description: String = "") {
    /** What a tile shows under the name: `aztec.palmyr.io`. */
    val host: String get() = url.substringAfter("://").substringBefore('/')

    /** The icon shipped with the app for this site (icons/dapps/<host>.png), drawn without asking the site. */
    val iconPath: String get() = "dapps/$host.png"

    /** Six hex digits derived from the URL (FNV-1a), for a badge colour that is the same on every device. */
    val tint: String get() = "%06x".format(fnv1a(url) and 0xFFFFFF)
}

private fun fnv1a(text: String): Int = text.toByteArray().fold(0x811c9dc5.toInt()) { h, b -> (h xor (b.toInt() and 0xFF)) * 0x01000193 }

/** Where the address bar sends what is not an address: engines that do not profile who searches. */
enum class SearchEngine(val label: String, private val query: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q="),
    ;

    fun search(text: String): String = query + URLEncoder.encode(text, Charsets.UTF_8)

    companion object {
        val DEFAULT = DUCKDUCKGO

        /** The engine saved under [name], or the default one. */
        fun named(name: String?): SearchEngine = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

object Browsing {
    /** Said when this build lacks the network's content script: sites open, but no dApp can reach the wallet. */
    fun noConnectionScript(network: String) =
        "Apps cannot connect to the wallet on $network: this version of Azwall lacks the connection script for it. Update the app."

    /**
     * What the address bar opens: a URL as typed, a host (https; http for this device), or a search with [engine].
     * Null for nothing typed: the start page.
     */
    fun addressOf(input: String, engine: SearchEngine): String? {
        val text = input.trim()
        return when {
            text.isEmpty() -> null
            text.startsWith("https://") || text.startsWith("http://") -> text
            text.startsWith("localhost") || text.startsWith("127.0.0.1") -> "http://$text"
            ' ' !in text && '.' in text -> "https://$text"
            else -> engine.search(text)
        }
    }
}
