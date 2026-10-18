package dev.azwall.app

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.WalletBackend
import dev.azwall.core.WalletException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeBackend : WalletBackend {
    val prepared = mutableListOf<String>()
    var failNode = false

    override fun networks() = listOf(network("testnet-v6", "6.0.0-rc.6"), network("testnet-v5", "5.1.0"))
    override fun defaultNetworkId() = "testnet-v6"
    override fun checkNode(networkId: String): NodeCheck {
        if (failNode) throw WalletException("connection refused")
        return NodeCheck("6.0.0-rc.3", 1L, compatible = networkId == "testnet-v6", mismatches = emptyList())
    }
    override fun prepareProver(networkId: String) {
        prepared += networkId
    }
    override fun prove(networkId: String, request: ByteArray) = request

    private fun network(id: String, bb: String) = NetworkInfo(id, id, "x", bb, "node.example", proverSupported = true)
}

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val backend = FakeBackend()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model() = WalletViewModel({ backend }, dispatcher)

    @Test
    fun loadSelectsTheDefaultNetworkAndChecksItsNode() = runTest(dispatcher) {
        val model = model()
        model.load()
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(2, state.networks.size)
        assertEquals("testnet-v6", state.selectedId)
        assertTrue((state.node as Status.Done).value.compatible)
    }

    @Test
    fun nodeFailuresLandInTheState() = runTest(dispatcher) {
        backend.failNode = true
        val model = model()
        model.load()
        advanceUntilIdle()
        assertEquals(Status.Failed("connection refused"), model.state.value.node)
    }

    @Test
    fun switchingNetworkResetsTheProverAndChecksAgain() = runTest(dispatcher) {
        val model = model()
        model.load()
        advanceUntilIdle()
        model.prepareProver()
        advanceUntilIdle()
        assertEquals(Status.Done("6.0.0-rc.6"), model.state.value.prover)

        model.select("testnet-v5")
        assertEquals(Status.Idle, model.state.value.prover)
        advanceUntilIdle()
        assertEquals(false, (model.state.value.node as Status.Done).value.compatible)
        model.prepareProver()
        advanceUntilIdle()
        assertEquals(listOf("testnet-v6", "testnet-v5"), backend.prepared)
    }

    @Test
    fun unknownNetworksAreIgnored() = runTest(dispatcher) {
        val model = model()
        model.load()
        advanceUntilIdle()
        model.select("mainnet")
        assertEquals("testnet-v6", model.state.value.selectedId)
    }

    @Test
    fun startupFailuresAreReported() = runTest(dispatcher) {
        val model = WalletViewModel({ throw WalletException("missing azwall-config.json") }, dispatcher)
        model.load()
        advanceUntilIdle()
        assertEquals("missing azwall-config.json", model.state.value.startupError)
    }
}
