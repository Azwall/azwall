package dev.azwall.core

import dev.azwall.core.ffi.KnownDapp
import dev.azwall.core.ffi.KnownToken
import dev.azwall.core.ffi.Network
import dev.azwall.core.ffi.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MappingTest {
    @Test
    fun networkMappingHidesTheRpcPath() {
        val network = Network(
            id = "testnet-v6",
            label = "Testnet (v6)",
            aztecVersion = "6.0.0",
            barretenbergVersion = "6.0.0",
            rpcUrl = "https://lb.drpc.org/aztec-testnet/SECRET",
            pxeBundle = "v6",
            proverSupported = true,
            knownTokens = listOf(KnownToken("0xweth", "Wrapped Ether", "WETH", 18u)),
            knownDapps = listOf(KnownDapp("Palmyr", "https://aztec.palmyr.io", "DeFi")),
        )
        val info = network.toInfo()
        assertEquals("lb.drpc.org", info.rpcHost)
        assertEquals("6.0.0", info.barretenbergVersion)
        assertEquals(18, info.knownTokens.single().decimals)
        assertEquals("aztec.palmyr.io", info.dapps.single().host)
    }

    @Test
    fun invalidUrlsAreNotEchoed() {
        assertEquals("invalid URL", hostOf("not a url"))
        assertEquals("invalid URL", hostOf(""))
    }

    @Test
    fun nodeStatusMapping() {
        val status = NodeStatus(
            nodeVersion = "5.0.0",
            l1ChainId = 11155111u,
            rollupVersion = 3409706519u,
            compatible = false,
            mismatches = listOf("node version 5.0.0 (client 6.0.0)"),
        )
        val check = status.toCheck()
        assertEquals(3409706519L, check.rollupVersion)
        assertFalse(check.compatible)
        assertEquals(1, check.mismatches.size)
    }
}
