import Testing

@testable import AzwallKit

struct FeesTests {
    func check(_ fees: String?) -> NetworkCheck {
        NetworkCheck(nodeVersion: "6.0.0", l1ChainId: 1, rollupVersion: 2, vkTreeRoot: "0x", protocolContractsHash: "0x", blockNumber: 10, fees: fees)
    }

    @Test func sponsoredNetworksChargeNothing() {
        #expect(Fees.line(check("sponsored"), feeJuice: nil) == Fees.sponsored)
        #expect(Fees.line(check(nil), feeJuice: nil) == Fees.sponsored)
        // Not before the network said so: on mainnet that would be wrong.
        #expect(Fees.line(nil, feeJuice: nil) == Fees.unknown)
    }

    @Test func elsewhereTheAccountPaysWithItsFeeJuice() {
        #expect(Fees.line(check("feeJuice"), feeJuice: "1500000000000000000") == "Paid with your Fee Juice (you hold 1.5 FJ)")
        #expect(Fees.line(check("feeJuice"), feeJuice: "0").contains("will fail"))
        #expect(Fees.activation(check("feeJuice"), feeJuice: "0").contains("bridge AZTEC from Ethereum"))
        #expect(Fees.activation(check("feeJuice"), feeJuice: "5").contains("paid with the Fee Juice it holds"))
    }

    /// While the PXE starts, how the network pays fees is unknown: Activate waits rather than show then vanish.
    @Test func activateWaitsForTheNetworkToSayHowItPays() {
        #expect(!Fees.canActivate(nil, feeJuice: nil, claim: nil))
        #expect(Fees.activation(nil, feeJuice: nil).contains("once the network is reached"))
        #expect(Fees.canActivate(check("sponsored"), feeJuice: nil, claim: nil))
        #expect(!Fees.canActivate(check("feeJuice"), feeJuice: "0", claim: nil))
        #expect(Fees.canActivate(check("feeJuice"), feeJuice: "5", claim: nil))
    }
}
