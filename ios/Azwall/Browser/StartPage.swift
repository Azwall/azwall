import AzwallKit
import SwiftUI

/// The browser's start page, native rather than a web page: it shows at once and asks nothing of any server until
/// the user picks a site. Tiles are the network's suggested dApps (a dApp works with one Aztec version), then the
/// sites connected to the wallet. Badges are drawn, not favicons: loading those would tell each site the app opened.
struct StartPage: View {
    let network: NetworkInfo
    let sessions: [DappSession]
    let engine: SearchEngine
    let onOpen: (String) -> Void

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Explore").font(.title2.bold())
                Text("dApps on \(network.label)").foregroundStyle(.secondary)
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(network.dapps) { dapp in
                        Button { onOpen(dapp.url) } label: { DappTile(dapp: dapp) }.buttonStyle(.plain)
                    }
                }
                if !sessions.isEmpty {
                    Text("Connected sites").font(.headline).padding(.top, 12)
                    ForEach(unique, id: \.origin) { s in
                        Button { onOpen(s.origin) } label: { ConnectedSite(session: s) }.buttonStyle(.plain)
                    }
                }
                Text("Searches from the address bar go to \(engine.label). Change it in Settings.")
                    .font(.footnote).foregroundStyle(.secondary).padding(.top, 12)
            }
            .padding(16)
        }
        .background(Palette.background)
    }

    private var unique: [DappSession] {
        var seen = Set<String>()
        return sessions.filter { seen.insert($0.origin).inserted }
    }
}

private struct DappTile: View {
    let dapp: KnownDapp

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            DappIcon(dapp: dapp)
            Text(dapp.name).font(.subheadline.weight(.semibold)).lineLimit(1)
            Text(dapp.description).font(.caption).foregroundStyle(.secondary).lineLimit(2, reservesSpace: true)
            Text(dapp.host).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 20).fill(Palette.card))
        .contentShape(Rectangle())
    }
}

private struct ConnectedSite: View {
    let session: DappSession

    var body: some View {
        let site = session.origin.replacingOccurrences(of: "https://", with: "")
        HStack(spacing: 12) {
            DappIcon(dapp: KnownDapp(name: site, url: session.origin), size: 32)
            VStack(alignment: .leading) {
                Text(site).font(.subheadline.weight(.semibold))
                Text(session.emojis).font(.caption)
            }
            Spacer()
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

/// The site's icon shipped in the app bundle, or a drawn badge for sites without one.
private struct DappIcon: View {
    let dapp: KnownDapp
    var size: CGFloat = 40

    var body: some View {
        if let path = Bundle.main.path(forResource: dapp.iconPath, ofType: nil), let image = UIImage(contentsOfFile: path) {
            Image(uiImage: image).resizable().scaledToFit().frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: 10))
        } else {
            TokenBadge(symbol: dapp.name, address: dapp.tint, size: size)
        }
    }
}
