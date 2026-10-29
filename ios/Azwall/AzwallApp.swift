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
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch model.phase {
            case .booting: ProgressView()
            case .onboarding: OnboardingView(model: model)
            case .locked: LockView(model: model)
            case .vaultLost: VaultLostView(model: model)
            case .unlocked(let session): MainView(model: model, session: session)
            case .failed(let message): Text("Azwall could not start:\n\(message)").multilineTextAlignment(.center).padding()
            }
        }
        .overlay(alignment: .bottom) {
            if let message = model.message {
                Text(friendlyError(message)).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 12)
                    .background(Capsule().fill(.regularMaterial)).padding(.bottom, 24)
                    .onTapGesture { model.message = nil }
                    // Cancelled only once the message is gone, so clearing it again is harmless.
                    .task { try? await Task.sleep(for: .seconds(4)); model.message = nil }
            }
        }
        .onChange(of: scenePhase) {
            // The app switcher shows a snapshot taken on the way out: a cover, not the balances or a revealed key, over
            // the sheets too (a key being imported, the accounts).
            if scenePhase == .active { PrivacyShield.shared.hide() } else { PrivacyShield.shared.show() }
            if scenePhase == .background {
                // A few seconds granted by iOS to write the PXE store before the app is suspended; ended when iOS takes
                // them back, so a slow write is cut short rather than the app killed for overrunning them.
                let task = BackgroundTask()
                Task {
                    await model.onBackground()
                    task.end()
                }
            }
            if scenePhase == .active { Task { await model.onForeground() } }
        }
    }
}

/// A window above every other, sheets included, holding the cover while the app is not active.
@MainActor
private final class PrivacyShield {
    static let shared = PrivacyShield()
    private var window: UIWindow?

    func show() {
        guard window == nil, let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let cover = UIWindow(windowScene: scene)
        cover.windowLevel = .alert + 1
        cover.rootViewController = UIHostingController(rootView: PrivacyCover())
        cover.isHidden = false
        window = cover
    }

    func hide() {
        window?.isHidden = true
        window = nil
    }
}

/// Background time asked of iOS, given back when the work ends or when iOS asks for it, whichever comes first.
@MainActor
private final class BackgroundTask {
    private var id = UIBackgroundTaskIdentifier.invalid

    init() {
        id = UIApplication.shared.beginBackgroundTask { [weak self] in MainActor.assumeIsolated { self?.end() } }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}

private struct PrivacyCover: View {
    var body: some View {
        VStack(spacing: 12) {
            Image("Logo").resizable().scaledToFit().frame(height: 96)
            Text("Azwall").font(.title2.bold())
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background)
    }
}
