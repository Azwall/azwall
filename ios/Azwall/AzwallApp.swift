import AzwallKit
import SwiftUI

@main
struct AzwallApp: App {
    @State private var model = AppModel(deps: AppContainer())

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .tint(Palette.accent)
                .task { await model.boot() }
        }
    }
}

/// Onboarding, lock screen, or the unlocked wallet; messages from user actions show as a banner.
struct RootView: View {
    @Bindable var model: AppModel

    var body: some View {
        Group {
            switch model.phase {
            case .booting: ProgressView()
            case .onboarding: OnboardingView(model: model)
            case .locked: LockView(model: model)
            case .unlocked(let session): HomeView(model: model, session: session)
            case .failed(let message): Text("Azwall could not start:\n\(message)").multilineTextAlignment(.center).padding()
            }
        }
        .overlay(alignment: .bottom) {
            if let message = model.message {
                Text(friendlyError(message)).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 12)
                    .background(Capsule().fill(.regularMaterial)).padding(.bottom, 24)
                    .onTapGesture { model.message = nil }
                    .task { try? await Task.sleep(for: .seconds(4)); model.message = nil }
            }
        }
    }
}
