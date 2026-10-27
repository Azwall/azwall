import Foundation
import Testing

@testable import AzwallKit

@MainActor
struct FeeJuiceClaimTests {
    private let five = #"{"amount":"5000000000000000000","messageHash":"0xm1","leafIndex":"7","ours":true,"ready":true,"claimed":false,"secret":"0x01"}"#

    private func session(_ pxe: FakePxe) throws -> WalletSession {
        try SessionTests().session(pxe)
    }

    @Test func bridgedFeeJuiceIsClaimedByTheActivationItself() async throws {
        let pxe = FakePxe()
        pxe.deposits = "[\(five),"
            + #"{"amount":"3","messageHash":"0xm2","leafIndex":"8","ours":true,"ready":false,"claimed":false},"#
            + #"{"amount":"9","messageHash":"0xm3","leafIndex":"9","ours":false,"ready":true,"claimed":false}]"#
        let s = try session(pxe)
        await s.open()
        #expect(s.claimable?.leafIndex == "7")
        #expect(s.arriving == "3")
        #expect(s.locked.count == 1)
        #expect(Fees.activation(s.check, feeJuice: s.feeJuice, claim: s.claimable).contains("5 FJ bridged from Ethereum are ready"))
        try await s.deployAccount()
        let claim = pxe.sent.last?["claim"]
        #expect(claim?["amount"]?.stringValue == "5000000000000000000")
        #expect(claim?["leafIndex"]?.stringValue == "7")
        #expect(claim?["secret"]?.stringValue == "0x01")
    }

    @Test func withoutABridgeDepositTheActivationPaysAsBefore() async throws {
        let pxe = FakePxe()
        let s = try session(pxe)
        await s.open()
        #expect(s.claimable == nil)
        try await s.deployAccount()
        #expect(pxe.sent.last?["claim"] == nil)
    }

    @Test func withoutFeeJuiceNorADepositNoActivationIsProven() async throws {
        let pxe = FakePxe()
        pxe.fees = "feeJuice"
        pxe.feeJuice = "0"
        let s = try session(pxe)
        await s.open()
        #expect(!Fees.canActivate(s.check, feeJuice: s.feeJuice, claim: s.claimable))
        await #expect(throws: PxeError.self) { try await s.deployAccount() }
        #expect(!pxe.calls.contains { $0.0 == "deployAccount" })
        // A deposit ready to claim pays it.
        pxe.deposits = "[\(five)]"
        await s.refresh()
        #expect(Fees.canActivate(s.check, feeJuice: s.feeJuice, claim: s.claimable))
    }

    @Test func anActiveAccountClaimsItsBridgedFeeJuice() async throws {
        let pxe = FakePxe()
        pxe.deposits = "[\(five)]"
        let s = try session(pxe)
        await s.open()
        let deposit = try #require(s.claimable)
        // Not active yet: activation is what claims it.
        await #expect(throws: PxeError.self) { try await s.claimFeeJuice(deposit) }
        pxe.deployed = true
        await s.refresh()
        try await s.claimFeeJuice(deposit)
        let item = try #require(s.activity.first)
        #expect(item.kind == .claimFeeJuice)
        #expect(item.symbol == "FJ")
        #expect(pxe.sent.last?["owner"]?.stringValue == s.selected)
        #expect(pxe.sent.last?["claim"]?["leafIndex"]?.stringValue == "7")
    }

    @Test func aClaimOnItsWayIsNotSentTwice() async throws {
        let pxe = FakePxe()
        pxe.deposits = "[\(five)]"
        pxe.deployed = true
        let s = try session(pxe)
        await s.open()
        // Sent, not in a block yet: the deposit still reads as unclaimed.
        s.record(ActivityItem(account: s.selected!, kind: .claimFeeJuice, status: .pending))
        #expect(s.claiming)
        #expect(s.bridged == Bridged(text: "Claiming 5 FJ bridged from Ethereum…", claim: false))
        await #expect(throws: PxeError.self) { try await s.claimFeeJuice(try #require(s.claimable)) }
        #expect(!pxe.calls.contains { $0.0 == "claimFeeJuice" })
    }

    @Test func anEnteredSecretIsKeptAndTriedOnTheDeposits() async throws {
        let pxe = FakePxe()
        let s = try session(pxe)
        await s.open()
        try await s.addClaimSecret("255")
        let asked = pxe.calls.last { $0.0 == "feeJuiceDeposits" }?.1["secrets"]?.arrayValue?.compactMap(\.stringValue)
        #expect(asked == ["0x" + String(repeating: "0", count: 62) + "ff"])
        await #expect(throws: PxeError.self) { try await s.addClaimSecret("not a secret") }
    }

    @Test func secretsAreNormalizedAndCheckedAgainstTheField() throws {
        let zero = "0x" + String(repeating: "0", count: 64)
        #expect(try ClaimSecrets.normalize("0") == zero)
        #expect(try ClaimSecrets.normalize(" 0x0\n") == zero)
        #expect(try ClaimSecrets.normalize("255") == "0x" + String(repeating: "0", count: 62) + "ff")
        #expect(try ClaimSecrets.normalize("0XFF") == "0x" + String(repeating: "0", count: 62) + "ff")
        // 2^200 in decimal: long division beyond 64 bits.
        #expect(try ClaimSecrets.normalize("1606938044258990275541962092341162602522202993782792835301376") == "0x" + String(repeating: "0", count: 13) + "1" + String(repeating: "0", count: 50))
        for bad in ["", "0xzz", "-1", "secret", "0x" + String(repeating: "f", count: 64)] {
            #expect(throws: PxeError.self) { try ClaimSecrets.normalize(bad) }
        }
    }
}

@MainActor
struct BridgedLineTests {
    @Test func theHomeScreenSaysWhatWasBridged() async throws {
        let pxe = FakePxe()
        pxe.deposits = #"[{"amount":"5000000000000000000","messageHash":"0xm","leafIndex":"1","ours":true,"ready":true,"claimed":false,"secret":"0x01"}]"#
        let s = try SessionTests().session(pxe)
        await s.open()
        // Not active: the activation claims it, so the line only says it is there.
        #expect(s.bridged == Bridged(text: "5 FJ bridged from Ethereum", claim: false))
        pxe.deployed = true
        await s.refresh()
        #expect(s.bridged == Bridged(text: "5 FJ bridged from Ethereum", claim: true))
        pxe.deposits = #"[{"amount":"2000000000000000000","messageHash":"0xm","leafIndex":"1","ours":true,"ready":false,"claimed":false}]"#
        await s.refresh()
        #expect(s.bridged == Bridged(text: "2 FJ arriving from Ethereum", claim: false))
        pxe.deposits = #"[{"amount":"1000000000000000000","messageHash":"0xm","leafIndex":"1","ours":false,"ready":true,"claimed":false}]"#
        await s.refresh()
        #expect(s.bridged == Bridged(text: "1 FJ bridged with your own secret", claim: false, askSecret: true))
        pxe.deposits = #"[{"amount":"1","messageHash":"0xm","leafIndex":"1","ours":true,"ready":true,"claimed":true}]"#
        await s.refresh()
        #expect(s.bridged == nil)
    }
}
