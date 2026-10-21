import Foundation

/// Error text for people, the same on both apps.
public enum Errors {
    /// The first line, without the `method: ` prefix the PXE bridge adds: no stack trace, no URL scheme cut in half.
    public static func friendly(_ message: String?) -> String {
        let line = (message ?? "").split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let text = line.replacingOccurrences(of: "^[A-Za-z][A-Za-z0-9]*: ", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? "Something went wrong" : text
    }
}
