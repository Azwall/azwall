package dev.azwall.core.wallet

import dev.azwall.core.NetworkInfo
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.pxe.FoundToken
import dev.azwall.core.pxe.NetworkCheck
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.pxe.SyncStatus
import dev.azwall.core.pxe.TokenBalance
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.vault.Recovery
import dev.azwall.core.vault.Vault
import dev.azwall.core.vault.VaultContents
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/** Background work the wallet shows in its status line, like an IDE's. */
sealed interface BackgroundTask {
    /** Syncing the PXE with the chain (it syncs while reading balances); [from] says how far behind it started. */
    data class Syncing(val from: SyncStatus?) : BackgroundTask

    /** Looking among the contracts dApps registered for tokens the account holds. */
    data object FindingTokens : BackgroundTask
}

/** A transaction being proven, or sent and waiting for a block. */
internal val IN_FLIGHT = setOf(ActivityStatus.PROVING, ActivityStatus.PENDING)

data class SessionState(
    val network: NetworkInfo,
    val phase: Phase = Phase.STARTING,
    val check: NetworkCheck? = null,
    val accounts: List<AccountView> = emptyList(),
    val selected: String? = null,
    val tokens: List<TokenView> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    /** Tokens the account holds that are not followed yet, found among the contracts dApps registered. */
    val found: List<FoundToken> = emptyList(),
    /** The token the user chose to show at the top (an address), if any. */
    val primary: String? = null,
    /** Tokens the selected account created (it mints them), as addresses. */
    val created: List<String> = emptyList(),
    /** True while the shown balances come from the last session (AccountSnapshot), until a refresh confirms them. */
    val stale: Boolean = false,
    /** What runs in the background right now, for the status line (null when idle). */
    val task: BackgroundTask? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Where fees are paid with Fee Juice: base units the selected account holds (Fees). */
    val feeJuice: String? = null,
) {
    val account: AccountView? get() = accounts.firstOrNull { it.address == selected }

    /** The selected account's activation is sent and not in a block yet: activating again would pay twice. */
    val activating: Boolean
        get() = activity.any { it.kind == ActivityKind.DEPLOY_ACCOUNT && it.account == selected && it.status in IN_FLIGHT }

    /**
     * The token shown at the top: the one the user chose while it is followed, else the first holding something,
     * else the first. Aztec has no native token and the wallet no prices, so there is no total to show instead.
     */
    val hero: TokenView? get() = tokens.firstOrNull { it.info.address.equals(primary, ignoreCase = true) }
        ?: tokens.firstOrNull { b -> b.balance?.let { it.privateAmount.signum() > 0 || it.publicAmount.signum() > 0 } == true }
        ?: tokens.firstOrNull()
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
    private val parent: CoroutineScope,
) {
    /** The session's own coroutines (refreshes, followers, confirmations): cancelled together by [close]. */
    internal val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    @Volatile private var closed = false
    private val _state = MutableStateFlow(SessionState(network))
    val state: StateFlow<SessionState> = _state.asStateFlow()
    internal val store = WalletStore(storeDir, network.id)
    private val running = MutableStateFlow<RunningPxe?>(null)
    private val pxe: RunningPxe? get() = running.value
    private val refreshLock = Mutex()
    private var lastDiscovery = 0L
    val transactions = Transactions(this)
    val dapps = DappLink(this)

    /** Events of the running PXE host (they restart with it). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val pxeEvents: Flow<PxeEvent> = running.flatMapLatest { it?.events ?: emptyFlow() }

    internal val client: PxeClient get() = pxe?.client ?: error("the wallet is not open")

    /** Starts the PXE, opens the network and registers every account. Failures land in [state]. */
    suspend fun open() {
        store.failInterrupted()
        store.seed(network.knownTokens)
        Snapshots.restore(_state.value, contents, store)?.let { restored -> _state.value = restored }
        scope.launch { dapps.followActivity() }
        scope.launch(Dispatchers.Default) { runCatching { backend.prepareProver(network.id) } }
        try {
            val host = launcher.launch(network)
            // Locked or switched away while the host started: nobody would ever stop it.
            if (closed) return host.stop()
            running.value = host
            val check = host.client.open(backend.pxeOpenParams(network.id), contents.dbKey)
            // Registering keeps what the snapshot knew of each account until the refresh reads it again.
            val known = _state.value.accounts
            val accounts = contents.accounts.map { s -> register(s).let { v -> v.copy(deployed = known.firstOrNull { it.address == v.address }?.deployed) } }
            runCatching { host.client.registerTokens(store.tokens.map { it.address }) }
            _state.update { s ->
                val selected = s.selected?.takeIf { sel -> accounts.any { it.address == sel } } ?: accounts.firstOrNull()?.address
                s.copy(phase = Phase.READY, check = check, accounts = accounts, selected = selected)
            }
            transactions.resume()
            refresh()
        } catch (e: Exception) {
            _state.update { it.copy(phase = Phase.FAILED, error = e.message ?: e.toString()) }
        }
    }

    /**
     * The app goes to the background, where it may be killed without notice: the PXE writes what it holds in memory
     * now rather than at its next debounce.
     */
    suspend fun flush() {
        runCatching { pxe?.client?.flush() }
    }

    fun close() {
        closed = true
        pxe?.let { host -> parent.launch { runCatching { host.client.close() }; host.stop() } }
        running.value = null
        scope.cancel()
    }

    fun select(address: String) {
        if (_state.value.accounts.none { it.address == address }) return
        lastDiscovery = 0L
        _state.update {
            it.copy(selected = address, tokens = it.tokens.map { t -> t.copy(balance = null) }, found = emptyList(), primary = store.primary(address), feeJuice = null)
        }
        scope.launch { refresh() }
    }

    /**
     * Re-reads deployment status, balances and history of the selected account. The user may pick another account
     * while it runs: then its results are dropped rather than shown (and saved) as the new account's, and the refresh
     * that [select] queued reads the new one.
     */
    suspend fun refresh() = refreshLock.withLock {
        val account = _state.value.account ?: return
        val current = { _state.value.selected == account.address }
        val sync = runCatching { client.syncStatus() }.getOrNull()
        _state.update { it.copy(refreshing = true, task = BackgroundTask.Syncing(sync)) }
        try {
            val deployed = client.isDeployed(account.address)
            val tokens = store.tokens
            val balances = if (deployed && tokens.isNotEmpty()) client.balances(account.address, tokens.map { it.address }) else emptyList()
            _state.update { s ->
                val accounts = s.accounts.map { if (it.address == account.address) it.copy(deployed = deployed) else it }
                if (s.selected != account.address) return@update s.copy(accounts = accounts)
                s.copy(
                    accounts = accounts,
                    tokens = tokens.map { info -> TokenView(info, balances.firstOrNull { it.token.equals(info.address, true) }) },
                    activity = store.activity(account.address),
                    primary = store.primary(account.address),
                    created = store.created(account.address),
                    stale = false,
                    error = null,
                )
            }
            if (current()) Snapshots.save(_state.value, store)
        } catch (e: Exception) {
            if (current()) _state.update { it.copy(error = e.message) }
        } finally {
            _state.update { it.copy(refreshing = false, task = null) }
        }
        // Independent of the balances: a token that cannot be read must not hide the others found.
        if (current() && _state.value.account?.deployed == true) discoverTokens(account.address)
        if (current() && Fees.paysWithFeeJuice(_state.value.check)) refreshFeeJuice(account.address)
    }

    /** What the account holds of Fee Juice, which pays its fees, activation included, where there is no sponsor. */
    private suspend fun refreshFeeJuice(address: String) {
        val balance = runCatching { client.feeJuiceBalance(address) }.getOrNull() ?: return
        _state.update { if (it.selected == address) it.copy(feeJuice = balance) else it }
    }

    /** Creates a fresh account, or imports one from its signing key, and saves it in the vault. */
    suspend fun addAccount(alias: String, importedKey: String? = null): AccountView {
        val secrets = Recovery.nextAccount(contents, alias, importedKey, backend)
        contents = vault.addAccount(contents, secrets)
        val view = register(secrets)
        _state.update { it.copy(accounts = it.accounts + view) }
        // Like any switch: the previous account's balances and suggestions must not show under the new one.
        select(view.address)
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

    /** Shows [address] at the top of the home screen for the selected account. */
    fun choosePrimary(address: String) {
        val account = _state.value.selected ?: return
        store.setPrimary(account, address)
        _state.update { it.copy(primary = address) }
    }

    /** Follows a found token; its balance is already known. */
    fun followFound(token: FoundToken) {
        store.addToken(token.info)
        lastDiscovery = 0L // the next refresh reads the new token's history
        _state.update { s -> s.copy(tokens = s.tokens + TokenView(token.info, token.balance), found = s.found - token) }
    }

    /** Stops suggesting a found token. */
    fun dismissFound(token: FoundToken) {
        store.dismiss(token.address)
        _state.update { s -> s.copy(found = s.found - token) }
    }

    /**
     * Discovery is a hint: a failure leaves the suggestions as they were. It simulates calls on every candidate,
     * so it runs at most once per [DISCOVERY_MS] (the home screen refreshes more often).
     */
    private suspend fun discoverTokens(owner: String) {
        val now = System.currentTimeMillis()
        if (now - lastDiscovery < DISCOVERY_MS) return
        lastDiscovery = now
        val known = store.tokens.map { it.address } + store.dismissed
        _state.update { it.copy(task = BackgroundTask.FindingTokens) }
        val found = runCatching { client.discoverTokens(owner, known) }.getOrNull()
        importHistory(owner)
        _state.update { s -> s.copy(task = null, found = if (found != null && s.selected == owner) found else s.found) }
    }

    /** Adds the private transfers the PXE reads back from the chain that the history does not have. */
    private suspend fun importHistory(owner: String) {
        val tokens = store.tokens
        if (tokens.isEmpty()) return
        val moves = runCatching { client.tokenHistory(owner, tokens.map { it.address }) }.getOrNull() ?: return
        store.addMissing(History.items(owner, moves, tokens, store.activity(owner)))
        _state.update { s -> if (s.selected == owner) s.copy(activity = store.activity(owner)) else s }
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

    private companion object {
        const val DISCOVERY_MS = 60_000L
    }
}
