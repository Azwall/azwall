import Testing

@testable import AzwallKit

struct ReviewTests {
    let to = "0x" + String(repeating: "cd", count: 32)

    @Test func aSendListsTheWholeRecipientAndWhatStaysPrivate() {
        let review = Review.transfer(.private, amount: "12.5 TST", to: to, network: "Testnet")
        #expect(review.title == "Send privately")
        #expect(review.value("To") == to)
        #expect(review.value("Amount") == "12.5 TST")
        #expect(review.value("Privacy")?.contains("hidden") == true)
    }

    @Test func shieldingHasNoRecipient() {
        let review = Review.transfer(.shield, amount: "3 TST", to: to, network: "Testnet")
        #expect(review.value("To") == nil)
        #expect(review.value("Moves") == "Public balance → private balance")
    }

    @Test func aMintWithoutRecipientGoesToTheAccount() {
        #expect(Review.mint(amount: "1,000 TST", toPrivate: true, to: "", network: "Testnet").value("To") == "This account")
        #expect(Review.mint(amount: "1,000 TST", toPrivate: false, to: to, network: "Testnet").value("To") == to)
    }

    @Test func activationSaysWhoPays() {
        #expect(Review.activation(network: "Testnet").value("Fee") == Fees.sponsored)
        #expect(Review.activation(network: "Mainnet", fee: "Paid with your Fee Juice").value("Fee") == "Paid with your Fee Juice")
    }
}
