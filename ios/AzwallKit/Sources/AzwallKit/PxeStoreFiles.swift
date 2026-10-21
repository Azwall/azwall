import Foundation

/// The PXE stores' copies the app keeps for the PXE page (pxe/host/src/native_store.ts): WebKit took the OPFS storage
/// away from the hidden page while it was open, so on iOS the page runs its stores in memory and hands the app each
/// database, sealed with the stores' key (the app cannot read it), at every write. One file per store name.
public struct PxeStoreFiles: Sendable {
    let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    /// The copy of the store `name`, or nil when there is none yet.
    public func load(_ name: String) throws -> Data? {
        let url = try file(name)
        return FileManager.default.fileExists(atPath: url.path) ? try Data(contentsOf: url) : nil
    }

    /// Replaces the copy at once (atomic write): a crash leaves the previous one or the new one, never half.
    public func save(_ name: String, _ data: Data) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: try file(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// `storeLoad` and `storeSave` for the page: the data travels as base64.
    public var handlers: [String: NativeHandler] {
        let files = self
        return [
            "storeLoad": { params in
                guard let name = params["name"]?.stringValue else { throw PxeError("storeLoad needs a name") }
                return .object(["data": try files.load(name).map { .string($0.base64EncodedString()) } ?? .null])
            },
            "storeSave": { params in
                guard let name = params["name"]?.stringValue, let b64 = params["data"]?.stringValue, let data = Data(base64Encoded: b64) else {
                    throw PxeError("storeSave needs a name and base64 data")
                }
                try files.save(name, data)
                return .null
            },
        ]
    }

    /// Store names are the PXE's own (`pxe-11155111-ed1eb2eb`): anything else could reach outside the directory.
    private func file(_ name: String) throws -> URL {
        guard !name.isEmpty, name.count <= 100, name.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }) else {
            throw PxeError("invalid store name")
        }
        return directory.appendingPathComponent("\(name).sealed")
    }
}
