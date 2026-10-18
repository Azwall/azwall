package dev.azwall.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.WalletBackend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Status<out T> {
    data object Idle : Status<Nothing>
    data object Running : Status<Nothing>
    data class Done<T>(val value: T) : Status<T>
    data class Failed(val message: String) : Status<Nothing>
}

data class WalletUiState(
    val networks: List<NetworkInfo> = emptyList(),
    val selectedId: String? = null,
    val node: Status<NodeCheck> = Status.Idle,
    val prover: Status<String> = Status.Idle,
    val startupError: String? = null,
) {
    val selected: NetworkInfo? get() = networks.firstOrNull { it.id == selectedId }
}

/**
 * Network selection, node check and prover warm-up. [backend] is created lazily (it reads the
 * bundled config) and every call runs on [io], so a failure only lands in the state.
 */
class WalletViewModel(
    private val backend: () -> WalletBackend,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()
    private var nodeJob: Job? = null

    fun load() {
        viewModelScope.launch {
            val loaded = withContext(io) { runCatching { backend().let { it.networks() to it.defaultNetworkId() } } }
            loaded
                .onSuccess { (networks, default) ->
                    _state.update { it.copy(networks = networks, startupError = null) }
                    select(default)
                }
                .onFailure { e -> _state.update { it.copy(startupError = e.message ?: e.toString()) } }
        }
    }

    /** Selects a network and checks its node; a newer selection cancels the pending check. */
    fun select(networkId: String) {
        if (_state.value.networks.none { it.id == networkId }) return
        _state.update { it.copy(selectedId = networkId, node = Status.Running, prover = Status.Idle) }
        nodeJob?.cancel()
        nodeJob = viewModelScope.launch {
            val result = withContext(io) { runCatching { backend().checkNode(networkId) } }
            if (_state.value.selectedId != networkId) return@launch
            _state.update { it.copy(node = result.toStatus()) }
        }
    }

    fun prepareProver() {
        val network = _state.value.selected ?: return
        _state.update { it.copy(prover = Status.Running) }
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching { backend().prepareProver(network.id) }.map { network.barretenbergVersion }
            }
            if (_state.value.selectedId == network.id) _state.update { it.copy(prover = result.toStatus()) }
        }
    }
}

private fun <T> Result<T>.toStatus(): Status<T> =
    fold({ Status.Done(it) }, { Status.Failed(it.message ?: it.toString()) })
