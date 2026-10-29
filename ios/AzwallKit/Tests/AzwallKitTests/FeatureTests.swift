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

    /// 2026-10-28 store-release audit (F7, F8): a dApp send that failed once the node had it, or whose result carries no
    /// hash, keeps the hash told before sending and is followed to its receipt.
    @Test func aDappTransactionKeepsTheHashToldBeforeSending() async throws {
        let pxe = FakePxe()
        let s = try base.session(pxe)
        await s.open()
        let tx = #""id":"t5","account":"addr-abab","origin":"https://vote.example","summary":"vote""#
        pxe.emit.yield(PxeEvent(name: "dappTx", data: try JSONValue.parse("{\(tx),\"status\":\"proving\"}")))
        await eventually { s.activity.first?.status == .proving }
        pxe.emit.yield(PxeEvent(name: "txSubmitting", data: try JSONValue.parse(#"{"from":"addr-abab","txHash":"0xtold"}"#)))
        await eventually { s.activity.first?.txHash == "0xtold" }
        pxe.emit.yield(PxeEvent(name: "dappTx", data: try JSONValue.parse("{\(tx),\"status\":\"failed\",\"error\":\"wait timed out\"}")))
        await eventually { s.activity.first?.status == .confirmed }
        #expect(s.activity.first?.txHash == "0xtold")
    }

    @Test func aDisconnectTheGatewayFailsIsReportedNotDropped() async throws {
        let pxe = FakePxe()
        let s = try base.session(pxe)
        await s.open()
        pxe.failing = ["dappDisconnect"]
        // The site would stay connected while the list says otherwise: the caller must hear of it.
        await #expect(throws: PxeError.self) { try await s.dapps.disconnect("s1") }
        pxe.failing = []
        try await s.dapps.disconnect("s1")
        #expect(pxe.calls.last { $0.0 == "dappDisconnect" }?.1["sessionId"]?.stringValue == "s1")
    }

    @Test func gatewayRepliesAreRoutedPerTabAndSessionsFollowed() async throws {
        let pxe = FakePxe()
        let s = try base.session(pxe)
        await s.open()
        var pages = s.dapps.toPages().makeAsyncIterator()
        pxe.emit.yield(PxeEvent(name: "dapp", data: try JSONValue.parse(#"{"tabId":2,"message":{"x":2}}"#)))
        pxe.emit.yield(PxeEvent(name: "dapp", data: try JSONValue.parse(#"{"tabId":1,"message":{"x":1}}"#)))
        pxe.emit.yield(PxeEvent(name: "dappSessions", data: try JSONValue.parse(
            #"[{"sessionId":"s1","origin":"https://vote.example","appId":"vote","emojis":"🦊","granted":true}]"#,
        )))
        let first = await pages.next(), second = await pages.next()
        #expect(first?.page == 2)
        #expect(second?.page == 1)
        #expect(try JSONValue.parse(second?.json ?? "") == JSONValue.parse(#"{"x":1}"#))
        await eventually { !s.dapps.sessions.isEmpty }
        #expect(s.dapps.sessions.first?.origin == "https://vote.example")
        await s.dapps.fromPage(1, origin: "https://vote.example", message: #"{"type":"aztec-wallet-discovery"}"#)
        let call = try #require(pxe.calls.last)
        #expect(call.0 == "dappMessage")
        #expect(call.1["account"]?.stringValue == "addr-abab")
        // An encrypted contract artifact (registerContract) weighs megabytes: it must reach the gateway.
        await s.dapps.fromPage(1, origin: "https://vote.example", message: String(repeating: "x", count: 9_000_000))
        #expect(pxe.calls.last?.1["message"]?.stringValue?.utf8.count == 9_000_000)
        let before = pxe.calls.count
        await s.dapps.fromPage(1, origin: "https://x.example", message: String(repeating: "x", count: DappLink.maxMessage + 1))
        #expect(pxe.calls.count == before)
        await s.dapps.closePage(4)
        #expect(pxe.calls.last?.0 == "dappClosePage")
        #expect(pxe.calls.last?.1["tabId"]?.intValue == 4)
    }
}

struct DropGraceTests {
    @Test func aSingleDroppedReadIsBelievedOnlyForAnOldTransaction() {
        let dropped = Receipt(txHash: "0x1", status: "dropped", blockNumber: nil, outcome: .dropped)
        let sent = Date()
        let item = ActivityItem(account: "a", kind: .sendPrivate, status: .pending, createdAt: sent)
        #expect(Settlement.droppedTooSoon(item, dropped, now: sent.addingTimeInterval(60)))
        #expect(!Settlement.droppedTooSoon(item, dropped, now: sent.addingTimeInterval(Settlement.dropGrace + 1)))
        let mined = Receipt(txHash: "0x1", status: "proposed", blockNumber: 3, outcome: .success)
        #expect(!Settlement.droppedTooSoon(item, mined, now: sent))
    }
}

@MainActor
struct HeroTests {
    @Test func theHeroIsTheChosenTokenElseFeeJuice() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try SessionTests().session(pxe)
        await s.open()
        try await s.addToken("0xt")
        #expect(s.hero.info.address == FeeJuice.id)
        #expect(s.hero.balance?.public == "1500000000000000000")
        s.feeJuice = nil
        // Before it is read, as for a new account: 0, never a spinner.
        #expect(s.hero.balance?.public == "0")
        #expect(s.shown.map(\.info.address) == [FeeJuice.id, "0xt"])
        s.choosePrimary("0xT")
        #expect(s.hero.info.address == "0xt")
        #expect(s.store.primary(account: "addr-abab") == "0xT")
        s.choosePrimary("0xgone")
        #expect(s.hero.info.address == FeeJuice.id)
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
struct TokenStudioTests {
    @Test func createdTokensAreRememberedAndMintedToAnyone() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try SessionTests().session(pxe)
        await s.open()
        s.store.markCreated(account: "addr-abab", token: "0xt")
        await s.refresh()
        #expect(s.created == ["0xt"])
        try await s.mint(token: TokenInfo(address: "0xt", name: "Test", symbol: "TST", decimals: 6), amount: "5", toPrivate: true, to: " addr-friend ")
        let call = try #require(pxe.calls.last { $0.0 == "mint" })
        #expect(call.1["to"]?.stringValue == "addr-friend")
        #expect(s.activity.first?.counterparty == "addr-friend")
    }
}

@MainActor
struct SnapshotTests {
    @Test func lastBalancesShowAtOnceAndStayStaleUntilARefresh() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let first = try SessionTests().session(pxe, dir: dir)
        await first.open()
        try await first.addToken("0xt")
        first.choosePrimary("0xt")
        #expect(first.stale == false)
        // A new start, before the PXE answers: the snapshot of the last refresh, marked stale.
        let second = try SessionTests().session(pxe, dir: dir)
        second.restoreSnapshot()
        #expect(second.stale)
        #expect(second.selected == "addr-abab")
        #expect(second.account?.deployed == true)
        #expect(second.tokens.first?.balance?.private == "2500000")
        #expect(second.primary == "0xt", "the top token too, as on Android")
        #expect(second.feeJuice == "1500000000000000000")
    }

    @Test func anAccountShowsAtTheNextStartEvenBeforeItsFirstRefresh() async throws {
        // A first start: the PXE gives the account's address, and the refresh fails before anything is read, so no
        // snapshot of a refresh is saved.
        let pxe = FakePxe()
        pxe.failBalances = true
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        await (try SessionTests().session(pxe, dir: dir)).open()
        let second = try SessionTests().session(pxe, dir: dir)
        second.restoreSnapshot()
        #expect(second.account?.address == "addr-abab")
        #expect(second.stale)
    }
}

@MainActor
struct ApprovalTests {
    @Test func approveWaitsForTheUserDecision() async throws {
        let approvals = DappApprovals()
        let params = try JSONValue.parse(#"{"kind":"capabilities","tab":7,"origin":"https://vote.example","title":"Vote asks","details":["accounts"],"emojis":"🦊🌵"}"#)
        let reply = Task { try await approvals.approve(params) }
        await eventually { !approvals.pending.isEmpty }
        let request = try #require(approvals.pending.first)
        #expect(request.kind == .capabilities)
        #expect(request.emojis == "🦊🌵")
        #expect(request.tab == 7)
        #expect(request.sections.isEmpty)
        approvals.decide(request.id, approved: true)
        #expect(try await reply.value["approved"] == .bool(true))
        #expect(approvals.pending.isEmpty)
    }

    @Test func aSpendIsApprovedOnlyOnceTheOwnerConfirmsIt() async throws {
        let approvals = DappApprovals()
        let params = try JSONValue.parse(#"{"kind":"transaction","tab":1,"origin":"https://dex.example","title":"Swap"}"#)
        let reply = Task { try await approvals.approve(params) }
        await eventually { !approvals.pending.isEmpty }
        let request = try #require(approvals.pending.first)
        var settled = false
        await approvals.answer(request, approved: true, owner: { false }, settle: { settled = true })
        #expect(approvals.pending == [request])
        await approvals.answer(request, approved: true, owner: { true }, settle: { settled = true })
        #expect(settled)
        #expect(try await reply.value["approved"] == .bool(true))
    }

    @Test func aRejectionAsksNothing() async throws {
        let approvals = DappApprovals()
        let params = try JSONValue.parse(#"{"kind":"transaction","tab":1,"origin":"https://dex.example","title":"Swap"}"#)
        let reply = Task { try await approvals.approve(params) }
        await eventually { !approvals.pending.isEmpty }
        let request = try #require(approvals.pending.first)
        await approvals.answer(request, approved: false, owner: { Issue.record("asked"); return true }, settle: {})
        #expect(try await reply.value["approved"] == .bool(false))
    }

    @Test func approvalsCarryTheirFullDetails() async throws {
        let approvals = DappApprovals()
        let params = try JSONValue.parse(#"""
        {"kind":"transaction","tab":1,"origin":"https://dex.example","title":"Send?","details":["swap"],
         "sections":[{"title":"Call 1: swap","rows":[{"label":"Address","value":"0x1111"},{"label":"amount · u128","value":"5"},{"bad":1}]},
                     {"rows":[]}]}
        """#)
        let reply = Task { try await approvals.approve(params) }
        await eventually { !approvals.pending.isEmpty }
        let request = try #require(approvals.pending.first)
        #expect(request.sections.map(\.title) == ["Call 1: swap"])
        #expect(request.sections.first?.rows == [.init(label: "Address", value: "0x1111"), .init(label: "amount · u128", value: "5")])
        approvals.decide(request.id, approved: false)
        #expect(try await reply.value["approved"] == .bool(false))
    }

    @Test func rejectAllAnswersNo() async {
        let approvals = DappApprovals()
        let answer = Task { await approvals.ask(.transaction, tab: 1, origin: "https://a", title: "Send?", details: [], emojis: nil) }
        await eventually { !approvals.pending.isEmpty }
        approvals.rejectAll()
        #expect(await answer.value == false)
    }

    @Test func aClosedPageLosesOnlyItsOwnRequests() async {
        let approvals = DappApprovals()
        let left = Task { await approvals.ask(.connect, tab: 1, origin: "https://legit.app", title: "Connect?", details: [], emojis: nil) }
        let other = Task { await approvals.ask(.connect, tab: 2, origin: "https://other.app", title: "Connect?", details: [], emojis: nil) }
        await eventually { approvals.pending.count == 2 }
        approvals.rejectTab(1)
        #expect(await left.value == false)
        #expect(approvals.pending.map(\.tab) == [2])
        approvals.decide(approvals.pending[0].id, approved: true)
        #expect(await other.value == true)
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

    @Test func aTransferBetweenTwoOwnAccountsShowsOnBoth() {
        let transfer = move("0xme", "0xother")
        let sent = History.items(owner: "0xme", moves: [transfer], tokens: [pusd], known: [])
        let received = History.items(owner: "0xother", moves: [transfer], tokens: [pusd], known: [])
        #expect(sent.map(\.kind) == [.sendPrivate])
        #expect(received.map(\.kind) == [.receive])
        // The store keeps one item per id across accounts: each side needs its own.
        #expect(sent.first?.id != received.first?.id)
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

struct PaymentRequestTests {
    let me = "0x" + String(repeating: "ab", count: 32)
    let token = "0x" + String(repeating: "cd", count: 32)

    @Test func aBareAddressIsARequestWithoutDetails() {
        #expect(PaymentRequest.parse("  \(me)\n") == PaymentRequest(address: me))
    }

    @Test func aUriCarriesNetworkTokenAndAmount() throws {
        let request = try #require(PaymentRequest.parse("aztec:\(me)?network=testnet-v6&token=\(token)&amount=1.5"))
        #expect(request == PaymentRequest(address: me, network: "testnet-v6", token: token, amount: "1.5"))
        #expect(request.uri == "aztec:\(me)?network=testnet-v6&token=\(token)&amount=1.5")
        #expect(PaymentRequest(address: me).uri == me)
    }

    @Test func anythingElseIsRejectedAndBadParametersIgnored() {
        #expect(PaymentRequest.parse("https://example.com") == nil)
        #expect(PaymentRequest.parse("aztec:0x12") == nil)
        #expect(PaymentRequest.parse("aztec:\(me)?token=nope&amount=-3") == PaymentRequest(address: me))
        #expect(PaymentRequest.parse("aztec:\(me)?network=a+b&amount=1%zz") == PaymentRequest(address: me, network: "a+b"))
        #expect(PaymentRequest.parse("aztec:\(me)?amount=١٢") == PaymentRequest(address: me)) // Arabic-Indic digits
    }

    @Test func aSharedTokenIsItsAddressWithTheNetwork() throws {
        let shared = PaymentRequest(address: token, network: "testnet-v6")
        #expect(shared.uri == "aztec:\(token)?network=testnet-v6")
        #expect(try #require(PaymentRequest.parse(shared.uri)).tokenAddress == token)
        #expect(try #require(PaymentRequest.parse("aztec:\(me)?token=\(token)")).tokenAddress == token)
        #expect(shared.networkNote(current: "testnet-v6") == nil)
        #expect(shared.networkNote(current: "testnet-v5") == "This code is for testnet-v6, not testnet-v5")
        #expect(PaymentRequest(address: token).networkNote(current: "testnet-v5") == nil)
    }
}

struct BrowsingTests {
    @Test func thePrivacyPolicyIsTheOnePublishedWithTheDocs() {
        #expect(Browsing.privacyPolicy.absoluteString == "https://azwall.github.io/azwall/privacy")
    }

    @Test func aBuildWithoutTheConnectionScriptSaysSoAndWhatToDo() {
        #expect(Browsing.noConnectionScript(network: "Mainnet (v6)")
            == "Apps cannot connect to the wallet on Mainnet (v6): this version of Azwall lacks the connection script for it. Update the app.")
    }

    @Test func addressesOpenAsTypedAndTextIsSearchedPrivately() {
        let ddg = SearchEngine.default
        #expect(Browsing.address(of: " vote.example.org ", engine: ddg) == "https://vote.example.org")
        #expect(Browsing.address(of: "https://x.dev/a?b=1", engine: ddg) == "https://x.dev/a?b=1")
        #expect(Browsing.address(of: "localhost:5180", engine: ddg) == "http://localhost:5180")
        #expect(Browsing.address(of: "aztec voting dapp", engine: ddg) == "https://duckduckgo.com/?q=aztec+voting+dapp")
        #expect(Browsing.address(of: "aztec", engine: .brave) == "https://search.brave.com/search?q=aztec")
        #expect(Browsing.address(of: "  ", engine: ddg) == nil)
    }

    @Test func aSavedEngineFallsBackToTheDefault() {
        #expect(SearchEngine.named("STARTPAGE") == .startpage)
        #expect(SearchEngine.named(nil) == .duckduckgo)
        #expect(SearchEngine.named("GOOGLE") == .duckduckgo)
    }

    @Test func aDappTileShowsItsHostAndTheSameColourAsOnAndroid() {
        #expect(KnownDapp(name: "Palmyr", url: "https://aztec.palmyr.io/swap?x=1").host == "aztec.palmyr.io")
        #expect(KnownDapp(name: "Palmyr", url: "https://aztec.palmyr.io").tint == "ac675a")
        #expect(KnownDapp(name: "Palmyr", url: "https://aztec.palmyr.io/swap").iconPath == "dapps/aztec.palmyr.io.png")
    }

    @Test func anExplorerLinksATransactionAndNothingElse() {
        let scan = KnownExplorer(name: "Explorer", url: "https://explorer.example/?network=testnet", txUrl: "https://explorer.example/tx/{hash}?network=testnet")
        #expect(scan.page(ofTx: "0x0a1B") == "https://explorer.example/tx/0x0a1B?network=testnet")
        for hash in ["0x", "0a1b", "0x12/../admin", "0x12?network=mainnet"] { #expect(scan.page(ofTx: hash) == nil, "\(hash)") }
        #expect(scan.tile.host == "explorer.example")
    }
}

/// 2026-10-28 store-release audit (F9): a long phishing host must not read as the site it imitates.
struct SiteNameTests {
    @Test func anApprovalNamesTheSiteByTheDomainItsHostCannotFake() {
        #expect(SiteName.domain(of: "https://app.aztec.network.wallet-session-verify.evil-host.io") == "evil-host.io")
        #expect(SiteName.domain(of: "https://app.aztec.network") == "aztec.network")
        #expect(SiteName.domain(of: "https://aztec.palmyr.io:8443/swap") == "palmyr.io")
        #expect(SiteName.domain(of: "https://www.example.co.uk") == "example.co.uk")
        #expect(SiteName.domain(of: "http://192.168.1.10:3000") == "192.168.1.10")
        #expect(SiteName.domain(of: "http://localhost:5173") == "localhost")
        #expect(SiteName.host(of: "https://aztec.palmyr.io:8443/swap") == "aztec.palmyr.io:8443")
        // Re-audit (R3): shared hosting keeps the tenant, and an IPv6 address stays whole.
        #expect(SiteName.domain(of: "https://evil.github.io/azwall") == "evil.github.io")
        #expect(SiteName.domain(of: "https://azwall.github.io") == "azwall.github.io")
        #expect(SiteName.domain(of: "https://preview.app.vercel.app") == "app.vercel.app")
        #expect(SiteName.domain(of: "http://[::1]:8080") == "[::1]")
    }
}
