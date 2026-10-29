package dev.azwall.core.wallet

import dev.azwall.core.DiagnosticLog
import dev.azwall.core.pxe.FeeJuiceDeposit
import dev.azwall.core.pxe.Receipt
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.pxe.TransferKind
import dev.azwall.core.pxe.TxOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.math.BigInteger
import java.util.UUID

/** What the UI shows while a transaction is being proven on the device. */
data class ProvingJob(val item: ActivityItem, val startedAt: Long)

/**
 * Every transaction follows the same path, recorded in the history: PROVING (witnesses and the Chonk
 * proof are made on the device), PENDING (accepted by the node, waiting for a block), then CONFIRMED or
 * FAILED. Proving is the long part (≈ 15 to 30 s on a phone); mining is waited for in the background.
 */
class Transactions internal constructor(private val session: WalletSession) {
    private val _proving = MutableStateFlow<ProvingJob?>(null)
    val proving: StateFlow<ProvingJob?> = _proving.asStateFlow()

    /**
     * Deploys the selected account's contract, once: a second activation would only pay a fee to fail. With Fee Juice
     * bridged and ready to claim, the activation claims it and pays its fee with it, in one transaction. [reviewedClaim]
     * is the message hash of the deposit the user's review showed (null: none), so a refresh between the review and the
     * confirmation cannot change what is sent.
     */
    suspend fun deployAccount(reviewedClaim: String?): ActivityItem {
        val account = selected()
        val state = session.state.value
        check(!state.activating) { "the account's activation is already on its way" }
        // What the review said, not what a refresh read since: the deposit it named, or none.
        val claim = reviewedClaim?.let { hash ->
            checkNotNull(state.deposits.firstOrNull { it.messageHash == hash && it.claimable }) {
                "the bridge deposit this activation was to claim cannot be claimed any more: review the activation again"
            }
        }
        // Nothing would pay it: it would only fail once proven.
        check(Fees.canActivate(state.check, state.feeJuice, claim)) {
            "this account holds no Fee Juice to pay its activation: bridge AZTEC from Ethereum to its address first"
        }
        return track(draft(ActivityKind.DEPLOY_ACCOUNT, account)) { session.client.deployAccount(session.secretsOf(account), claim) }
    }

    /** Claims Fee Juice bridged to the selected, active account; the transaction pays its fee with what it claims. */
    suspend fun claimFeeJuice(deposit: FeeJuiceDeposit): ActivityItem {
        val account = selected()
        check(session.state.value.account?.deployed == true) { "activate the account first: activation claims it too" }
        check(!session.state.value.claiming) { "a claim of this Fee Juice is already on its way" }
        return track(draft(ActivityKind.CLAIM_FEE_JUICE, account, FeeJuice.INFO, deposit.units)) { session.client.claimFeeJuice(account, deposit) }
    }

    /** Sends [amount] base units of [token]. For SHIELD and UNSHIELD the recipient is the sender. */
    suspend fun send(kind: TransferKind, token: TokenInfo, to: String, amount: BigInteger): ActivityItem {
        val from = selected()
        val recipient = if (kind == TransferKind.SHIELD || kind == TransferKind.UNSHIELD) from else to.trim()
        val activity = when (kind) {
            TransferKind.PRIVATE -> ActivityKind.SEND_PRIVATE
            TransferKind.PUBLIC -> ActivityKind.SEND_PUBLIC
            TransferKind.SHIELD -> ActivityKind.SHIELD
            TransferKind.UNSHIELD -> ActivityKind.UNSHIELD
        }
        val item = draft(activity, from, token, amount).copy(counterparty = recipient.takeIf { it != from })
        return track(item) { session.client.transfer(kind, from, recipient, token.address, amount) }
    }

    /** Deploys a token with the selected account as minter, and follows it (on any network: the account pays where there is no sponsor). */
    suspend fun deployTestToken(name: String, symbol: String, decimals: Int = 6): ActivityItem {
        val admin = selected()
        val item = draft(ActivityKind.DEPLOY_TOKEN, admin).copy(symbol = symbol)
        return track(item) {
            val deployed = session.client.deployToken(admin, name, symbol, decimals)
            session.store.addToken(TokenInfo(deployed.token, name, symbol, decimals))
            session.store.markCreated(admin, deployed.token)
            deployed.txHash
        }
    }

    /** Mints to [to] (the selected account when null), which must be allowed by the token's minter. */
    suspend fun mint(token: TokenInfo, amount: BigInteger, toPrivate: Boolean, to: String? = null): ActivityItem {
        val me = selected()
        val recipient = to?.trim()?.takeIf { it.isNotEmpty() } ?: me
        val item = draft(ActivityKind.MINT, me, token, amount).copy(counterparty = recipient.takeIf { it != me })
        return track(item) { session.client.mint(me, token.address, recipient, amount, toPrivate) }
    }

    private fun selected(): String = session.state.value.account?.address ?: error("no account selected")

    private fun draft(kind: ActivityKind, account: String, token: TokenInfo? = null, amount: BigInteger? = null) = ActivityItem(
        id = UUID.randomUUID().toString(),
        account = account,
        kind = kind,
        status = ActivityStatus.PROVING,
        createdAt = System.currentTimeMillis(),
        token = token?.address,
        symbol = token?.symbol,
        decimals = token?.decimals,
        amount = amount?.toString(),
    )

    /**
     * Cancels the transaction being proven: the screen is released at once, and its proof is refused ([ProofGate]).
     * False once its proof was released: the transaction may already be on the network, so it is followed instead.
     */
    fun cancel(): Boolean {
        if (!ProofGate.cancel()) return false
        stop?.complete(Unit)
        return true
    }

    private var stop: CompletableDeferred<Unit>? = null

    /**
     * Returns once no cancelled transaction is still being proven: a dApp transaction approved now waits for it, so the
     * cancel that refuses that proof (ProofGate) is not taken for the dApp's.
     */
    suspend fun settle() {
        winding?.join()
    }

    /** A cancelled transaction still being proven in the background; the next one waits for its proof to be refused. */
    private var winding: Job? = null

    /** Proves and sends with [send] (returns the tx hash), then follows the transaction to a block. */
    private suspend fun track(draft: ActivityItem, send: suspend () -> String): ActivityItem {
        check(_proving.value == null) { "another transaction is being proven" }
        // A failed session has no host to send with; checked before anything reopens the proof gate (retireHost).
        check(session.state.value.phase != Phase.FAILED) { session.state.value.error ?: "the wallet is not open" }
        // Its review could not say how its fee is paid (Fees.UNKNOWN): nothing is sent on a guess.
        checkNotNull(session.state.value.check) { "the wallet is still reaching the network: try again in a moment" }
        // One cancel gate for every proof (ProofGate): a cancel must never land on a dApp's transaction.
        check(session.state.value.activity.none { it.kind == ActivityKind.DAPP && it.status == ActivityStatus.PROVING }) {
            "a dApp transaction is being proven; try again once it is sent"
        }
        session.recordActivity(draft)
        val stop = CompletableDeferred<Unit>().also { this.stop = it }
        _proving.value = ProvingJob(draft, System.currentTimeMillis())
        val sent = try {
            draft.copy(status = ActivityStatus.PENDING, txHash = proveAndSend(draft, send, stop))
        } catch (e: Exception) {
            // Told before sending (followSubmissions): the node may have it, so it is followed until a receipt says how it
            // ended, never called failed (and sent again).
            val hash = session.store.item(draft.id)?.txHash.takeIf { e !is CancelledByUser }
            if (hash == null) {
                session.recordActivity(draft.copy(status = ActivityStatus.FAILED, error = if (e is CancelledByUser) "Cancelled" else e.message))
                throw e
            }
            DiagnosticLog.shared.report("transactions", e)
            draft.copy(status = ActivityStatus.PENDING, txHash = hash)
        } finally {
            _proving.value = null
            this.stop = null
        }
        session.recordActivity(sent)
        session.scope.launch { confirm(sent) }
        return sent
    }

    /**
     * Runs [send], or gives it up as soon as the user cancels. Witnesses and the proof cannot be stopped, so a
     * cancelled [send] goes on in the background until the app's prove handler refuses its proof.
     */
    private suspend fun proveAndSend(draft: ActivityItem, send: suspend () -> String, stop: CompletableDeferred<Unit>): String {
        winding?.join()
        ProofGate.reset()
        if (stop.isCompleted) throw CancelledByUser()
        val work = session.scope.async { send() }
        val hash = try {
            select<String?> { work.onAwait { it }; stop.onAwait { null } }
        } catch (e: Exception) {
            val cancelled = ProofGate.cancelled || stop.isCompleted
            // Unanswered before its proof was handed over (a call that timed out while the app was frozen): the page may
            // still finish that proof, and it must not be sent behind a transaction shown failed. The gate stays shut until
            // the next transaction opens it. Any other error (a wrong recipient, a low balance) ended the call: the gate
            // opens again, or every dApp proof after it would be refused as cancelled.
            // cancel() is false once the proof was released: then it may be on the network, and is followed as any other.
            if (!cancelled && e is dev.azwall.core.pxe.PxeException && e.unanswered && ProofGate.cancel()) {
                // The gate alone does not hold: the next transaction opens it again. The page that may still finish this
                // proof is stopped for good, so nothing can send it (its late proof is refused: ProofGate.release); Retry
                // starts a new one, whose dApp proofs must then go through.
                session.retireHost(STUCK)
                ProofGate.reset()
                throw e
            }
            ProofGate.reset()
            throw if (cancelled) CancelledByUser() else e
        }
        if (hash != null) return hash
        winding = session.scope.launch { windDown(draft, work) }
        throw CancelledByUser()
    }

    /** Waits for a cancelled transaction to end; one whose proof was accepted just before the cancel was sent, so it is followed. */
    private suspend fun windDown(draft: ActivityItem, work: Deferred<String>) {
        val hash = try {
            work.await()
        } catch (e: Exception) {
            null
        } finally {
            ProofGate.reset()
        }
        if (hash == null) return
        val sent = draft.copy(status = ActivityStatus.PENDING, txHash = hash)
        session.recordActivity(sent)
        confirm(sent)
    }

    /**
     * Follows a sent transaction to a block. A wait can end without the transaction failing (the app was locked,
     * the host stopped, a timeout), so the outcome always comes from a receipt; with none, the item is left as
     * it is and [resume] looks again on the next opening.
     */
    internal suspend fun confirm(sent: ActivityItem) {
        val hash = sent.txHash ?: return
        val receipt = try {
            session.client.waitForTx(hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No receipt leaves the item pending for resume() to look again; the log keeps why.
            runCatching { session.client.txReceipt(hash) }.onFailure { DiagnosticLog.shared.report("transactions", it) }.getOrNull()
                ?.takeUnless { droppedTooSoon(sent, it) }
        }
        settled(sent, receipt ?: return)?.let {
            session.recordActivity(it)
            session.refresh()
        }
    }

    /**
     * A node answers "dropped" for a transaction it has not seen yet: behind a load balancer, one read can reach a
     * node the transaction has not reached. waitForTx allows for that; a single read does not, so its "dropped" only
     * counts once the transaction is old enough to have been mined. Until then the item stays pending: a transaction
     * shown failed while it was mined would be sent again, and paid twice.
     */
    private fun droppedTooSoon(sent: ActivityItem, receipt: Receipt): Boolean =
        receipt.outcome == TxOutcome.DROPPED && System.currentTimeMillis() - sent.createdAt < DROP_GRACE_MS

    /**
     * Keeps each transaction's hash from the moment the PXE sends it (`txSubmitting`): a wallet that stops before the
     * node's answer still follows it, rather than calling failed a transaction that may be mined.
     */
    internal suspend fun followSubmissions() {
        session.pxeEvents.filter { it.name == "txSubmitting" }.collect { e ->
            // An unreadable one leaves the item without its hash until the answer brings it; reported, as host and app disagree.
            val told = runCatching { json.decodeFromJsonElement<Submitting>(e.data) }.onFailure { DiagnosticLog.shared.report("transactions", it) }.getOrNull()
                ?: return@collect
            // A dApp transaction comes with its id (the gateway labels its send): that very row. Else the wallet's own,
            // of which one at most is being proven; never guessed among several.
            val waiting = session.store.proving().filter { it.txHash == null }.filter {
                if (told.id != null) it.id == told.id else it.kind != ActivityKind.DAPP && (told.from == null || it.account.equals(told.from, ignoreCase = true))
            }
            waiting.singleOrNull()?.let { session.recordActivity(it.copy(txHash = told.txHash)) }
        }
    }

    /** Looks again at transactions sent but not settled by a receipt, e.g. when the app closed while waiting. */
    internal fun resume() {
        session.store.unsettled().forEach { item -> session.scope.launch { confirm(item) } }
    }

    private fun settled(item: ActivityItem, receipt: Receipt): ActivityItem? = when (receipt.outcome) {
        TxOutcome.PENDING -> null
        TxOutcome.SUCCESS -> item.copy(status = ActivityStatus.CONFIRMED, blockNumber = receipt.blockNumber, error = null)
        TxOutcome.REVERTED -> item.copy(status = ActivityStatus.FAILED, blockNumber = receipt.blockNumber, error = REVERTED)
        TxOutcome.DROPPED -> item.copy(status = ActivityStatus.FAILED, error = DROPPED)
    }

    @Serializable
    private data class Submitting(val from: String? = null, val txHash: String, val id: String? = null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Why the wallet engine was stopped after a transaction it left unanswered. */
        const val STUCK = "The wallet engine stopped answering during a transaction. It was stopped so that transaction can never " +
            "be sent late: check the activity, then tap Retry."

        /** Failures read from a receipt: final, never looked at again. */
        const val REVERTED = "reverted on chain"
        const val DROPPED = "dropped by the network"

        /** How old a transaction must be before a single "dropped" read is believed. */
        const val DROP_GRACE_MS = 15 * 60_000L
    }
}
