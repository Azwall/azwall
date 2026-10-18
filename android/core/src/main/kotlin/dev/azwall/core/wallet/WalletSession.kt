package dev.azwall.core.wallet

import dev.azwall.core.NetworkInfo
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.pxe.NetworkCheck
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.pxe.TokenBalance
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.vault.Vault
import dev.azwall.core.vault.VaultContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** A running PXE host for one network. */
interface RunningPxe {
    val client: PxeClient
    val events: Flow<PxeEvent>

    fun stop()
}

/** Starts the PXE host bundle of a network ([dev.azwall.core.pxe.PxeHost] on Android). */
fun interface PxeLauncher {
    suspend fun launch(network: NetworkInfo): RunningPxe
}

data class AccountView(val alias: String, val address: String, val signingKey: String, val deployed: Boolean?)

data class TokenView(val info: TokenInfo, val balance: TokenBalance?)

enum class Phase { STARTING, READY, FAILED }

data class SessionState(
    val network: NetworkInfo,
    val phase: Phase = Phase.STARTING,
    val check: NetworkCheck? = null,
    val accounts: List<AccountView> = emptyList(),
    val selected: String? = null,
    val tokens: List<TokenView> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    val account: AccountView? get() = accounts.firstOrNull { it.address == selected }
}

/**
 * One unlocked wallet on one network: the PXE host, the vault's accounts (their address depends on the
 * network's Aztec version), the followed tokens with balances, and the transaction history. Everything
 * the UI shows comes from [state]; every action reports failures there or by throwing to the caller.
 */
class WalletSession(
    private val backend: WalletBackend,
    private val launcher: PxeLauncher,
    private val vault: Vault,
    private var contents: VaultContents,
    private val storeDir: File,
    val network: NetworkInfo,
    internal val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionState(network))
    val state: StateFlow<SessionState> = _state.asStateFlow()
    internal val store = WalletStore(storeDir, network.id)
    private var pxe: RunningPxe? = null
    private val refreshLock = Mutex()
    val transactions = Transactions(this)

    internal val client: PxeClient get() = pxe?.client ?: error("the wallet is not open")

    /** Starts the PXE, opens the network and registers every account. Failures land in [state]. */
    suspend fun open() {
        store.failInterrupted()
        scope.launch(Dispatchers.Default) { runCatching { backend.prepareProver(network.id) } }
        try {
            val running = launcher.launch(network).also { pxe = it }
            val check = running.client.open(backend.pxeOpenParams(network.id), contents.dbKey)
            val accounts = contents.accounts.map { register(it) }
            _state.update {
                it.copy(phase = Phase.READY, check = check, accounts = accounts, selected = it.selected ?: accounts.firstOrNull()?.address)
            }
            refresh()
        } catch (e: Exception) {
            _state.update { it.copy(phase = Phase.FAILED, error = e.message ?: e.toString()) }
        }
    }

    fun close() {
        pxe?.let { running -> scope.launch { runCatching { running.client.close() }; running.stop() } }
        pxe = null
    }

    fun select(address: String) {
        if (_state.value.accounts.none { it.address == address }) return
        _state.update { it.copy(selected = address, tokens = it.tokens.map { t -> t.copy(balance = null) }) }
        scope.launch { refresh() }
    }

    /** Re-reads deployment status, balances and history of the selected account. */
    suspend fun refresh() = refreshLock.withLock {
        val account = _state.value.account ?: return
        _state.update { it.copy(refreshing = true) }
        try {
            val deployed = client.isDeployed(account.address)
            val tokens = store.tokens
            val balances = if (deployed && tokens.isNotEmpty()) client.balances(account.address, tokens.map { it.address }) else emptyList()
            _state.update { s ->
                s.copy(
                    accounts = s.accounts.map { if (it.address == account.address) it.copy(deployed = deployed) else it },
                    tokens = tokens.map { info -> TokenView(info, balances.firstOrNull { it.token.equals(info.address, true) }) },
                    activity = store.activity(account.address),
                    error = null,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Creates a fresh account, or imports one from its signing key, and saves it in the vault. */
    suspend fun addAccount(alias: String, importedKey: String? = null): AccountView {
        val key = importedKey?.let(Vault::parseSigningKey) ?: client.newSigningKey()
        val secrets = AccountSecrets(alias.trim().ifEmpty { "Account ${contents.accounts.size + 1}" }, key, Vault.ZERO_SALT)
        contents = vault.addAccount(contents, secrets)
        val view = register(secrets)
        _state.update { it.copy(accounts = it.accounts + view, selected = view.address) }
        scope.launch { refresh() }
        return view
    }

    fun renameAccount(address: String, alias: String) {
        val account = _state.value.accounts.firstOrNull { it.address == address } ?: return
        contents = vault.renameAccount(contents, account.signingKey, alias)
        _state.update { s -> s.copy(accounts = s.accounts.map { if (it.address == address) it.copy(alias = alias.trim()) else it }) }
    }

    /** Looks a token up on chain and follows it. */
    suspend fun addToken(address: String): TokenInfo {
        val from = _state.value.account?.address ?: error("no account")
        val info = client.tokenInfo(address.trim(), from)
        store.addToken(info)
        refresh()
        return info
    }

    fun removeToken(address: String) {
        store.removeToken(address)
        _state.update { s -> s.copy(tokens = s.tokens.filterNot { it.info.address.equals(address, true) }) }
    }

    internal fun secretsOf(address: String): AccountSecrets {
        val account = _state.value.accounts.first { it.address == address }
        return contents.accounts.first { it.signingKey == account.signingKey }
    }

    internal fun recordActivity(item: ActivityItem) {
        store.upsert(item)
        _state.update { s -> if (item.account == s.selected) s.copy(activity = store.activity(item.account)) else s }
    }

    private suspend fun register(secrets: AccountSecrets): AccountView =
        AccountView(secrets.alias, client.addAccount(secrets), secrets.signingKey, deployed = null)
}
