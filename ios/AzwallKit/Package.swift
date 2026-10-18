// swift-tools-version: 6.4
// AzwallKit: the wallet logic in pure Swift (testable with `swift test`). AzwallPlatform: what needs the
// device (the PXE host's web view, the Keychain, LocalAuthentication).
import PackageDescription

let package = Package(
    name: "AzwallKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "AzwallKit", targets: ["AzwallKit"]),
        .library(name: "AzwallPlatform", targets: ["AzwallPlatform"]),
    ],
    targets: [
        .target(name: "AzwallKit"),
        .target(name: "AzwallPlatform", dependencies: ["AzwallKit"]),
        .testTarget(name: "AzwallKitTests", dependencies: ["AzwallKit"]),
    ]
)
