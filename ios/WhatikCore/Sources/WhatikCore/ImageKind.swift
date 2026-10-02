import Foundation

/// Formato riconosciuto dai "magic bytes", indipendente dall'estensione dichiarata.
public enum ImageKind: String, Codable, Sendable {
    case gif, webp, png, jpeg, heif, unknown

    public var mimeType: String {
        switch self {
        case .gif: return "image/gif"
        case .webp: return "image/webp"
        case .png: return "image/png"
        case .jpeg: return "image/jpeg"
        case .heif: return "image/heic"
        case .unknown: return "application/octet-stream"
        }
    }

    public var fileExtension: String {
        switch self {
        case .gif: return "gif"
        case .webp: return "webp"
        case .png: return "png"
        case .jpeg: return "jpg"
        case .heif: return "heic"
        case .unknown: return "bin"
        }
    }

    public static func sniff(_ data: Data) -> ImageKind {
        let b = [UInt8](data.prefix(16))
        func ascii(_ from: Int, _ len: Int) -> String {
            guard b.count >= from + len else { return "" }
            return String(decoding: b[from..<(from + len)], as: UTF8.self)
        }
        if ascii(0, 4) == "GIF8" { return .gif }
        if ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" { return .webp }
        if b.count >= 8 && b[0] == 0x89 && ascii(1, 3) == "PNG" { return .png }
        if b.count >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF { return .jpeg }
        if b.count >= 12 && ascii(4, 4) == "ftyp" { return .heif }
        return .unknown
    }

    /// true se un WebP dichiara l'animazione nel chunk VP8X (controllo rapido, senza decodificare).
    public static func webpDeclaresAnimation(_ data: Data) -> Bool {
        let b = [UInt8](data.prefix(32))
        guard b.count >= 21, String(decoding: b[12..<16], as: UTF8.self) == "VP8X" else { return false }
        return b[20] & 0x02 != 0
    }
}
