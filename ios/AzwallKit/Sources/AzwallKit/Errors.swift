import Foundation

/// Error text for people, the same on both apps.
public enum Errors {
    /// What the PXE's web view says when a request never got an answer (WebKit, Chromium, Firefox): the connection
    /// dropped, often because the app went to the background while proving. It can drop after the node received the
    /// transaction: the outcome is unknown, and a retry could send it twice.
    static let lostConnection = ["Load failed", "Failed to fetch", "NetworkError when attempting to fetch resource.", "The network connection was lost."]
    public static let network = "The connection to the network was lost before it answered, so the transaction may have been sent or not: check the activity list, and wait a few minutes before trying again, with the app kept open while it proves."

    /// The first line, without the `method: ` prefix the PXE bridge adds: no stack trace, no URL scheme cut in half.
    public static func friendly(_ message: String?) -> String {
        let line = (message ?? "").split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let text = line.replacingOccurrences(of: "^[A-Za-z][A-Za-z0-9]*: ", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        if lostConnection.contains(text) { return network }
        return text.isEmpty ? "Something went wrong" : text
    }
}
