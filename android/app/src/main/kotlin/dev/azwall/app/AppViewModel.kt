package dev.azwall.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.vault.AuthenticationRequired
import dev.azwall.core.vault.Vault
import dev.azwall.core.vault.VaultContents
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

    private var contents: VaultContents? = null

    /** dApp requests waiting for the user (the browser shows them as sheets). */
    val approvals get() = deps.approvals

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
    fun createWallet(signingKey: String, alias: String, auth: Auth) = action {
        val account = AccountSecrets(alias.trim().ifEmpty { "Account 1" }, Vault.parseSigningKey(signingKey), Vault.ZERO_SALT)
        val created = withAuth(auth) { withContext(io) { deps.vault.create(account) } }
        start(created)
    }

    fun unlock(auth: Auth) = action {
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
