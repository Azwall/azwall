import AzwallCoreFFI
import AzwallKit
import Foundation

/// Builds the `WalletBackend` from what the app bundle ships.
public enum AzwallRuntime {
    /// Generated at build time from networks.json and .env (rust/azwall-core, azwall-config).
    public static let configResource = "azwall-config"
    /// Folder reference holding the CRS files (scripts/fetch-crs.sh).
    public static let crsFolder = "crs"

    /// Version of the Rust core, for the About screen.
    public static var coreVersion: String { AzwallCoreFFI.coreVersion() }

    /// Barretenberg releases this build can prove with.
    public static var proverReleases: [String] { supportedBarretenbergReleases() }

    public static func create(bundle: Bundle = .main, proverThreads: UInt32 = 0) throws -> any WalletBackend {
        guard let configURL = bundle.url(forResource: configResource, withExtension: "json") else {
            throw WalletError("\(configResource).json is missing from the app bundle")
        }
        let config = try String(contentsOf: configURL, encoding: .utf8)
        // Barretenberg releases are embedded as frameworks; the CRS is read in place.
        let frameworks = bundle.privateFrameworksPath ?? bundle.bundlePath
        let crs = bundle.resourceURL?.appendingPathComponent(crsFolder).path ?? bundle.bundlePath
        return try RustWalletBackend(configJson: config, nativeLibDir: frameworks, crsDir: crs, proverThreads: proverThreads)
    }
}
