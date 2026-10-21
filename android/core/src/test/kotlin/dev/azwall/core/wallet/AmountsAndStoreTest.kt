package dev.azwall.core.wallet

import dev.azwall.core.pxe.TokenInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger

class AmountsTest {
    @Test
    fun formatsWithGroupingAndTrimmedDecimals() {
        assertEquals("1,234.5", Amounts.format(BigInteger("1234500000"), 6))
        assertEquals("0", Amounts.format(BigInteger.ZERO, 18))
        assertEquals("0.000001", Amounts.format(BigInteger.ONE, 6))
        assertEquals("1.2345", Amounts.format(BigInteger("1234567"), 6, maxFraction = 4))
        assertEquals("123,456,789,012,345,678", Amounts.format(BigInteger("123456789012345678"), 0))
    }

    @Test
    fun dustNeverShowsAsZero() {
        // 0.0074 WETH split as 0.0070 private and 0.0004 public: the public part is dust at two decimals.
        assertEquals("< 0.01", Amounts.display(BigInteger("400000000000000"), 18, 2))
        assertEquals("0.0004", Amounts.display(BigInteger("400000000000000"), 18, 4))
        assertEquals("0", Amounts.display(BigInteger.ZERO, 18, 2))
        assertEquals("1.5", Amounts.display(BigInteger("1500000"), 6, 2))
    }

    @Test
    fun parsesHumanInput() {
        assertEquals(BigInteger("1500000"), Amounts.parse("1.5", 6))
        assertEquals(BigInteger("1500000"), Amounts.parse(" 1,5 ", 6))
        assertEquals(BigInteger("500000"), Amounts.parse(".5", 6))
        assertEquals(BigInteger("10").pow(24), Amounts.parse("1000000", 18))
    }

    @Test
    fun rejectsInvalidAmounts() {
        listOf("", "abc", "-1", "0", "0.0", "1.1234567", "1e5").forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { Amounts.parse(input, 6) }
        }
    }
}

class WalletStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val token = TokenInfo("0xAAA", "Test", "TST", 6)

    private fun item(id: String, at: Long, status: ActivityStatus = ActivityStatus.PENDING) =
        ActivityItem(id, "0xme", ActivityKind.SEND_PRIVATE, status, createdAt = at)

    @Test
    fun tokensPersistPerNetworkWithoutDuplicates() {
        WalletStore(tmp.root, "testnet-v6").apply { addToken(token); addToken(token.copy(address = "0xaaa")) }
        assertEquals(listOf(token), WalletStore(tmp.root, "testnet-v6").tokens)
        assertEquals(emptyList<TokenInfo>(), WalletStore(tmp.root, "testnet-v5").tokens)
        WalletStore(tmp.root, "testnet-v6").removeToken("0xaaa")
        assertEquals(emptyList<TokenInfo>(), WalletStore(tmp.root, "testnet-v6").tokens)
    }

    @Test
    fun activityIsUpsertedNewestFirstPerAccount() {
        val store = WalletStore(tmp.root, "n")
        store.upsert(item("a", 1))
        store.upsert(item("b", 2))
        store.upsert(item("a", 1, ActivityStatus.CONFIRMED))
        store.upsert(item("c", 3).copy(account = "0xother"))
        val mine = WalletStore(tmp.root, "n").activity("0xme")
        assertEquals(listOf("b", "a"), mine.map { it.id })
        assertEquals(ActivityStatus.CONFIRMED, mine.last().status)
    }

    @Test
    fun interruptedProofsAreMarkedFailedAndHistoryIsCapped() {
        val store = WalletStore(tmp.root, "n")
        (1..WalletStore.MAX_ACTIVITY + 5).forEach { store.upsert(item("i$it", it.toLong())) }
        store.upsert(item("p", 9999, ActivityStatus.PROVING))
        store.failInterrupted()
        val all = store.activity("0xme")
        assertEquals(WalletStore.MAX_ACTIVITY, all.size)
        assertEquals(ActivityStatus.FAILED, all.first().status)
    }

    @Test
    fun aCorruptFileStartsEmpty() {
        tmp.root.resolve("wallet-n.json").writeText("{not json")
        assertEquals(emptyList<TokenInfo>(), WalletStore(tmp.root, "n").tokens)
    }

    @Test
    fun knownTokensAreFollowedOnceAndStayRemoved() {
        val store = WalletStore(tmp.root, "n")
        val weth = TokenInfo("0xWETH", "Wrapped Ether", "WETH", 18)
        store.seed(listOf(weth))
        assertEquals(listOf(weth), store.tokens)
        store.removeToken("0xweth")
        store.seed(listOf(weth))
        assertTrue(store.tokens.isEmpty())
    }
}
