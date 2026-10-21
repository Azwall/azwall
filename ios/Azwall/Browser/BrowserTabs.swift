import AzwallKit
import Foundation
import Observation

/// The app's browser: tabs kept for the whole unlocked app. Page messages go to the network's dApp gateway with
/// their page id; replies come back to the tab whose page they are for, if it is still loaded. When a page goes
/// away (navigation, closed tab), its pending requests are rejected and its sessions closed.
@MainActor
@Observable
final class BrowserTabs {
    /// Where Settings saves the search engine (SearchEngine raw value).
    static let engineKey = "azwall.searchEngine"

    private(set) var tabs: [BrowserTab] = []
    private(set) var current: BrowserTab
    @ObservationIgnored private var nextTab = 1
    @ObservationIgnored private var nextPageId = 1
    @ObservationIgnored private var script: String?
    @ObservationIgnored private var link: DappLink?
    @ObservationIgnored private var relay: Task<Void, Never>?
    @ObservationIgnored private let approvals: DappApprovals

    init(approvals: DappApprovals) {
        self.approvals = approvals
        let placeholder = BrowserTab(id: 0, nextPage: { 0 }, onPageClosed: { _ in })
        current = placeholder
        current = makeTab()
        tabs = [current]
    }

    /// Connects every tab to a network's dApp gateway (its content script, its PXE host); called again when the
    /// network changes: the previous link is dropped.
    func attach(_ link: DappLink, contentScript: String) {
        self.link = link
        script = contentScript
        for tab in tabs { tab.install(contentScript: contentScript) }
        relay?.cancel()
        relay = Task { [weak self] in
            for await message in link.toPages() { self?.tab(of: message.page)?.deliver(page: message.page, json: message.json) }
        }
    }

    /// Opens a new tab on the start page and shows it.
    func open() {
        let tab = makeTab()
        tabs.append(tab)
        current = tab
    }

    func select(_ tab: BrowserTab) {
        if tabs.contains(where: { $0 === tab }) { current = tab }
    }

    /// Shows the tab whose loaded page is `page`, e.g. the one a dApp request comes from.
    func selectPage(_ page: Int) {
        if let tab = tab(of: page) { current = tab }
    }

    /// Closes a tab (its page's sessions end); the last one is replaced by a fresh tab.
    func close(_ tab: BrowserTab) {
        tabs.removeAll { $0 === tab }
        if tabs.isEmpty { tabs = [makeTab()] }
        if current === tab, let last = tabs.last { current = last }
        tab.close()
    }

    private func tab(of page: Int) -> BrowserTab? { tabs.first { $0.pageId == page } }

    private func makeTab() -> BrowserTab {
        let tab = BrowserTab(id: nextTab, nextPage: { [weak self] in self?.newPageId() ?? 0 }, onPageClosed: { [weak self] in self?.pageClosed($0) })
        nextTab += 1
        tab.onMessage = { [weak self] page, origin, body in
            guard let link = self?.link else { return }
            Task { await link.fromPage(page, origin: origin, message: body) }
        }
        if let script { tab.install(contentScript: script) }
        return tab
    }

    private func newPageId() -> Int {
        defer { nextPageId += 1 }
        return nextPageId
    }

    private func pageClosed(_ page: Int) {
        approvals.rejectTab(page)
        if let link { Task { await link.closePage(page) } }
    }

    /// dApps must be served over https; plain http only from this device (dApp development).
    static func allowedOrigin(_ origin: String) -> Bool {
        guard let url = URL(string: origin), let host = url.host() else { return false }
        return url.scheme == "https" || (url.scheme == "http" && (host == "localhost" || host == "127.0.0.1"))
    }
}
