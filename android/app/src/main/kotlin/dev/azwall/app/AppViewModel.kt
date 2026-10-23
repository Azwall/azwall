package dev.azwall.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.vault.AuthenticationRequired
import dev.azwall.core.vault.Recovery
import dev.azwall.core.vault.VaultContents
import dev.azwall.core.wallet.AutoLock
import dev.azwall.core.wallet.SearchEngine
import dev.azwall.core.wallet.WalletSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppPhase {
    data object Booting : AppPhase

    data object Onboarding : AppPhase

    data object Locked : AppPhase

    data class Unlocked(val session: WalletSession) : AppPhase

    data class Failed(val message: String) : AppPhase
}

class AuthCancelled : Exception("authentication was cancelled")

/** The phone has no screen lock, so the vault's key (which requires one) cannot be created. */
class NoScreenLock : Exception("Set a screen lock (PIN, pattern or password) in your phone's settings first: it protects your wallet's key")

/**
 * The app's lifecycle: onboarding, unlocking the vault, and one [WalletSession] for the selected network
 * (switching network replaces it). Screens read [phase] and the session's own state.
 */
class AppViewModel(private val deps: AppDeps, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Booting)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val _networks = MutableStateFlow<List<NetworkInfo>>(emptyList())
    val networks: StateFlow<List<NetworkInfo>> = _networks.asStateFlow()

    /** Outcome of the last user action (an error, or a confirmation), shown once by the UI. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True while a user action runs (buttons show progress). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Latest node check per network id. */
    private val _nodes = MutableStateFlow<Map<String, NodeCheck>>(emptyMap())
    val nodes: StateFlow<Map<String, NodeCheck>> = _nodes.asStateFlow()

    /** Networks whose Barretenberg release is loaded (the prover keeps one at a time). */
    private val _proverReady = MutableStateFlow<String?>(null)
    val proverReady: StateFlow<String?> = _proverReady.asStateFlow()

    private val _searchEngine = MutableStateFlow(deps.searchEngine)
    val searchEngine: StateFlow<SearchEngine> = _searchEngine.asStateFlow()

    private var contents: VaultContents? = null

    /** dApp requests waiting for the user (the browser shows them as sheets). */
    val approvals get() = deps.approvals

    fun chooseSearchEngine(engine: SearchEngine) {
        deps.searchEngine = engine
        _searchEngine.value = engine
    }

    fun boot() {
        if (_phase.value != AppPhase.Booting) return
        viewModelScope.launch {
            val result = withContext(io) { runCatching { deps.backend.networks() to deps.vault.exists() } }
            result.onSuccess { (networks, exists) ->
                _networks.value = networks
                _phase.value = if (exists) AppPhase.Locked else AppPhase.Onboarding
            }.onFailure { _phase.value = AppPhase.Failed(it.message ?: it.toString()) }
        }
    }

    /** Creates the vault with [signingKey] (generated or imported), then opens the wallet. */
    /** Creates the wallet from [secret]: a recovery phrase (new or restored), or a signing key. */
    fun createWallet(secret: String, alias: String, auth: Auth) = action {
        // Checked first: otherwise the Keystore refuses the key with a message the user cannot act on.
        if (!deps.deviceSecure) throw NoScreenLock()
        val (account, phrase) = withContext(io) { Recovery.firstAccount(secret, alias, deps.backend) }
        val created = withAuth(auth) { withContext(io) { deps.vault.create(account, phrase) } }
        start(created)
    }

    /** A new recovery phrase for onboarding; the Rust core loads on first use, off the main thread. */
    suspend fun newRecoveryPhrase(): String = withContext(io) { deps.backend.newRecoveryPhrase() }

    /** Null when [secret] is a usable phrase or key, else what is wrong with it. */
    suspend fun checkSecret(secret: String): String? = withContext(io) {
        runCatching { Recovery.firstAccount(secret, "", deps.backend) }.exceptionOrNull()?.message
    }

    /** False for a wallet created from a signing key, before recovery phrases. */
    val hasRecoveryPhrase: Boolean get() = contents?.phrase != null

    /** The recovery phrase, after the user authenticated again; null for a wallet created from a key. */
    suspend fun revealPhrase(auth: Auth): String? {
        if (!auth()) throw AuthCancelled()
        return contents?.phrase
    }

    /**
     * Always asks first: the Keystore key alone would open without a prompt for a while after any authentication,
     * the phone's own unlock included, so the wallet would open just because the phone was unlocked.
     */
    fun unlock(auth: Auth) = action {
        if (!auth()) throw AuthCancelled()
        start(withAuth(auth) { withContext(io) { deps.vault.unlock() } })
    }

    fun switchNetwork(id: String) = action {
        val current = (_phase.value as? AppPhase.Unlocked)?.session ?: return@action
        if (current.network.id == id) return@action
        current.close()
        deps.approvals.rejectAll()
        deps.lastNetwork = id
        start(contents ?: error("the wallet is locked"))
    }

    private val autoLock = AutoLock()

    fun onBackground() {
        autoLock.backgrounded()
        (_phase.value as? AppPhase.Unlocked)?.session?.let { session -> viewModelScope.launch { session.flush() } }
    }

    /** Back from the background: locks if away too long, unless a transaction is being proven or sent. */
    fun onForeground() {
        val session = (_phase.value as? AppPhase.Unlocked)?.session
        if (autoLock.foregrounded() && session != null && session.transactions.proving.value == null) lock()
    }

    fun lock() {
        (_phase.value as? AppPhase.Unlocked)?.session?.close()
        contents = null
        _phase.value = AppPhase.Locked
    }

    /** Creates (or imports, with [importedKey]) an account; saving it in the vault may need [auth]. */
    fun addAccount(alias: String, importedKey: String?, auth: Auth) = action {
        val session = (_phase.value as? AppPhase.Unlocked)?.session ?: return@action
        withAuth(auth) { session.addAccount(alias, importedKey) }
    }

    fun renameAccount(address: String, alias: String, auth: Auth) = action {
        val session = (_phase.value as? AppPhase.Unlocked)?.session ?: return@action
        withAuth(auth) { session.renameAccount(address, alias) }
    }

    /** Re-authenticates, then returns the signing key of the account at [address] (for backup). */
    suspend fun revealKey(address: String, auth: Auth): String {
        if (!auth()) throw AuthCancelled()
        val session = (_phase.value as? AppPhase.Unlocked)?.session ?: error("the wallet is locked")
        return session.state.value.accounts.first { it.address == address }.signingKey
    }

    fun wipe(auth: Auth) = action {
        if (!auth()) throw AuthCancelled()
        lock()
        withContext(io) { deps.wipe() }
        _phase.value = AppPhase.Onboarding
    }

    fun checkNode(networkId: String) = action {
        val check = withContext(io) { deps.backend.checkNode(networkId) }
        _nodes.value = _nodes.value + (networkId to check)
    }

    fun loadProver(networkId: String) = action {
        withContext(io) { deps.backend.prepareProver(networkId) }
        _proverReady.value = networkId
    }

    /** Runs a wallet action (transaction, token…) in the view model's scope, so it survives the screen. */
    fun run(done: String? = null, block: suspend (WalletSession) -> Unit) = action {
        val session = (_phase.value as? AppPhase.Unlocked)?.session ?: error("the wallet is locked")
        block(session)
        done?.let { _message.value = it }
    }

    /**
     * A transaction the user confirmed: the fingerprint (or the device's credential) first, as for unlocking. Declined,
     * nothing is proven or sent.
     */
    fun sign(auth: Auth, done: String? = null, block: suspend (WalletSession) -> Unit) = action {
        if (!auth()) throw AuthCancelled()
        val session = (_phase.value as? AppPhase.Unlocked)?.session ?: error("the wallet is locked")
        block(session)
        done?.let { _message.value = it }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Runs [block] (the vault may need [auth] first, when its Keystore key asks for it). */
    private suspend fun <T> withAuth(auth: Auth, block: suspend () -> T): T = try {
        block()
    } catch (e: AuthenticationRequired) {
        if (!auth()) throw AuthCancelled()
        block()
    }

    private suspend fun start(unlocked: VaultContents) {
        contents = unlocked
        val networks = _networks.value
        val network = networks.firstOrNull { it.id == deps.lastNetwork }
            ?: networks.firstOrNull { it.id == withContext(io) { deps.backend.defaultNetworkId() } }
            ?: error("no network is configured")
        deps.lastNetwork = network.id
        val session = WalletSession(deps.backend, deps.launcher, deps.vault, unlocked, deps.storeDir, network, viewModelScope)
        _phase.value = AppPhase.Unlocked(session)
        session.open()
        checkNode(network.id)
    }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            block()
        } catch (e: AuthCancelled) {
            _message.value = e.message
        } catch (e: Exception) {
            _message.value = e.message ?: e.toString()
        } finally {
            _busy.value = false
        }
    }
}
