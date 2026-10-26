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
    /** Base units of Fee Juice, the top of the home screen. */
    val feeJuice: String? = null,
)

internal object Snapshots {
    fun keyHash(signingKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(signingKey.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * The state to show before the PXE is up: the accounts known from a previous start (one without a snapshot shows
     * once the PXE gives its address), or null when none is known yet (first start on this network).
     */
    fun restore(state: SessionState, contents: VaultContents, store: WalletStore): SessionState? {
        val known = contents.accounts.mapNotNull { secrets -> store.snapshot(keyHash(secrets.signingKey))?.let { secrets to it } }
        val accounts = known.map { (secrets, snap) -> AccountView(secrets.alias, snap.address, secrets.signingKey, snap.deployed) }
        val first = known.firstOrNull()?.second ?: return null
        val selected = first.address
        val balances = first.balances
        return state.copy(
            accounts = accounts,
            selected = selected,
            tokens = store.tokens.map { info -> TokenView(info, balances.firstOrNull { it.token.equals(info.address, ignoreCase = true) }) },
            activity = store.activity(selected),
            primary = store.primary(selected),
            created = store.created(selected),
            feeJuice = first.feeJuice,
            stale = true,
        )
    }

    /** Updates only the Fee Juice of an account's snapshot: read after its balances, it must not erase them. */
    fun saveFeeJuice(account: AccountView, units: String, store: WalletStore) {
        val snap = store.snapshot(keyHash(account.signingKey)) ?: return
        store.saveSnapshot(snap.copy(feeJuice = units))
    }

    /** Keeps each registered account's address for the next start, even one never refreshed (no balances yet). */
    fun remember(accounts: List<AccountView>, store: WalletStore) {
        for (a in accounts) {
            if (store.snapshot(keyHash(a.signingKey)) == null) store.saveSnapshot(AccountSnapshot(keyHash(a.signingKey), a.address, a.deployed))
        }
    }

    /** Saves what a successful refresh of the selected account showed. */
    fun save(state: SessionState, store: WalletStore, now: Long = System.currentTimeMillis()) {
        val account = state.account ?: return
        val balances = state.tokens.mapNotNull { it.balance }
        store.saveSnapshot(AccountSnapshot(keyHash(account.signingKey), account.address, account.deployed, balances, now, state.feeJuice))
    }
}
