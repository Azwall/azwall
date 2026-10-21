import Foundation
import Testing

@testable import AzwallKit

struct PxeStoreFilesTests {
    let files = PxeStoreFiles(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))

    @Test func theCopyOfAStoreComesBackAsSaved() async throws {
        let load = try #require(files.handlers["storeLoad"]), save = try #require(files.handlers["storeSave"])
        #expect(try await load(.object(["name": .string("pxe-1-ab")]))["data"] == .null)
        _ = try await save(.object(["name": .string("pxe-1-ab"), "data": .string(Data([1, 2, 3]).base64EncodedString())]))
        #expect(try await load(.object(["name": .string("pxe-1-ab")]))["data"]?.stringValue == Data([1, 2, 3]).base64EncodedString())
        #expect(try files.load("wallet-1-ab") == nil)
    }

    @Test func aNameCannotReachOutsideTheDirectory() {
        #expect(throws: PxeError.self) { try files.save("../vault", Data()) }
        #expect(throws: PxeError.self) { try files.load("a/b") }
        #expect(throws: PxeError.self) { try files.load("") }
    }
}
