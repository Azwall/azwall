import Foundation
import Testing

@testable import AzwallKit

struct DiagnosticLogTests {
    @Test func whatCouldIdentifyTheUserIsCutDown() {
        let key = "0x" + String(repeating: "ab", count: 32)
        #expect(DiagnosticLog.redact("sent from \(key)") == "sent from 0xababab…abab")
        #expect(DiagnosticLog.redact("node https://lb.drpc.org/aztec/SECRETKEY?x=1 is down") == "node https://lb.drpc.org is down")
        #expect(DiagnosticLog.redact("block 0x1f 12") == "block 0x1f 12")
        #expect(DiagnosticLog.redact("two\nlines") == "two lines")
    }

    @Test func onlyTheLastLinesAreKeptAndTheyLastAcrossLaunches() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".log")
        let log = DiagnosticLog(now: { Date(timeIntervalSince1970: 0) })
        log.attach(file: file)
        for i in 0 ..< DiagnosticLog.capacity + 5 { log.record(.warn, "test", "line \(i)") }
        #expect(log.entries.count == DiagnosticLog.capacity)
        #expect(log.entries.first == "1970-01-01T00:00:00Z WARN [test] line 5")
        let next = DiagnosticLog()
        next.attach(file: file)
        #expect(next.entries == log.entries)
        next.clear()
        #expect(next.entries.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test func theExportSaysWhereItComesFrom() {
        let log = DiagnosticLog(now: { Date(timeIntervalSince1970: 0) })
        log.report("feeJuice", PxeError("bridge deposits could not be read"))
        let text = log.export(app: "Azwall 0.1.0 (1)", system: "iOS 26.3")
        #expect(text.hasPrefix("Azwall diagnostic log\nApp: Azwall 0.1.0 (1)\nSystem: iOS 26.3\n"))
        #expect(text.contains("WARN [feeJuice] bridge deposits could not be read"))
    }

    @Test func aFailedReadIsKeptAndACancellationIsNot() async {
        let log = DiagnosticLog(now: { Date(timeIntervalSince1970: 0) })
        #expect(await log.attempt("sync") { 7 } == 7)
        #expect(await log.attempt("sync") { () throws -> Int in throw PxeError("no Ethereum node configured") } == nil)
        #expect(await log.attempt("sync") { () throws -> Int in throw CancellationError() } == nil)
        #expect(log.entries == ["1970-01-01T00:00:00Z WARN [sync] no Ethereum node configured"])
    }

    @Test func anUnreadableStoreStartsOverAndSaysSo() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let networkId = "broken-\(UUID().uuidString)"
        try Data("not json".utf8).write(to: directory.appendingPathComponent("wallet-\(networkId).json"))
        let before = DiagnosticLog.shared.entries.filter { $0.contains("[store]") }.count
        #expect(WalletStore(directory: directory, networkId: networkId).tokens.isEmpty)
        #expect(DiagnosticLog.shared.entries.filter { $0.contains("[store]") }.count > before)
    }
}
