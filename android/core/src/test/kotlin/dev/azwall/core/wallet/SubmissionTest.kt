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
    private fun TestScope.session(onStop: () -> Unit = {}): WalletSession {
        val vault = Vault(tmp.root.resolve("vault"), PlainBox)
        val contents = if (vault.exists()) vault.unlock() else vault.create(AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT))
        val launcher = PxeLauncher { object : RunningPxe { override val client = PxeClient(pxe); override val events = this@SubmissionTest.events; override fun stop() = onStop() } }
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

    /** And the store-release audit (F7): failing once the node has it, the send is followed, not called failed. */
    @Test
    fun aSendThatFailsAfterTheHashWasToldIsFollowed() = runTest {
        val s = session().apply { open() }
        val stops = CompletableDeferred<Unit>()
        pxe.whileSending = {
            events.emit(submitting("0xtold"))
            stops.await()
            error("the answer was lost")
        }
        val sending = async { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }
        runCurrent()
        stops.complete(Unit)
        val sent = sending.await()
        assertEquals(ActivityStatus.PENDING, sent.status)
        assertEquals("0xtold", sent.txHash)
        pxe.mined.complete(Unit)
        advanceUntilIdle()
        assertEquals(ActivityStatus.CONFIRMED, s.state.value.activity.first().status)
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

    /**
     * Store-release audit (F13) and open-test audit (OT-01): a transaction left unanswered before its proof left must never
     * be sent behind that failure, even by the proof finishing after a retry started. Its host is stopped for good.
     */
    @Test
    fun aTransactionLeftUnansweredIsNeverSentLater() = runTest {
        var stopped = false
        val s = session(onStop = { stopped = true }).apply { open() }
        pxe.whileProving = { throw dev.azwall.core.pxe.PxeException("transfer: no answer from the wallet engine after 20 min", unanswered = true) }
        val failed = runCatching { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }.exceptionOrNull()
        assertTrue(failed is dev.azwall.core.pxe.PxeException)
        assertTrue("the page that may still send it is stopped", stopped)
        assertEquals(Phase.FAILED, s.state.value.phase)
        assertEquals(Transactions.STUCK, s.state.value.error)
        // A retry on this session cannot start; the old proof, finishing late, is refused by its stopped host.
        pxe.whileProving = {}
        assertTrue(runCatching { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.TWO) }.isFailure)
        org.junit.Assert.assertThrows(CancelledByUser::class.java) { ProofGate.release(hostAlive = false) }
        assertTrue(pxe.sent.isEmpty())
        // Readiness review (N1): after Retry, the new host's first dApp proof goes through.
        ProofGate.check()
        ProofGate.release(hostAlive = true)
        // (N5) a refresh does not erase why the session failed.
        s.refresh()
        assertEquals(Transactions.STUCK, s.state.value.error)
    }

    /** Store-release re-audit (R1): a send the page refused must not leave every dApp proof after it refused as cancelled. */
    @Test
    fun aSendThePageRefusedLetsTheNextDappProofThrough() = runTest {
        val s = session().apply { open() }
        pxe.whileProving = { throw dev.azwall.core.pxe.PxeException("transfer: balance too low") }
        runCatching { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }
        // A dApp's proof goes through the same gate: it must pass.
        ProofGate.check()
        ProofGate.release()
        assertFalse(ProofGate.cancelled)
    }

    private fun dapp(id: String) = PxeEvent(
        "dappTx", Json.parseToJsonElement("""{"id":"$id","account":"addr-abab","origin":"https://dapp.example","summary":"transfer","status":"proving"}"""),
    )

    /** Open-test audit (OT-02): with two dApp transactions of one account queued, the hash goes to the one sent. */
    @Test
    fun queuedDappSendsKeepTheirHashAcrossARestart() = runTest {
        val s = session().apply { open() }
        runCurrent()
        events.emit(dapp("first"))
        runCurrent()
        events.emit(dapp("second"))
        runCurrent()
        events.emit(PxeEvent("txSubmitting", Json.parseToJsonElement("""{"from":"addr-abab","txHash":"0xaccepted","id":"first"}""")))
        runCurrent()
        // The process dies before the gateway's "sent".
        s.close()
        val restarted = WalletStore(tmp.root, network.id)
        restarted.failInterrupted()
        assertEquals("0xaccepted", restarted.item("first")?.txHash)
        assertEquals(ActivityStatus.PENDING, restarted.item("first")?.status)
        assertEquals(null, restarted.item("second")?.txHash)
    }

    /**
     * Final open-test re-audit (FO-01): a send whose proof went, then no answer, may still be sent. It is never called
     * failed, a retry waits for its outcome, and its late hash reaches its own row, by id.
     */
    @Test
    fun aReleasedButUnansweredSendStaysUncertainUntilItsHashComes() = runTest {
        val s = session().apply { open() }
        pxe.whileSending = { throw dev.azwall.core.pxe.PxeException("transfer: no answer from the wallet engine after 20 min", unanswered = true) }
        val error = runCatching { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }.exceptionOrNull()
        assertTrue(error is UncertainSubmission)
        val first = s.store.proving().single()
        assertEquals(Transactions.UNCERTAIN, first.error)
        // A retry would pay twice if the first one lands: refused while the outcome is unknown.
        pxe.whileSending = {}
        val retry = runCatching { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }.exceptionOrNull()
        assertEquals(Transactions.UNCERTAIN, retry?.message)
        // The first send goes out late: its hash, told with its id, makes it pending and followed.
        events.emit(PxeEvent("txSubmitting", Json.parseToJsonElement("""{"from":"addr-abab","txHash":"0xlate","id":"${first.id}"}""")))
        runCurrent()
        val settled = s.store.item(first.id)!!
        assertEquals("0xlate", settled.txHash)
        assertEquals(ActivityStatus.PENDING, settled.status)
    }

    @Test
    fun anUncertainSendSaysSoAfterARestart() = runTest {
        val s = session().apply { open() }
        pxe.whileSending = { throw dev.azwall.core.pxe.PxeException("transfer: no answer", unanswered = true) }
        runCatching { s.transactions.send(TransferKind.PRIVATE, token, recipient, BigInteger.ONE) }
        val id = s.store.proving().single().id
        s.close()
        val restarted = WalletStore(tmp.root, network.id)
        restarted.failInterrupted()
        assertEquals(ActivityStatus.FAILED, restarted.item(id)?.status)
        assertEquals("never 'not sent': it may have been", Transactions.UNCERTAIN, restarted.item(id)?.error)
    }
}

