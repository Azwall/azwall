import Foundation
import Testing

@testable import AzwallKit

/// Waits (up to 2 s) for work the session runs in the background: confirmations, event handling.
@MainActor
func eventually(_ condition: () -> Bool) async {
    for _ in 0..<200 where !condition() { try? await Task.sleep(for: .milliseconds(10)) }
}

/// Settlement from receipts, discovery, history and dApp transactions (the Android core's WalletSessionTest and
/// DappsTest, on the Swift session).
@MainActor
struct FeatureTests {
    let base = SessionTests()
    let token = TokenInfo(address: "0xt", name: "Test", symbol: "TST", decimals: 6)

    @Test func anInterruptedWaitAsksTheNodeInsteadOfFailing() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        pxe.waitError = "waitForTx: the PXE host was stopped"
        let s = try base.session(pxe)
        await s.open()
        try await s.send(.unshield, token: token, to: "", amount: "1")
        await eventually { s.activity.first?.status == .confirmed }
        #expect(s.activity.first?.status == .confirmed)
        #expect(s.activity.first?.blockNumber == 12)
    }

    @Test func transactionsMarkedFailedWithoutAReceiptAreRepairedOnOpen() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        var old = ActivityItem(id: "old", account: "addr-abab", kind: .unshield, status: .failed, createdAt: Date(timeIntervalSince1970: 1))
        old.txHash = "0xold"
        old.error = "waitForTx: the PXE host was stopped"
        WalletStore(directory: dir, networkId: "testnet-v6").upsert(old)
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try base.session(pxe, dir: dir)
        await s.open()
        await eventually { s.activity.first?.status == .confirmed }
        #expect(s.activity.first?.status == .confirmed)
    }

    @Test func revertedTransactionsStayFailed() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        pxe.waitError = "reverted"
        pxe.receiptOutcome = "reverted"
        let s = try base.session(pxe)
        await s.open()
        try await s.send(.private, token: token, to: "addr-x", amount: "1")
        await eventually { s.activity.first?.status == .failed }
        #expect(s.activity.first?.error == Settlement.reverted)
        #expect(s.store.unsettled().isEmpty)
    }

    @Test func tokensFromDappsAreSuggestedAndFollowedInOneTap() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try base.session(pxe)
        await s.open()
        let found = try #require(s.found.first)
        #expect(found.symbol == "PUSD")
        s.followFound(found)
        #expect(s.found.isEmpty)
        #expect(s.tokens.first { $0.info.address == "0xpal" }?.balance?.public == "5000000")
        #expect(s.store.tokens.map(\.address) == ["0xpal"])
    }

    @Test func dismissedTokensAreNotSuggestedAgain() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try base.session(pxe)
        await s.open()
        s.dismissFound(try #require(s.found.first))
        await s.select("addr-abab") // resets the discovery throttle
        #expect(s.found.isEmpty)
        let known = pxe.calls.last { $0.0 == "discoverTokens" }?.1["known"]?.arrayValue?.compactMap(\.stringValue)
        #expect(known?.contains("0xpal") == true)
    }

    @Test func dappTransactionsLandInTheActivity() async throws {
        let pxe = FakePxe()
        let s = try base.session(pxe)
        await s.open()
        let tx = #""id":"t1","account":"addr-abab","origin":"https://vote.example","summary":"cast_vote on 0xab""#
        pxe.emit.yield(PxeEvent(name: "dappTx", data: try JSONValue.parse("{\(tx),\"status\":\"proving\"}")))
        await eventually { s.activity.first?.status == .proving }
        pxe.emit.yield(PxeEvent(name: "dappTx", data: try JSONValue.parse("{\(tx),\"status\":\"sent\",\"txHash\":\"0xhash\"}")))
        await eventually { s.activity.first?.status == .confirmed }
        let item = try #require(s.activity.first)
        #expect(item.kind == .dapp)
        #expect(item.summary == "cast_vote on 0xab")
        #expect(item.origin == "https://vote.example")
        #expect(item.blockNumber == 11)
    }

    @Test func gatewayRepliesAreRoutedPerTabAndSessionsFollowed() async throws {
        let pxe = FakePxe()
        let s = try base.session(pxe)
        await s.open()
        var tab1 = s.dapps.toPage(1).makeAsyncIterator()
        pxe.emit.yield(PxeEvent(name: "dapp", data: try JSONValue.parse(#"{"tabId":2,"message":{"x":2}}"#)))
        pxe.emit.yield(PxeEvent(name: "dapp", data: try JSONValue.parse(#"{"tabId":1,"message":{"x":1}}"#)))
        pxe.emit.yield(PxeEvent(name: "dappSessions", data: try JSONValue.parse(
            #"[{"sessionId":"s1","origin":"https://vote.example","appId":"vote","emojis":"🦊","granted":true}]"#,
        )))
        let first = await tab1.next()
        #expect(try JSONValue.parse(first ?? "") == JSONValue.parse(#"{"x":1}"#))
        await eventually { !s.dapps.sessions.isEmpty }
        #expect(s.dapps.sessions.first?.origin == "https://vote.example")
        await s.dapps.fromPage(1, origin: "https://vote.example", message: #"{"type":"aztec-wallet-discovery"}"#)
        let call = try #require(pxe.calls.last)
        #expect(call.0 == "dappMessage")
        #expect(call.1["account"]?.stringValue == "addr-abab")
    }
}

@MainActor
struct HeroTests {
    @Test func theHeroIsTheChosenTokenElseTheFirstHoldingSomething() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try SessionTests().session(pxe)
        await s.open()
        try await s.addToken("0xt")
        #expect(s.hero?.info.address == "0xt")
        s.choosePrimary("0xT")
        #expect(s.hero?.info.address == "0xt")
        #expect(s.store.primary(account: "addr-abab") == "0xT")
    }
}

struct KnownTokenTests {
    @Test func knownTokensAreFollowedOnceAndStayRemoved() {
        let store = WalletStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), networkId: "n")
        let weth = TokenInfo(address: "0xWETH", name: "Wrapped Ether", symbol: "WETH", decimals: 18)
        store.seed([weth])
        #expect(store.tokens == [weth])
        store.removeToken("0xweth")
        store.seed([weth])
        #expect(store.tokens.isEmpty)
    }
}

@MainActor
struct ApprovalTests {
    @Test func approveWaitsForTheUserDecision() async throws {
        let approvals = DappApprovals()
        let params = try JSONValue.parse(#"{"kind":"capabilities","origin":"https://vote.example","title":"Vote asks","details":["accounts"],"emojis":"🦊🌵"}"#)
        let reply = Task { try await approvals.approve(params) }
        await eventually { !approvals.pending.isEmpty }
        let request = try #require(approvals.pending.first)
        #expect(request.kind == .capabilities)
        #expect(request.emojis == "🦊🌵")
        approvals.decide(request.id, approved: true)
        #expect(try await reply.value["approved"] == .bool(true))
        #expect(approvals.pending.isEmpty)
    }

    @Test func rejectAllAnswersNo() async {
        let approvals = DappApprovals()
        let answer = Task { await approvals.ask(.transaction, origin: "https://a", title: "Send?", details: [], emojis: nil) }
        await eventually { !approvals.pending.isEmpty }
        approvals.rejectAll()
        #expect(await answer.value == false)
    }
}

struct HistoryAndStatusTests {
    let pusd = TokenInfo(address: "0xpusd", name: "Palmyr USD", symbol: "PUSD", decimals: 6)

    func move(_ from: String, _ to: String, tx: String = "0xtx") -> TokenMove {
        TokenMove(token: "0xPUSD", txHash: tx, block: 42, at: 1_000, from: from, to: to, amount: "100")
    }

    @Test func transfersBecomeItemsWithTheirBlock() {
        let items = History.items(owner: "0xme", moves: [move("0xalice", "0xme", tx: "0x1"), move("0xme", "0xbob", tx: "0x2"), move("0x00", "0xme", tx: "0x3")],
                                  tokens: [pusd], known: [])
        #expect(items.map(\.kind) == [.receive, .sendPrivate, .mint])
        #expect(items[0].counterparty == "0xalice")
        #expect(items[0].blockNumber == 42)
        #expect(items[2].counterparty == nil)
    }

    @Test func recordedTransactionsAndChangeAreSkipped() {
        var recorded = ActivityItem(id: "a", account: "0xme", kind: .dapp, status: .confirmed)
        recorded.txHash = "0xTX"
        #expect(History.items(owner: "0xme", moves: [move("0xalice", "0xme")], tokens: [pusd], known: [recorded]).isEmpty)
        #expect(History.items(owner: "0xme", moves: [move("0xme", "0xme", tx: "0x9")], tokens: [pusd], known: []).isEmpty)
    }

    @Test func statusTextSaysWhatRuns() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let late = SyncStatus(syncedBlock: 7_341, syncedAt: 1_000_000 - 36 * 3600, tip: 8_581)
        #expect(StatusText.text(phase: .ready, task: nil, activity: [], now: now) == nil)
        #expect(StatusText.text(phase: .ready, task: .syncing(from: late), activity: [], now: now) == "Syncing PXE · 1,240 blocks behind (36 h)")
        #expect(StatusText.text(phase: .ready, task: .syncing(from: SyncStatus(syncedBlock: 0, syncedAt: 0, tip: 8_581)), activity: [], now: now)
            == "Syncing PXE · 8,581 blocks behind")
        let pending = ActivityItem(account: "0xme", kind: .mint, status: .pending)
        #expect(StatusText.text(phase: .ready, task: nil, activity: [pending, pending], now: now) == "Waiting for 2 transactions to be mined…")
    }
}
