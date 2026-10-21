import Foundation
import Network

/// Serves the PXE host bundles from the app's resources on http://127.0.0.1:<port>.
///
/// The PXE needs a secure context (Web Crypto, OPFS), which WebKit grants to loopback HTTP origins. The
/// port is fixed per bundle so the origin, and with it the encrypted OPFS stores, stays the same across
/// launches. Only static, public files are served; the bridge itself goes through WKScriptMessageHandler.
final class BundleServer: @unchecked Sendable {
    let port: UInt16
    private let root: URL
    private let listener: NWListener
    private let queue = DispatchQueue(label: "azwall.bundle-server")

    init(root: URL, port: UInt16) throws {
        self.root = root.standardizedFileURL
        self.port = port
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
        listener = try NWListener(using: params)
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            // The first ready or failed state detaches the handler, so the continuation resumes exactly once.
            listener.stateUpdateHandler = { [listener] state in
                switch state {
                case .ready:
                    listener.stateUpdateHandler = nil
                    cont.resume()
                case .failed(let error):
                    listener.stateUpdateHandler = nil
                    cont.resume(throwing: error)
                default: break
                }
            }
            listener.newConnectionHandler = { [weak self] in self?.serve($0) }
            listener.start(queue: queue)
        }
    }

    func stop() {
        listener.cancel()
    }

    private func serve(_ connection: NWConnection) {
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, _, _ in
            guard let self, let data, let request = String(data: data, encoding: .utf8) else { return connection.cancel() }
            let response = self.response(for: request)
            connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
        }
    }

    /// `GET /pxe/v6/assets/x.js HTTP/1.1` → the file under the bundle root, or 404.
    func response(for request: String) -> Data {
        let parts = request.split(separator: " ", maxSplits: 2)
        guard parts.count >= 2, parts[0] == "GET" else { return Self.status(405, "Method Not Allowed") }
        let path = String(parts[1].split(separator: "?").first ?? "").removingPercentEncoding ?? ""
        let file = root.appendingPathComponent(path).standardizedFileURL
        guard file.path.hasPrefix(root.path + "/"), let body = try? Data(contentsOf: file) else { return Self.status(404, "Not Found") }
        let head = "HTTP/1.1 200 OK\r\nContent-Type: \(Self.mimeType(file.pathExtension))\r\nContent-Length: \(body.count)\r\n"
            + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        return Data(head.utf8) + body
    }

    static func mimeType(_ ext: String) -> String {
        switch ext.lowercased() {
        case "html": "text/html; charset=utf-8"
        case "js", "mjs": "text/javascript; charset=utf-8"
        case "wasm": "application/wasm"
        case "json": "application/json"
        case "css": "text/css"
        default: "application/octet-stream"
        }
    }

    private static func status(_ code: Int, _ text: String) -> Data {
        Data("HTTP/1.1 \(code) \(text)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
    }
}
