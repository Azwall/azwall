package dev.azwall.core.wallet

import dev.azwall.core.pxe.Receipt
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.pxe.TransferKind
import dev.azwall.core.pxe.TxOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** Deploys the selected account's contract; fees are paid by the Sponsored FPC. */
    suspend fun deployAccount(): ActivityItem {
        val account = selected()
        return track(draft(ActivityKind.DEPLOY_ACCOUNT, account)) { session.client.deployAccount(session.secretsOf(account)) }
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

    /** Test networks: deploys a token with the selected account as minter, and follows it. */
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

    /** Test networks: mints to [to] (the selected account when null), which must be allowed by the token's minter. */
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

    /** Cancels the transaction being proven: its proof is refused, so it is never sent ([ProofGate]). */
    fun cancel() = ProofGate.cancel()

    /** Proves and sends with [send] (returns the tx hash), then follows the transaction to a block. */
    private suspend fun track(draft: ActivityItem, send: suspend () -> String): ActivityItem {
        check(_proving.value == null) { "another transaction is being proven" }
        session.recordActivity(draft)
        ProofGate.reset()
        _proving.value = ProvingJob(draft, System.currentTimeMillis())
        val sent = try {
            draft.copy(status = ActivityStatus.PENDING, txHash = send())
        } catch (e: Exception) {
            val cancelled = ProofGate.cancelled || e is CancelledByUser
            session.recordActivity(draft.copy(status = ActivityStatus.FAILED, error = if (cancelled) "Cancelled" else e.message))
            throw if (cancelled) CancelledByUser() else e
        } finally {
            _proving.value = null
            ProofGate.reset()
        }
        session.recordActivity(sent)
        session.scope.launch { confirm(sent) }
        return sent
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
            runCatching { session.client.txReceipt(hash) }.getOrNull()
        }
        settled(sent, receipt ?: return)?.let {
            session.recordActivity(it)
            session.refresh()
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

    companion object {
        /** Failures read from a receipt: final, never looked at again. */
        const val REVERTED = "reverted on chain"
        const val DROPPED = "dropped by the network"
    }
}
