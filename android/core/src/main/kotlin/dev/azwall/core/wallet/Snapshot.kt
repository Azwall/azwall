package dev.azwall.core.wallet

import dev.azwall.core.pxe.TokenBalance
import dev.azwall.core.vault.VaultContents
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * What the home screen last showed for an account, saved after each successful refresh: at the next start the
 * wallet shows it at once, marked stale, while the PXE starts and syncs (that takes seconds, the address itself
 * comes from the PXE). Keyed by a hash of the signing key, never the key: nothing here is secret.
 */
@Serializable
data class AccountSnapshot(
    val keyHash: String,
    val address: String,
    val deployed: Boolean?,
    val balances: List<TokenBalance> = emptyList(),
    val at: Long = 0,
)

internal object Snapshots {
    fun keyHash(signingKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(signingKey.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

    /** The state to show before the PXE is up, or null when an account has no snapshot yet (first start). */
    fun restore(state: SessionState, contents: VaultContents, store: WalletStore): SessionState? {
        val snaps = contents.accounts.map { store.snapshot(keyHash(it.signingKey)) ?: return null }
        val accounts = contents.accounts.zip(snaps) { secrets, snap -> AccountView(secrets.alias, snap.address, secrets.signingKey, snap.deployed) }
        val selected = accounts.firstOrNull()?.address ?: return null
        val balances = snaps.first().balances
        return state.copy(
            accounts = accounts,
            selected = selected,
            tokens = store.tokens.map { info -> TokenView(info, balances.firstOrNull { it.token.equals(info.address, ignoreCase = true) }) },
            activity = store.activity(selected),
            primary = store.primary(selected),
            created = store.created(selected),
            stale = true,
        )
    }

    /** Saves what a successful refresh of the selected account showed. */
    fun save(state: SessionState, store: WalletStore, now: Long = System.currentTimeMillis()) {
        val account = state.account ?: return
        val balances = state.tokens.mapNotNull { it.balance }
        store.saveSnapshot(AccountSnapshot(keyHash(account.signingKey), account.address, account.deployed, balances, now))
    }
}
