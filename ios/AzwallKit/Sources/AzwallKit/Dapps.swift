import Foundation
import Observation
import OSLog

public enum ApprovalKind: String, Sendable {
    case connect, verify, capabilities, transaction, authwit

    /// Approving it spends or lets spend from the account: the owner confirms it (Face ID), as for the wallet's own.
    public var signs: Bool { self == .transaction || self == .authwit }
}

/// Something a dApp asks the user, shown in a panel docked under the page (pxe/<bundle>/src/dapp/gateway.ts).
public struct ApprovalRequest: Identifiable, Equatable, Sendable {
    public let id: Int
    public let kind: ApprovalKind
    /// The page that asked (the browser's page id): its requests are dropped when it navigates away or closes.
    public let tab: Int
    public let origin: String
    public let title: String
    public let details: [String]
    /// Shown by the page too once the secure channel is up: the user checks they match.
    public let emojis: String?
    /// Everything else, in full (contracts, functions, calls, arguments, fees, the raw request), for the detail view.
    public let sections: [ApprovalSection]
}

/// A titled group of labelled values in an approval's detail view (pxe/<bundle>/src/dapp/details.ts).
public struct ApprovalSection: Equatable, Sendable {
    public struct Row: Equatable, Sendable {
        public let label: String
        public let value: String
    }

    public let title: String
    public let rows: [Row]

    /// The sections of an `approve` call; malformed ones are skipped.
    static func list(_ json: JSONValue?) -> [ApprovalSection] {
        (json?.arrayValue ?? []).compactMap { section in
            guard let title = section["title"]?.stringValue else { return nil }
            let rows = (section["rows"]?.arrayValue ?? []).compactMap { row -> Row? in
                guard let label = row["label"]?.stringValue, let value = row["value"]?.stringValue else { return nil }
                return Row(label: label, value: value)
            }
            return ApprovalSection(title: title, rows: rows)
        }
    }
}

/// The user's answers to dApp requests. One queue for the app; the PXE host's `approve` calls wait on it.
@MainActor
@Observable
public final class DappApprovals {
    public private(set) var pending: [ApprovalRequest] = []
    private var waiting: [Int: CheckedContinuation<Bool, Never>] = [:]
    private var nextId = 1

    /// Created by the app container, before any actor work: it touches no state.
    public nonisolated init() {}

    /// Native handler for the host's `approve` call: params in, `{ approved }` out.
    public nonisolated var handler: NativeHandler {
        { [weak self] params in
            guard let self else { return .object(["approved": .bool(false)]) }
            return try await self.approve(params)
        }
    }

    func approve(_ params: JSONValue) async throws -> JSONValue {
        guard let kind = params["kind"]?.stringValue.flatMap(ApprovalKind.init(rawValue:)) else { throw PxeError("unknown approval kind") }
        let details = params["details"]?.arrayValue?.compactMap(\.stringValue) ?? []
        let approved = await ask(kind, tab: params["tab"]?.intValue ?? 0, origin: params["origin"]?.stringValue ?? "",
                                 title: params["title"]?.stringValue ?? "", details: details, emojis: params["emojis"]?.stringValue,
                                 sections: ApprovalSection.list(params["sections"]))
        return .object(["approved": .bool(approved)])
    }

    public func ask(
        _ kind: ApprovalKind, tab: Int, origin: String, title: String, details: [String], emojis: String?, sections: [ApprovalSection] = []
    ) async -> Bool {
        let request = ApprovalRequest(id: nextId, kind: kind, tab: tab, origin: origin, title: title, details: details, emojis: emojis, sections: sections)
        nextId += 1
        pending.append(request)
        return await withCheckedContinuation { waiting[request.id] = $0 }
    }

    private var answering: Set<Int> = []

    /// The user's answer to `request`. A spend (`ApprovalKind.signs`) is approved only once `owner` confirms it
    /// (Face ID) — declined, the request stays pending to retry or reject — and after `settle` (a cancelled wallet
    /// transaction winding down). A second tap while the first is being confirmed is ignored.
    public func answer(_ request: ApprovalRequest, approved: Bool, owner: () async -> Bool, settle: () async -> Void) async {
        guard answering.insert(request.id).inserted else { return }
        defer { answering.remove(request.id) }
        if approved && request.kind.signs {
            guard await owner() else { return }
            await settle()
        }
        decide(request.id, approved: approved)
    }

    public func decide(_ id: Int, approved: Bool) {
        pending.removeAll { $0.id == id }
        waiting.removeValue(forKey: id)?.resume(returning: approved)
    }

    /// Rejects what page `tab` asked: it navigated away or closed, an answer must not reach the next page.
    public func rejectTab(_ tab: Int) {
        for request in pending where request.tab == tab { decide(request.id, approved: false) }
    }

    /// Rejects everything pending, e.g. when the network changes and the host that asked is gone.
    public func rejectAll() {
        for id in Array(waiting.keys) { decide(id, approved: false) }
    }
}

/// A site connected to the wallet through the browser.
public struct DappSession: Codable, Equatable, Identifiable, Sendable {
    public let sessionId: String
    public let origin: String
    public let appId: String
    public let emojis: String
    public let granted: Bool
    public var id: String { sessionId }
}

/// One step of a dApp transaction, as the gateway reports it (`dappTx` events).
struct DappTx: Codable {
    let id: String
    let account: String
    let origin: String
    let summary: String
    let status: String
    let txHash: String?
    let error: String?

    func applied(to item: ActivityItem) -> ActivityItem {
        var next = item
        switch status {
        // A sent transaction without a hash cannot be followed: shown as done rather than pending forever.
        case "sent": next.status = txHash == nil ? .confirmed : .pending; next.txHash = txHash
        case "failed": next.status = .failed; next.error = error
        default: break
        }
        return next
    }
}

/// A gateway message for the content script of page `page`, as JSON.
public struct PageMessage: Sendable {
    public let page: Int
    public let json: String
}

/// The browser's link to the PXE host's dApp gateway: page messages go in with their page id and origin (from the
/// web view, never from the page) and the selected account; the gateway's replies come out with their page id.
@MainActor
@Observable
public final class DappLink {
    public private(set) var sessions: [DappSession] = []
    @ObservationIgnored private var pages: AsyncStream<PageMessage>.Continuation?
    @ObservationIgnored private var started: [String: ActivityItem] = [:]
    @ObservationIgnored weak var session: WalletSession?

    /// `registerContract` carries a whole contract artifact, encrypted: several megabytes for a token (Palmyr's are
    /// 4 to 9 MB). The cap only stops a page from wearing the PXE down with far larger messages.
    public static let maxMessage = 32_000_000

    private static let logger = Logger(subsystem: "dev.azwall", category: "dapp")

    /// Messages for the pages' content scripts; a new call replaces the previous stream.
    public func toPages() -> AsyncStream<PageMessage> {
        let (stream, continuation) = AsyncStream<PageMessage>.makeStream(bufferingPolicy: .bufferingNewest(64))
        pages?.finish()
        pages = continuation
        return stream
    }

    /// A message from the content script of page `tabId` at `origin`; oversized ones are dropped (the page then waits
    /// for a reply that never comes, so the drop is logged).
    public func fromPage(_ tabId: Int, origin: String, message: String) async {
        guard message.utf8.count <= Self.maxMessage else {
            Self.logger.warning("dropped a \(message.utf8.count, privacy: .public)-byte message from \(origin, privacy: .public)")
            return
        }
        guard let session, let account = session.account?.address, let client = try? session.client else { return }
        let params: JSONValue = .object([
            "tabId": .number(Double(tabId)), "origin": .string(origin), "message": .string(message), "account": .string(account),
        ])
        _ = try? await client.callRaw("dappMessage", params)
    }

    /// Page `tabId` navigated away or its tab closed: its sessions and pending connections end.
    public func closePage(_ tabId: Int) async {
        _ = try? await session?.client.callRaw("dappClosePage", .object(["tabId": .number(Double(tabId))]))
    }

    public func disconnect(_ sessionId: String) async {
        _ = try? await session?.client.callRaw("dappDisconnect", .object(["sessionId": .string(sessionId)]))
    }

    /// Handles the gateway's events; false for the others.
    func handle(_ event: PxeEvent) -> Bool {
        switch event.name {
        case "dapp":
            guard let tab = event.data["tabId"]?.intValue, let message = event.data["message"] else { return true }
            pages?.yield(PageMessage(page: tab, json: message.jsonString()))
        case "dappSessions":
            sessions = (try? event.data.decode([DappSession].self)) ?? []
        case "dappTx":
            if let tx = try? event.data.decode(DappTx.self) { track(tx) }
        default:
            return false
        }
        return true
    }

    /// Records the transactions dApps send in the activity, like the wallet's own: proving once approved,
    /// pending with the hash once sent (then followed to a block), or failed.
    private func track(_ tx: DappTx) {
        guard let session else { return }
        var draft = ActivityItem(id: tx.id, account: tx.account, kind: .dapp)
        draft.origin = tx.origin
        draft.summary = tx.summary
        let item = tx.applied(to: started[tx.id] ?? draft)
        if item.status == .proving { started[tx.id] = item } else { started[tx.id] = nil }
        session.record(item)
        if item.status == .pending, let client = try? session.client { Task { await session.confirm(item, client) } }
    }
}
