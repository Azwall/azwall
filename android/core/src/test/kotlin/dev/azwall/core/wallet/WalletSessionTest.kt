package dev.azwall.core.wallet

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.TestPhrases
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.PxeCaller
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.pxe.PxeException
import dev.azwall.core.pxe.TokenBalance
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.pxe.TransferKind
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger

class WalletSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val network = NetworkInfo("testnet-v6", "Testnet", "6.0.0", "6.0.0", "node", "v6", true)
    private val pxe = FakePxe()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelSessions() = scopes.forEach { it.cancel() }

    /** A session; called again, a later one of the same wallet (the vault is created once). */
    private fun TestScope.session(): WalletSession {
        tmp.root.resolve("vault").delete()
        val vault = Vault(tmp.root.resolve("vault"), PlainBox)
        val contents = vault.create(dev.azwall.core.pxe.AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT))
        val launcher = PxeLauncher { object : RunningPxe { override val client = PxeClient(pxe); override val events = emptyFlow<PxeEvent>(); override fun stop() {} } }
        // The session runs endless collectors: a scope on the test scheduler (advanceUntilIdle runs it), cancelled after.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        return WalletSession(FakeBackend, launcher, vault, contents, tmp.root, network, scope)
    }

    @Test
    fun openRegistersAccountsAndSelectsTheFirst() = runTest {
        val s = session().apply { open() }
        val state = s.state.value
        assertEquals(Phase.READY, state.phase)
        assertEquals(10L, state.check?.blockNumber)
        assertEquals("addr-abab", state.selected)
        assertEquals(false, state.account?.deployed)
    }

    @Test
    fun openFailuresAreReportedInTheState() = runTest {
        pxe.failOpen = "network testnet-v6 is not compatible"
        val state = session().apply { open() }.state.value
        assertEquals(Phase.FAILED, state.phase)
        assertEquals("open: network testnet-v6 is not compatible", state.error)
    }

    @Test
    fun tokensAndBalancesAreFollowedOnceDeployed() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.addToken("0xt")
        val token = s.state.value.tokens.single()
        assertEquals("TST", token.info.symbol)
        assertEquals(BigInteger("2500000"), token.balance?.privateAmount)
    }

    @Test
    fun anAccountNotActivatedYetShowsWhatItReceived() = runTest {
        val s = session().apply { open() }
        s.addToken("0xt")
        assertEquals(false, s.state.value.account?.deployed)
        assertEquals(BigInteger("2500000"), s.state.value.tokens.single().balance?.privateAmount)
    }

    @Test
    fun aCancelledTransactionIsNeverSent() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        pxe.whileProving = { s.transactions.cancel() }
        val token = TokenInfo("0xt", "Test", "TST", 6)
        val error = runCatching { s.transactions.send(TransferKind.PRIVATE, token, "0x" + "cd".repeat(32), BigInteger.ONE) }.exceptionOrNull()
        assertTrue(error is CancelledByUser)
        assertTrue(pxe.sent.isEmpty())
        assertEquals("Cancelled", s.state.value.activity.first().error)
        advanceUntilIdle()
        assertEquals(false, ProofGate.cancelled)
    }

    @Test
    fun cancellingReleasesTheScreenWithoutWaitingForTheProof() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        val proof = CompletableDeferred<Unit>()
        pxe.whileProving = { s.transactions.cancel(); proof.await() }
        val token = TokenInfo("0xt", "Test", "TST", 6)
        val error = runCatching { s.transactions.send(TransferKind.PRIVATE, token, "0x" + "cd".repeat(32), BigInteger.ONE) }.exceptionOrNull()
        assertTrue(error is CancelledByUser)
        assertEquals(null, s.transactions.proving.value)
        assertEquals(true, ProofGate.cancelled)
        proof.complete(Unit)
        advanceUntilIdle()
        assertTrue(pxe.sent.isEmpty())
        assertEquals(false, ProofGate.cancelled)
    }

    @Test
    fun aNetworkResetSetsTheOldChainsTokensAndHistoryAside() = runTest {
        session().apply { open(); addToken("0xpal"); close() }
        pxe.rollup = 3
        val s = session().apply { open() }
        assertTrue(s.state.value.chainReset)
        assertTrue(s.state.value.tokens.none { it.info.address == "0xpal" })
        assertTrue(tmp.root.resolve("wallet-testnet-v6-1-2.json").exists())
        s.acknowledgeChainReset()
        assertFalse(s.state.value.chainReset)
    }

    @Test
    fun theSameChainKeepsEverything() = runTest {
        session().apply { open(); addToken("0xpal"); close() }
        val s = session().apply { open() }
        assertFalse(s.state.value.chainReset)
        assertTrue(s.state.value.tokens.any { it.info.address == "0xpal" })
    }

    @Test
    fun withoutASponsorTheAccountsFeeJuiceIsRead() = runTest {
        pxe.fees = "feeJuice"
        val s = session().apply { open() }
        s.refresh()
        assertEquals("1500000000000000000", s.state.value.feeJuice)
        assertEquals("Paid with your Fee Juice (you hold 1.5 FJ)", Fees.line(s.state.value.check, s.state.value.feeJuice))
    }

    @Test
    fun anAccountIsActivatedOnce() = runTest {
        val s = session().apply { open() }
        s.transactions.deployAccount(null)
        assertTrue(s.state.value.activating)
        val again = runCatching { s.transactions.deployAccount(null) }.exceptionOrNull()
        assertTrue(again?.message!!.contains("already on its way"))
        assertEquals(1, pxe.calls.count { it == "deployAccount" })
    }

    @Test
    fun bridgedFeeJuiceIsClaimedByTheActivationItself() = runTest {
        pxe.deposits = """[{"amount":"5000000000000000000","messageHash":"0xm1","leafIndex":"7","ours":true,"ready":true,"claimed":false,"secret":"0x0000000000000000000000000000000000000000000000000000000000000000"},
            {"amount":"3","messageHash":"0xm2","leafIndex":"8","ours":true,"ready":false,"claimed":false},
            {"amount":"9","messageHash":"0xm3","leafIndex":"9","ours":false,"ready":true,"claimed":false}]"""
        val s = session().apply { open() }
        val state = s.state.value
        assertEquals("7", state.claimable?.leafIndex)
        assertEquals(BigInteger("3"), state.arriving)
        assertTrue(Fees.activation(state.check, state.feeJuice, state.claimable).contains("5 FJ bridged from Ethereum are ready"))
        s.transactions.deployAccount(state.claimable?.messageHash)
        val claim = pxe.sent.single()["claim"]!!.jsonObject
        assertEquals("5000000000000000000", claim["amount"]!!.jsonPrimitive.content)
        assertEquals("7", claim["leafIndex"]!!.jsonPrimitive.content)
        assertEquals("0x" + "0".repeat(64), claim["secret"]!!.jsonPrimitive.content)
    }

    @Test
    fun anActivationClaimsWhatItsReviewShowedOrNothing() = runTest {
        val ready = """{"amount":"5","messageHash":"0xm1","leafIndex":"7","ours":true,"ready":true,"secret":"0x01","claimed":"""
        pxe.deposits = "[${ready}false}]"
        val s = session().apply { open() }
        // Reviewed with the deposit, which a refresh then reads as claimed (from elsewhere): refused, nothing proven.
        pxe.deposits = "[${ready}true}]"
        s.refresh()
        val refused = runCatching { s.transactions.deployAccount("0xm1") }.exceptionOrNull()
        assertTrue(refused?.message!!.contains("review the activation again"))
        assertEquals(0, pxe.calls.count { it == "deployAccount" })
        // Reviewed without one (the sponsor pays), and one became claimable since: still none claimed.
        pxe.deposits = "[${ready}false}]"
        s.refresh()
        s.transactions.deployAccount(null)
        assertNull(pxe.sent.single()["claim"])
    }

    @Test
    fun aClaimSecretTheUserEntersIsKeptAndTriedOnTheDeposits() = runTest {
        val secret = "0x" + "0".repeat(62) + "2a"
        pxe.deposits = """[{"amount":"9","messageHash":"0xm3","leafIndex":"9","ours":false,"ready":false,"claimed":false}]"""
        pxe.unlockedBy = secret to """[{"amount":"9","messageHash":"0xm3","leafIndex":"9","ours":true,"ready":true,"claimed":false,"secret":"$secret"}]"""
        val s = session().apply { open() }
        assertEquals(1, s.state.value.locked.size)
        // A typo opens nothing: said so, and not kept.
        val wrong = runCatching { s.addClaimSecret("0x2b") }.exceptionOrNull()
        assertTrue(wrong?.message!!.contains("matches none of the deposits"))
        assertTrue(runCatching { s.addClaimSecret("hello") }.isFailure)
        s.addClaimSecret(" 0x2A \n")
        assertEquals("9", s.state.value.claimable?.amount)
        // Kept: the next start tries it again, and only it.
        s.refresh()
        assertEquals(listOf(secret), pxe.depositQueries.last())
    }

    @Test
    fun withoutFeeJuiceNorADepositNoActivationIsProven() = runTest {
        pxe.fees = "feeJuice"
        pxe.feeJuice = "0"
        val s = session().apply { open() }
        val state = s.state.value
        assertFalse(Fees.canActivate(state.check, state.feeJuice, state.claimable))
        val refused = runCatching { s.transactions.deployAccount(null) }.exceptionOrNull()
        assertTrue(refused?.message!!.contains("holds no Fee Juice"))
        assertEquals(0, pxe.calls.count { it == "deployAccount" })
    }

    @Test
    fun aDepositOnItsWayIsWatchedUntilItCanBeClaimed() = runTest {
        pxe.fees = "feeJuice"
        pxe.feeJuice = "0"
        val deposit = """{"amount":"5000000000000000000","messageHash":"0xm1","leafIndex":"7","ours":true,"claimed":false,"secret":"0x01","ready":"""
        pxe.deposits = "[${deposit}false}]"
        val s = session().apply { open() }
        assertEquals(BigInteger("5000000000000000000"), s.state.value.arriving)
        assertFalse(Fees.canActivate(s.state.value.check, s.state.value.feeJuice, s.state.value.claimable))
        // The message lands on Aztec: no pull to refresh, the next watch read sees it.
        pxe.deposits = "[${deposit}true}]"
        advanceUntilIdle()
        val state = s.state.value
        assertEquals("7", state.claimable?.leafIndex)
        assertTrue(Fees.canActivate(state.check, state.feeJuice, state.claimable))
    }

    @Test
    fun aDepositOnItsWayIsWatchedForTheAccountSwitchedTo() = runTest {
        val deposit = """{"amount":"5","messageHash":"0xm1","leafIndex":"7","ours":true,"claimed":false,"secret":"0x01","ready":"""
        pxe.deposits = "[${deposit}false}]"
        val s = session().apply { open() }
        // The first account's watcher sleeps; the second one has a deposit on its way too.
        val second = s.addAccount("second", "0x" + "12".repeat(32))
        runCurrent() // the second account's refresh, before any watcher tick: its deposit is still on its way
        assertEquals("5", s.state.value.arriving.toString())
        pxe.deposits = "[${deposit}true}]"
        advanceUntilIdle()
        assertEquals(second.address, s.state.value.selected)
        assertEquals("7", s.state.value.claimable?.leafIndex)
    }

    @Test
    fun aDepositThatNeverArrivesIsNotWatchedForever() = runTest {
        pxe.deposits = """[{"amount":"5","messageHash":"0xm1","leafIndex":"7","ours":true,"ready":false,"claimed":false,"secret":"0x01"}]"""
        session().apply { open() }
        val before = pxe.calls.count { it == "feeJuiceDeposits" }
        advanceUntilIdle()
        assertEquals(before + 40, pxe.calls.count { it == "feeJuiceDeposits" })
    }

    @Test
    fun aClaimOnItsWayIsNotSentTwice() = runTest {
        pxe.deposits = """[{"amount":"5000000000000000000","messageHash":"0xm1","leafIndex":"4","ours":true,"ready":true,"claimed":false,"secret":"0x01"}]"""
        pxe.deployed = true
        val s = session().apply { open() }
        // Sent, not in a block yet: the deposit still reads as unclaimed.
        s.recordActivity(ActivityItem("c1", s.state.value.selected!!, ActivityKind.CLAIM_FEE_JUICE, ActivityStatus.PENDING, 0L))
        assertTrue(s.state.value.claiming)
        val refused = runCatching { s.transactions.claimFeeJuice(s.state.value.claimable!!) }.exceptionOrNull()
        assertTrue(refused?.message!!.contains("already on its way"))
        assertEquals(0, pxe.calls.count { it == "claimFeeJuice" })
    }

    @Test
    fun withoutABridgeDepositTheActivationPaysAsBefore() = runTest {
        val s = session().apply { open() }
        assertNull(s.state.value.claimable)
        s.transactions.deployAccount(null)
        assertNull(pxe.sent.single()["claim"])
    }

    @Test
    fun anActiveAccountClaimsItsBridgedFeeJuice() = runTest {
        pxe.deposits = """[{"amount":"2000000000000000000","messageHash":"0xm1","leafIndex":"4","ours":true,"ready":true,"claimed":false,"secret":"0x0000000000000000000000000000000000000000000000000000000000000000"}]"""
        val s = session().apply { open() }
        val deposit = s.state.value.claimable!!
        // Not active yet: activation is what claims it.
        assertTrue(runCatching { s.transactions.claimFeeJuice(deposit) }.exceptionOrNull()!!.message!!.contains("activate the account first"))
        pxe.deployed = true
        s.refresh()
        val item = s.transactions.claimFeeJuice(deposit)
        assertEquals(ActivityKind.CLAIM_FEE_JUICE, item.kind)
        assertEquals("FJ", item.symbol)
        assertEquals("4", pxe.sent.single()["claim"]!!.jsonObject["leafIndex"]!!.jsonPrimitive.content)
        assertEquals("addr-abab", pxe.sent.single()["owner"]!!.jsonPrimitive.content)
    }

    @Test
    fun noWalletTransactionStartsWhileADappOneIsProven() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.recordActivity(ActivityItem("d", s.state.value.selected!!, ActivityKind.DAPP, ActivityStatus.PROVING, 0))
        val token = TokenInfo("0xt", "Test", "TST", 6)
        val error = runCatching { s.transactions.send(TransferKind.PRIVATE, token, "0x" + "cd".repeat(32), BigInteger.ONE) }.exceptionOrNull()
        assertTrue(error?.message!!.contains("dApp transaction is being proven"))
        assertTrue(pxe.sent.isEmpty())
    }

    @Test
    fun anApprovedDappTransactionWaitsForACancelledOneToWindDown() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        val proof = CompletableDeferred<Unit>()
        pxe.whileProving = { s.transactions.cancel(); proof.await() }
        runCatching { s.transactions.send(TransferKind.PRIVATE, TokenInfo("0xt", "Test", "TST", 6), "0x" + "cd".repeat(32), BigInteger.ONE) }
        val settled = async { s.transactions.settle() }
        advanceUntilIdle()
        assertFalse(settled.isCompleted)
        proof.complete(Unit)
        advanceUntilIdle()
        assertTrue(settled.isCompleted)
        assertEquals(false, ProofGate.cancelled)
    }

    @Test
    fun goingToTheBackgroundFlushesThePxeStore() = runTest {
        val s = session().apply { open() }
        s.flush()
        assertEquals("flush", pxe.calls.last())
    }

    @Test
    fun aHostThatStartsAfterCloseIsStopped() = runTest {
        val started = CompletableDeferred<Unit>()
        var stopped = false
        val vault = Vault(tmp.root.resolve("vault"), PlainBox)
        val contents = vault.create(dev.azwall.core.pxe.AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT))
        val launcher = PxeLauncher {
            started.await()
            object : RunningPxe { override val client = PxeClient(pxe); override val events = emptyFlow<PxeEvent>(); override fun stop() { stopped = true } }
        }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val s = WalletSession(FakeBackend, launcher, vault, contents, tmp.root, network, scope)
        val opening = launch { s.open() }
        advanceUntilIdle()
        s.close() // locked while the PXE host starts
        started.complete(Unit)
        opening.join()
        assertTrue("the late host is stopped", stopped)
        assertTrue("nothing was opened on it", pxe.calls.isEmpty())
    }

    @Test
    fun aRefreshOfThePreviousAccountDoesNotShowUnderTheNewOne() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.addToken("0xt")
        val second = s.addAccount("second", "0x" + "12".repeat(32))
        assertNull("a new account starts without the previous one's balances", s.state.value.tokens.single().balance)
        s.select("addr-abab")
        advanceUntilIdle()
        pxe.hold = CompletableDeferred()
        launch { s.refresh() } // waits for the first account's balances
        advanceUntilIdle()
        s.select(second.address)
        pxe.hold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(second.address, s.state.value.selected)
        assertEquals(BigInteger("7000000"), s.state.value.tokens.single().balance?.privateAmount)
    }

    @Test
    fun transactionsGoFromProvingToConfirmed() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        val token = TokenInfo("0xt", "Test", "TST", 6)
        val sent = s.transactions.send(TransferKind.PRIVATE, token, " addr-x ", BigInteger("1000000"))
        assertEquals(ActivityStatus.PENDING, sent.status)
        assertEquals("addr-x", pxe.sent.single()["to"]!!.jsonPrimitive.content)
        assertNull(s.transactions.proving.value)
        pxe.mined.complete(Unit)
        advanceUntilIdle()
        val item = s.state.value.activity.single()
        assertEquals(ActivityStatus.CONFIRMED, item.status)
        assertEquals(11L, item.blockNumber)
    }

    @Test
    fun shieldingSendsToTheSenderAndAccountsCanBeAdded() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.transactions.send(TransferKind.SHIELD, TokenInfo("0xt", "Test", "TST", 6), "", BigInteger.TEN)
        assertEquals("addr-abab", pxe.sent.single()["to"]!!.jsonPrimitive.content)
        val added = s.addAccount("Savings", importedKey = "0x" + "12".repeat(32))
        assertEquals("addr-1212", added.address)
        assertEquals(listOf("main", "Savings"), s.state.value.accounts.map { it.alias })
        assertTrue(s.state.value.selected == added.address)
        pxe.mined.complete(Unit) // let the background confirmation finish
    }

    @Test
    fun anInterruptedWaitAsksTheNodeInsteadOfFailing() = runTest {
        pxe.deployed = true
        pxe.waitError = "waitForTx: the PXE host was stopped"
        val s = session().apply { open() }
        s.transactions.send(TransferKind.UNSHIELD, TokenInfo("0xt", "Test", "TST", 6), "", BigInteger("1"))
        advanceUntilIdle()
        val item = s.state.value.activity.single()
        assertEquals(ActivityStatus.CONFIRMED, item.status)
        assertEquals(12L, item.blockNumber)
    }

    @Test
    fun transactionsMarkedFailedWithoutAReceiptAreRepairedOnOpen() = runTest {
        WalletStore(tmp.root, network.id).upsert(
            ActivityItem("old", "addr-abab", ActivityKind.UNSHIELD, ActivityStatus.FAILED, 1, txHash = "0xold", error = "waitForTx: the PXE host was stopped"),
        )
        pxe.deployed = true
        pxe.mined.complete(Unit)
        val s = session().apply { open() }
        advanceUntilIdle()
        assertEquals(ActivityStatus.CONFIRMED, s.state.value.activity.single().status)
    }

    @Test
    fun revertedTransactionsStayFailed() = runTest {
        pxe.deployed = true
        pxe.waitError = "reverted"
        pxe.receiptOutcome = "reverted"
        val s = session().apply { open() }
        s.transactions.send(TransferKind.PRIVATE, TokenInfo("0xt", "Test", "TST", 6), "addr-x", BigInteger("1"))
        advanceUntilIdle()
        val item = s.state.value.activity.single()
        assertEquals(ActivityStatus.FAILED, item.status)
        assertEquals(Transactions.REVERTED, item.error)
        assertTrue(WalletStore(tmp.root, network.id).unsettled().isEmpty())
    }

    @Test
    fun tokensFromDappsAreSuggestedAndFollowedInOneTap() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        val found = s.state.value.found.single()
        assertEquals("PUSD", found.symbol)
        s.followFound(found)
        assertTrue(s.state.value.found.isEmpty())
        assertEquals(BigInteger("5000000"), s.state.value.tokens.single().balance?.publicAmount)
        assertEquals(listOf("0xpal"), WalletStore(tmp.root, network.id).tokens.map { it.address })
    }

    @Test
    fun dismissedTokensAreNotSuggestedAgain() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.dismissFound(s.state.value.found.single())
        s.select("addr-abab") // resets the discovery throttle
        advanceUntilIdle()
        assertTrue(s.state.value.found.isEmpty())
        assertTrue("0xpal" in pxe.discoveryKnown.last())
    }

    @Test
    fun theHeroIsTheChosenTokenElseFeeJuice() = runTest {
        val empty = TokenView(TokenInfo("0xa", "A", "A", 6), TokenBalance("0xa", "0", "0"))
        val held = TokenView(TokenInfo("0xb", "B", "B", 18), TokenBalance("0xb", "0", "7400000000000000"))
        val state = SessionState(network, tokens = listOf(empty, held), feeJuice = "5")
        assertEquals(FeeJuice.ID, state.hero.info.address)
        assertEquals("5", state.hero.balance?.public)
        assertEquals("0xa", state.copy(primary = "0xA").hero.info.address)
        assertEquals(FeeJuice.ID, state.copy(primary = "0xgone").hero.info.address)
        assertEquals(listOf(FeeJuice.ID, "0xa", "0xb"), state.shown.map { it.info.address })
        // Before it is read, as for a new account: 0, never a spinner.
        assertEquals("0", SessionState(network).hero.balance?.public)
    }

    @Test
    fun feeJuiceIsReadOnSponsoredNetworksToo() = runTest {
        val s = session().apply { open() }
        s.refresh()
        assertEquals(pxe.feeJuice, s.state.value.feeJuice)
        assertEquals(Fees.SPONSORED, Fees.line(s.state.value.check, s.state.value.feeJuice))
    }

    @Test
    fun theChosenHeroIsKeptPerAccount() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.choosePrimary("0xt")
        assertEquals("0xt", s.state.value.primary)
        assertEquals("0xt", WalletStore(tmp.root, network.id).primary("addr-abab"))
    }

    @Test
    fun createdTokensAreRememberedAndMintedToAnyone() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        val token = TokenInfo("0xt", "Test", "TST", 6)
        s.store.markCreated("addr-abab", "0xt")
        s.refresh()
        assertEquals(listOf("0xt"), s.state.value.created)
        s.transactions.mint(token, BigInteger("5"), toPrivate = true, to = " addr-friend ")
        val minted = pxe.minted.single()
        assertEquals("addr-friend", minted["to"]!!.jsonPrimitive.content)
        assertEquals("addr-friend", s.state.value.activity.first().counterparty)
    }

    @Test
    fun lastBalancesShowAtOnceAndStayStaleUntilARefresh() = runTest {
        pxe.deployed = true
        val first = session().apply { open() }
        first.addToken("0xt")
        // A new start, before the PXE answers: the snapshot of the last refresh, marked stale.
        val vault = Vault(tmp.root.resolve("vault"), PlainBox)
        val contents = vault.unlock()
        val restored = Snapshots.restore(SessionState(network), contents, WalletStore(tmp.root, network.id))!!
        assertTrue(restored.stale)
        assertEquals("addr-abab", restored.selected)
        assertEquals(true, restored.account?.deployed)
        assertEquals(BigInteger("2500000"), restored.tokens.single().balance?.privateAmount)
        assertEquals(pxe.feeJuice, restored.feeJuice)
        assertEquals(false, first.state.value.stale)
    }

    @Test
    fun anAccountShowsAtTheNextStartEvenBeforeItsFirstRefresh() = runTest {
        // A first start: the PXE gives the account's address, and the refresh fails before any balance is read.
        pxe.failBalances = true
        session().apply { open() }
        val contents = Vault(tmp.root.resolve("vault"), PlainBox).unlock()
        val restored = Snapshots.restore(SessionState(network), contents, WalletStore(tmp.root, network.id))!!
        assertEquals("addr-abab", restored.account?.address)
        assertTrue(restored.stale)
    }
}
