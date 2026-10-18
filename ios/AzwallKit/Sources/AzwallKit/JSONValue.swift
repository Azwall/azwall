import Foundation

/// Any JSON value, for the PXE bridge's untyped parameters and results.
public enum JSONValue: Codable, Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var intValue: Int? {
        if case .number(let n) = self { return Int(exactly: n) }
        return nil
    }

    /// Converts any Encodable into a JSONValue (through JSON encoding).
    public static func from<T: Encodable>(_ value: T) throws -> JSONValue {
        try parse(data: JSONEncoder().encode(value))
    }

    /// Decodes this value as a Decodable type.
    public func decode<T: Decodable>(_ type: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONEncoder().encode(self))
    }

    public func jsonString() -> String {
        (try? String(decoding: JSONEncoder().encode(self), as: UTF8.self)) ?? "null"
    }

    public static func parse(_ text: String) throws -> JSONValue {
        try parse(data: Data(text.utf8))
    }

    /// Parses with JSONSerialization, which tells booleans from numbers (Codable's single-value
    /// decoding may read `1` as `true` on Darwin).
    public static func parse(data: Data) throws -> JSONValue {
        convert(try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]))
    }

    private static func convert(_ any: Any) -> JSONValue {
        switch any {
        case let n as NSNumber:
            return CFGetTypeID(n) == CFBooleanGetTypeID() ? .bool(n.boolValue) : .number(n.doubleValue)
        case let s as String: return .string(s)
        case let a as [Any]: return .array(a.map(convert))
        case let o as [String: Any]: return .object(o.mapValues(convert))
        default: return .null
        }
    }
}
