package dev.azwall.core.wallet

import dev.azwall.core.DiagnosticLog
import dev.azwall.core.pxe.PxeChannel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import dev.azwall.core.NetworkInfo
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.pxe.FeeJuiceDeposit
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
import dev.azwall.core.vault.VaultState
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** Base units of Fee Juice the selected account holds (FeeJuice), once read. */
    val feeJuice: String? = null,
    /** Fee Juice bridged to the selected account from Ethereum, claimed or not (FeeJuiceDeposit). */
    val deposits: List<FeeJuiceDeposit> = emptyList(),
    /** The network runs a new chain since last time (WalletStore.moveTo): shown until the user acknowledges it. */
    val chainReset: Boolean = false,
    /** What this phone kept for the network could not be read and started over (WalletStore.startedOver). */
    val storeUnreadable: Boolean = false,
) {
    val account: AccountView? get() = accounts.firstOrNull { it.address == selected }

    /** The selected account's activation is sent and not in a block yet: activating again would pay twice. */
    val activating: Boolean
        get() = activity.any { it.kind == ActivityKind.DEPLOY_ACCOUNT && it.account == selected && it.status in IN_FLIGHT }

    /**
     * A claim of bridged Fee Juice is sent and not in a block yet: the deposit still reads as unclaimed until then, and
     * claiming it again would only pay a fee to fail.
     */
    val claiming: Boolean
        get() = activity.any { it.kind == ActivityKind.CLAIM_FEE_JUICE && it.account == selected && it.status in IN_FLIGHT }

    /** The first deposit the wallet can claim now, if any: activation claims it in the same transaction. */
    val claimable: FeeJuiceDeposit? get() = deposits.firstOrNull { it.claimable }

    /** Deposits made with a secret the wallet does not know: the user can enter it (WalletSession.addClaimSecret). */
    val locked: List<FeeJuiceDeposit> get() = deposits.filter { it.locked }

    /** Base units bridged to the account and not on Aztec yet. */
    val arriving: BigInteger get() = deposits.filter { it.arriving }.fold(BigInteger.ZERO) { sum, d -> sum + d.units }

    /** The account's Fee Juice, as the first token of the home screen: 0 until read, as a new account holds. */
    val feeJuiceToken: TokenView get() = FeeJuice.token(feeJuice ?: "0")

    /** What the top of the home screen can show: Fee Juice, then the followed tokens. */
    val shown: List<TokenView> get() = listOf(feeJuiceToken) + tokens

    /** The token shown at the top: the one the user chose while it is followed, else Fee Juice, Aztec's fee token. */
    val hero: TokenView get() = shown.firstOrNull { it.info.address.equals(primary, ignoreCase = true) } ?: feeJuiceToken
}

/**
 * One unlocked wallet on one network: the PXE host, the vault's accounts (their address depends on the
 * network's Aztec version), the followed tokens with balances, and the transaction history. Everything
 * the UI shows comes from [state]; every action reports failures there or by throwing to the caller.
 */
class WalletSession(
    private val backend: WalletBackend,
    private val launcher: PxeLauncher,
    /** The unlocked vault, shared with the sessions before and after this one (VaultState). */
    private val vaultState: VaultState,
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
    /** Watches deposits on their way from Ethereum (watchArrivals); null when none is. */
    private var arrivals: Job? = null
    /** The account [arrivals] watches: only the selected one is, so another one's watcher gives way. */
    private var arrivalsOf: String? = null
    val transactions = Transactions(this)
    val dapps = DappLink(this)

    /**
     * Whether a transaction is on its way, the wallet's own or a dApp's: being proven or handed to the node. Locking then
     * would stop it midway, so the auto-lock waits for it (AppViewModel).
     */
    val sending: Flow<Boolean> get() = combine(transactions.proving, state) { proving, s -> isSending(proving, s) }

    /** [sending], now. */
    val isSending: Boolean get() = isSending(transactions.proving.value, state.value)

    /**
     * Every account's dApp rows count, not the selected one's only; a row with its hash is on the network already, and
     * a failed session sends nothing more (its rows would never end).
     */
    private fun isSending(proving: ProvingJob?, s: SessionState) = proving != null ||
        s.phase != Phase.FAILED && store.proving().any { it.kind == ActivityKind.DAPP && it.txHash == null }

    /** Events of the running PXE host (they restart with it). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val pxeEvents: Flow<PxeEvent> = running.flatMapLatest { it?.events ?: emptyFlow() }

    internal val client: PxeClient get() = pxe?.client ?: error("the wallet is not open")

    /** Starts the PXE, opens the network and registers every account. Failures land in [state]. */
    suspend fun open() {
        if (store.startedOver) _state.update { it.copy(storeUnreadable = true) }
        store.failInterrupted()
        store.seed(network.knownTokens)
        Snapshots.restore(_state.value, contents, store)?.let { restored -> _state.value = restored }
        scope.launch { dapps.followActivity() }
        scope.launch { transactions.followSubmissions() }
        // Only warms the prover ahead of the first transaction; the log keeps why it could not.
        scope.launch(Dispatchers.Default) { runCatching { backend.prepareProver(network.id) }.onFailure { DiagnosticLog.shared.report("prover", it) } }
        try {
            val host = launcher.launch(network)
            // Locked or switched away while the host started: nobody would ever stop it.
            if (closed) return host.stop()
            running.value = host
            watchHost(host)
            val check = host.client.open(backend.pxeOpenParams(network.id), contents.dbKey)
            adoptChain(check)
            // Registering keeps what the snapshot knew of each account until the refresh reads it again.
            val known = _state.value.accounts
            val accounts = contents.accounts.map { s -> register(s).let { v -> v.copy(deployed = known.firstOrNull { it.address == v.address }?.deployed) } }
            Snapshots.remember(accounts, store)
            // The wallet opens anyway, but a token the PXE does not know reads no balance; the log says why.
            runCatching { host.client.registerTokens(store.tokens.map { it.address }) }.onFailure { DiagnosticLog.shared.report("pxe", it) }
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
     * The host can stop on its own (the system ends its web view to free memory): every call would fail from then on,
     * so the wallet says so, and Retry starts a new session.
     */
    private fun watchHost(host: RunningPxe) = scope.launch {
        val gone = host.events.firstOrNull { it.name == PxeChannel.CLOSED }
        if (gone == null || closed) return@launch
        val reason = (gone.data as? JsonPrimitive)?.content ?: "the wallet engine stopped"
        DiagnosticLog.shared.record(DiagnosticLog.Level.WARN, "pxe", reason)
        _state.update { it.copy(phase = Phase.FAILED, error = reason, refreshing = false, task = null) }
    }

    /** A network reset since last time drops what the screen shows of the old chain (WalletStore.moveTo). */
    private fun adoptChain(check: NetworkCheck) {
        if (!store.moveTo(WalletStore.chainOf(check), network.knownTokens)) return
        _state.update {
            it.copy(
                chainReset = true, stale = false, found = emptyList(), primary = null, created = emptyList(),
                tokens = store.tokens.map { t -> TokenView(t, null) }, activity = emptyList(),
            )
        }
    }

    fun acknowledgeChainReset() = _state.update { it.copy(chainReset = false) }

    fun acknowledgeStoreUnreadable() = _state.update { it.copy(storeUnreadable = false) }

    /**
     * The app goes to the background, where it may be killed without notice: the PXE writes what it holds in memory
     * now rather than at its next debounce.
     */
    suspend fun flush() {
        // The PXE still writes at its next debounce; a failed flush only risks what a kill would lose.
        runCatching { pxe?.client?.flush() }.onFailure { DiagnosticLog.shared.report("pxe", it) }
    }

    /**
     * Stops the running host for good (it left a transaction unanswered and may still send it late): the session fails
     * with [reason], and Retry starts a new host.
     */
    internal fun retireHost(reason: String) {
        val host = running.value ?: return
        running.value = null
        host.stop()
        DiagnosticLog.shared.record(DiagnosticLog.Level.WARN, "pxe", reason)
        _state.update { it.copy(phase = Phase.FAILED, error = reason, refreshing = false, task = null) }
    }

    fun close() {
        closed = true
        // Closing only lets the PXE finish its writes: the host is stopped right after either way. With the app's scope
        // already gone (its screen destroyed), it is stopped at once: left running, it would keep the keys in memory and
        // its stores locked, and the wallet could not open again.
        pxe?.let { host -> if (parent.isActive) parent.launch { runCatching { host.client.close() }; host.stop() } else host.stop() }
        running.value = null
        scope.cancel()
    }

    fun select(address: String) {
        if (_state.value.accounts.none { it.address == address }) return
        lastDiscovery = 0L
        _state.update {
            it.copy(selected = address, tokens = it.tokens.map { t -> t.copy(balance = null) }, found = emptyList(), primary = store.primary(address), feeJuice = null, deposits = emptyList())
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
        // The refresh goes on without a sync height; the log keeps why it could not be read.
        val sync = runCatching { client.syncStatus() }.onFailure { DiagnosticLog.shared.report("sync", it) }.getOrNull()
        _state.update { it.copy(refreshing = true, task = BackgroundTask.Syncing(sync)) }
        try {
            val deployed = client.isDeployed(account.address)
            val tokens = store.tokens
            // An account receives before it is activated: balances are utility and public reads, without its contract.
            val balances = if (tokens.isNotEmpty()) client.balances(account.address, tokens.map { it.address }) else emptyList()
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
                    // A failed session keeps saying why (Transactions.STUCK, a stopped host): a read does not undo that.
                    error = if (s.phase == Phase.FAILED) s.error else null,
                )
            }
            if (current()) Snapshots.save(_state.value, store)
        } catch (e: Exception) {
            if (current()) _state.update { if (it.phase == Phase.FAILED) it else it.copy(error = e.message) }
        } finally {
            _state.update { it.copy(refreshing = false, task = null) }
        }
        // Independent of the balances: a token that cannot be read must not hide the others found.
        if (current()) discoverTokens(account.address)
        if (current()) refreshFeeJuice(account.address)
    }

    /** What the account holds of Fee Juice (shown first, and what pays its fees where there is no sponsor), and what was bridged to it. */
    private suspend fun refreshFeeJuice(address: String) {
        // The deposits shown stay as they were: reported, as a silent failure here once hid a bridge read that never worked.
        runCatching { client.feeJuiceDeposits(address, store.claimSecrets(address)) }.onFailure { DiagnosticLog.shared.report("feeJuice", it) }.onSuccess { found ->
            _state.update { if (it.selected == address) it.copy(deposits = found) else it }
            if (found.any { it.arriving }) watchArrivals(address)
        }
        // The last balance known stays shown; the log keeps why it was not refreshed.
        val balance = runCatching { client.feeJuiceBalance(address) }.onFailure { DiagnosticLog.shared.report("feeJuice", it) }.getOrNull() ?: return
        _state.update { if (it.selected == address) it.copy(feeJuice = balance) else it }
        _state.value.account?.takeIf { it.address == address }?.let { Snapshots.saveFeeJuice(it, balance, store) }
    }

    /**
     * Reads the deposits again while one is on its way from Ethereum (a few minutes), so that it shows as ready (and
     * activation offers to claim it) without the user pulling to refresh. Bounded: a deposit the node never sees stops
     * being watched after ARRIVAL_CHECKS reads.
     */
    private fun watchArrivals(address: String) {
        if (arrivals?.isActive == true && arrivalsOf == address) return
        // Another account's watcher would only stop at its next tick: this account would never be watched.
        arrivals?.cancel()
        arrivalsOf = address
        arrivals = scope.launch {
            repeat(ARRIVAL_CHECKS) {
                delay(ARRIVAL_POLL_MS)
                if (closed || _state.value.selected != address) return@launch
                // The next poll tries again; each failure is kept for the log.
                val found = runCatching { client.feeJuiceDeposits(address, store.claimSecrets(address)) }.onFailure { DiagnosticLog.shared.report("feeJuice", it) }.getOrNull() ?: return@repeat
                _state.update { if (it.selected == address) it.copy(deposits = found) else it }
                // Arrived: a full refresh reads the balance it brings once claimed, and the activation it unblocks.
                if (found.none { it.arriving }) { refresh(); return@launch }
            }
        }
    }

    /** The vault as it is now: read from the one owner every time, never from a copy (VaultState). */
    private val contents: VaultContents get() = vaultState.contents

    /** Creates a fresh account, or imports one from its signing key, and saves it in the vault. */
    suspend fun addAccount(alias: String, importedKey: String? = null): AccountView {
        // From the latest contents, after any other save: the next account index and the duplicate checks see them.
        val secrets = vaultState.change { vault, current ->
            val secrets = Recovery.nextAccount(current, alias, importedKey, backend)
            vault.addAccount(current, secrets) to secrets
        }
        val view = register(secrets)
        _state.update { it.copy(accounts = it.accounts + view) }
        // Like any switch: the previous account's balances and suggestions must not show under the new one.
        select(view.address)
        return view
    }

    suspend fun renameAccount(address: String, alias: String) {
        val account = _state.value.accounts.firstOrNull { it.address == address } ?: return
        vaultState.change { vault, current -> vault.renameAccount(current, account.signingKey, alias) to Unit }
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

    /**
     * Remembers a claim secret the user entered for the selected account's bridge deposits (hex or decimal), and reads
     * the deposits again: the one made with it becomes claimable. Throws on a value that is no field element, and on one
     * that opens none of them, which is not kept.
     */
    suspend fun addClaimSecret(text: String): String {
        val account = _state.value.selected ?: error("no account")
        val secret = ClaimSecrets.normalize(text)
        // Read with it before keeping it (a typo would otherwise pass in silence); a failed read throws rather than
        // calling a good secret wrong.
        val found = client.feeJuiceDeposits(account, store.claimSecrets(account) + secret)
        val opened = found.firstOrNull { it.secret?.lowercase() == secret }
        requireNotNull(opened) { "This secret matches none of the deposits bridged to this account: check it and enter it again" }
        store.addClaimSecret(account, secret)
        refreshFeeJuice(account)
        return opened.messageHash
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
        // Tokens are looked for again at the next discovery; the log keeps why this one failed.
        val found = runCatching { client.discoverTokens(owner, known) }.onFailure { DiagnosticLog.shared.report("tokens", it) }.getOrNull()
        importHistory(owner)
        _state.update { s -> s.copy(task = null, found = if (found != null && s.selected == owner) found else s.found) }
    }

    /** Adds the private transfers the PXE reads back from the chain that the history does not have. */
    private suspend fun importHistory(owner: String) {
        val tokens = store.tokens
        if (tokens.isEmpty()) return
        // The history keeps what it had; the log keeps why the chain could not be read back.
        val moves = runCatching { client.tokenHistory(owner, tokens.map { it.address }) }.onFailure { DiagnosticLog.shared.report("history", it) }.getOrNull() ?: return
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
        /** A deposit's L1 → L2 message lands on Aztec within minutes: read again every 30 s, for 20 minutes at most. */
        const val ARRIVAL_POLL_MS = 30_000L
        const val ARRIVAL_CHECKS = 40
    }
}
