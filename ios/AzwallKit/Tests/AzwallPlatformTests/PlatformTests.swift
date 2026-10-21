import Foundation
import Testing

@testable import AzwallPlatform

struct BundleServerTests {
    @Test func servesThePxeBundlesOnly() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("pxe/v6"), withIntermediateDirectories: true)
        try Data("page".utf8).write(to: root.appendingPathComponent("pxe/v6/index.html"))
        try Data("{}".utf8).write(to: root.appendingPathComponent("azwall-config.json"))
        let server = try BundleServer(root: root, port: 47_699)
        let ok = String(decoding: server.response(for: "GET /pxe/v6/index.html HTTP/1.1"), as: UTF8.self)
        #expect(ok.hasPrefix("HTTP/1.1 200") && ok.hasSuffix("page"))
        for path in ["/azwall-config.json", "/pxe/../azwall-config.json", "/pxe/v6/../../azwall-config.json"] {
            #expect(String(decoding: server.response(for: "GET \(path) HTTP/1.1"), as: UTF8.self).hasPrefix("HTTP/1.1 404"), "served \(path)")
        }
    }
}

@MainActor
struct BridgeOriginTests {
    @Test func onlyThePxePageIsHeard() {
        let origin = "http://127.0.0.1:47606"
        #expect(MessageHandler.accepts(mainFrame: true, protocol: "http", host: "127.0.0.1", port: 47_606, origin: origin))
        #expect(!MessageHandler.accepts(mainFrame: false, protocol: "http", host: "127.0.0.1", port: 47_606, origin: origin))
        #expect(!MessageHandler.accepts(mainFrame: true, protocol: "http", host: "127.0.0.1", port: 47_605, origin: origin))
        #expect(!MessageHandler.accepts(mainFrame: true, protocol: "https", host: "evil.app", port: 443, origin: origin))
    }
}
