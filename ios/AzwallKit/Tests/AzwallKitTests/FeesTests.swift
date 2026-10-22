import Testing

@testable import AzwallKit

struct FeesTests {
    func check(_ fees: String?) -> NetworkCheck {
        NetworkCheck(nodeVersion: "6.0.0", l1ChainId: 1, rollupVersion: 2, vkTreeRoot: "0x", protocolContractsHash: "0x", blockNumber: 10, fees: fees)
    }

    @Test func sponsoredNetworksChargeNothing() {
        #expect(Fees.line(check("sponsored"), feeJuice: nil) == Fees.sponsored)
        #expect(Fees.line(check(nil), feeJuice: nil) == Fees.sponsored)
    }

    @Test func elsewhereTheAccountPaysWithItsFeeJuice() {
        #expect(Fees.line(check("feeJuice"), feeJuice: "1500000000000000000") == "Paid with your Fee Juice (you hold 1.5 FJ)")
        #expect(Fees.line(check("feeJuice"), feeJuice: "0").contains("will fail"))
        #expect(Fees.activation(check("feeJuice"), feeJuice: "0").contains("bridge AZTEC from Ethereum"))
        #expect(Fees.activation(check("feeJuice"), feeJuice: "5").contains("paid with the Fee Juice it holds"))
    }
}
