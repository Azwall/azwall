import AzwallKit
import AzwallRust
import SwiftUI

@main
struct AzwallApp: App {
    @State private var model = WalletModel { try AzwallRuntime.create() }

    var body: some Scene {
        WindowGroup {
            NetworkView(model: model)
                .task { await model.load() }
        }
    }
}
