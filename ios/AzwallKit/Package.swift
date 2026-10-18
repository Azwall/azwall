// swift-tools-version: 6.4
// Pure Swift wallet logic (models, view state). No native code, so `swift test` runs anywhere.
import PackageDescription

let package = Package(
    name: "AzwallKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "AzwallKit", targets: ["AzwallKit"])],
    targets: [
        .target(name: "AzwallKit"),
        .testTarget(name: "AzwallKitTests", dependencies: ["AzwallKit"]),
    ]
)
