package dev.azwall.core.wallet

import dev.azwall.core.NetworkInfo
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.pxe.TransferKind
import dev.azwall.core.vault.Vault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger

/**
 * From the 2026-10-28 independent audit (A03, A06): once its proof is handed over, a transaction may reach the
 * network at any moment. A cancel can no longer say nothing was sent, and the wallet keeps its hash from then on, so a
 * lost answer or a stopped app never turns it into a failure the user would send again.
 */
class SubmissionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val network = NetworkInfo("testnet-v6", "Testnet", "6.0.0", "6.0.0", "node", "v6", true)
    private val pxe = FakePxe().apply { deployed = true }
    private val events = MutableSharedFlow<PxeEvent>(extraBufferCapacity = 8)
    private val scopes = mutableListOf<CoroutineScope>()
    private val token = TokenInfo("0xt", "Test", "TST", 6)
    private val recipient = "0x" + "cd".repeat(32)

    @After
    fun end() {
        scopes.forEach { it.cancel() }
        ProofGate.reset()
    }

    /** A session of the wallet in [tmp]; called again, the same wallet after the app restarted. */
    private fun TestScope.session(): WalletSession {
        val vault = Vault(tmp.root.resolve("vault"), PlainBox)
        val contents = if (vault.exists()) vault.unlock() else vault.create(AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT))
        val launcher = PxeLauncher { object : RunningPxe { override val client = PxeClient(pxe); override val events = this@SubmissionTest.events; override fun stop() {} } }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        return WalletSession(FakeBackend, launcher, unlocked(vault, contents), tmp.root, network, scope)
    }

    private fun submitting(hash: String) = PxeEvent("txSubmitting", Json.parseToJsonElement("""{"from":"ADDR-ABAB","txHash":"$hash"}"""))

    @Test
    fun aTransactionWhoseProofWasReleasedCannotBeCancelled() = runTest {
        val s = session().apply { open() }
        val answer = CompletableDeferred<Unit>()
        pxe.whileSending = { answer.await() }
        val sending = async { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }
        runCurrent()
        assertTrue(ProofGate.released.value)
        assertFalse("a cancel after the proof left must not claim nothing was sent", s.transactions.cancel())
        assertNotNull("the screen still shows the transaction on its way", s.transactions.proving.value)
        answer.complete(Unit)
        val sent = sending.await()
        assertEquals(ActivityStatus.PENDING, sent.status)
        assertEquals("0xhash1", sent.txHash)
        assertFalse(ProofGate.cancelled)
    }

    @Test
    fun aCancelBeforeTheProofIsReleasedStillStopsIt() = runTest {
        val s = session().apply { open() }
        pxe.whileProving = { assertTrue(s.transactions.cancel()) }
        val error = runCatching { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }.exceptionOrNull()
        assertTrue(error is CancelledByUser)
        assertTrue(pxe.sent.isEmpty())
    }

    @Test
    fun aSendThatFailsAfterTheHashWasToldKeepsIt() = runTest {
        val s = session().apply { open() }
        val stops = CompletableDeferred<Unit>()
        pxe.whileSending = {
            events.emit(submitting("0xtold"))
            stops.await()
            error("the wallet engine stopped")
        }
        val sending = async { runCatching { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) } }
        runCurrent()
        stops.complete(Unit)
        sending.await()
        val item = s.state.value.activity.first()
        assertEquals(ActivityStatus.FAILED, item.status)
        assertEquals("0xtold", item.txHash)
        assertTrue("it is looked at again on the chain", item in s.store.unsettled())
    }

    @Test
    fun aTransactionTheAppStoppedSendingIsFollowedAfterARestart() = runTest {
        val s = session().apply { open() }
        val never = CompletableDeferred<Unit>()
        pxe.whileSending = {
            events.emit(submitting("0xtold"))
            never.await()
        }
        val sending = async { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }
        runCurrent()
        assertEquals("0xtold", s.store.proving().single().txHash)
        // The process dies here: nothing more of this session runs.
        sending.cancel()
        scopes.forEach { it.cancel() }
        val next = session().apply { open() }
        advanceUntilIdle()
        val item = next.state.value.activity.first()
        assertEquals(ActivityStatus.PENDING, item.status)
        assertEquals("0xtold", item.txHash)
    }

    @Test
    fun aHashIsNotGivenToAnotherAccountsTransaction() = runTest {
        val s = session().apply { open() }
        val answer = CompletableDeferred<Unit>()
        pxe.whileSending = {
            events.emit(PxeEvent("txSubmitting", Json.parseToJsonElement("""{"from":"addr-other","txHash":"0xother"}""")))
            answer.await()
        }
        val sending = async { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }
        runCurrent()
        assertEquals(null, s.store.proving().single().txHash)
        answer.complete(Unit)
        sending.await()
    }
}
