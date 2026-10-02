import Foundation

/// Un'immagine trovata in una pagina web.
public struct RemoteCandidate: Equatable, Hashable, Sendable, Identifiable {
    public var url: String
    public var name: String
    public var looksSticker: Bool
    public var id: String { url }
    public init(url: String, name: String, looksSticker: Bool) { self.url = url; self.name = name; self.looksSticker = looksSticker }
}

/// Analisi dei link condivisi e delle pagine (TikTok serializza gli URL nel JSON della pagina).
public enum URLParsing {
    private static let urlInText = try! NSRegularExpression(pattern: #"https?://[^\s<>"'\\]+"#)
    private static let imageExt = try! NSRegularExpression(pattern: #"\.(awebp|webp|gif|png|jpe?g)(\?|#|$)"#, options: [.caseInsensitive])
    private static let stickerHints = ["sticker", "awebp", "video2sticker", "ibyteimg", "tiktokcdn", "emoji"]

    private static func trimEnd(_ s: String, _ chars: Set<Character>) -> String {
        var out = Substring(s)
        while let last = out.last, chars.contains(last) { out = out.dropLast() }
        return String(out)
    }

    /// Estrae il primo URL da un testo (es. "Guarda questo sticker! https://...") e ripulisce la fine.
    public static func extractURL(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let range = NSRange(trimmed.startIndex..., in: trimmed)
        guard let m = urlInText.firstMatch(in: trimmed, range: range), let r = Range(m.range, in: trimmed) else { return nil }
        return trimEnd(String(trimmed[r]), [".", ",", ")", "]"])
    }

    public static func nameFromURL(_ url: String) -> String {
        let path = url.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]
            .split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
        let lastComponent = path.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? ""
        let last = lastComponent.isEmpty ? "sticker" : lastComponent
        let name = String(StickerNames.stripExtension(last).replacingOccurrences(of: "~", with: " ").prefix(60))
        return name.isEmpty ? "sticker" : name
    }

    /// Cerca gli URL di immagini dentro l'HTML/JSON di una pagina; gli sticker probabili prima.
    public static func findImages(html: String, pageURL: String) -> [RemoteCandidate] {
        let text = html
            .replacingOccurrences(of: "\\u002F", with: "/").replacingOccurrences(of: "\\u002f", with: "/")
            .replacingOccurrences(of: "\\/", with: "/")
            .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "\\u0026", with: "&")
        var order: [String] = []
        var seen: [String: RemoteCandidate] = [:]
        let range = NSRange(text.startIndex..., in: text)
        for m in urlInText.matches(in: text, range: range) {
            guard let r = Range(m.range, in: text) else { continue }
            let url = trimEnd(String(text[r]), [".", ",", ")", "]", ";"])
            let lower = url.lowercased()
            let lowerRange = NSRange(lower.startIndex..., in: lower)
            let isImage = imageExt.firstMatch(in: lower, range: lowerRange) != nil
                || lower.contains("video2sticker") || (lower.contains("tplv-") && lower.contains("sticker"))
            if !isImage { continue }
            let key = String(url.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0])
            if seen[key] != nil { continue }
            let looksSticker = stickerHints.contains { lower.contains($0) } || lower.hasSuffix(".gif") || lower.contains(".webp")
            seen[key] = RemoteCandidate(url: url, name: nameFromURL(url), looksSticker: looksSticker)
            order.append(key)
            if order.count >= 300 { break }
        }
        let all = order.compactMap { seen[$0] }
        // ordinamento stabile: prima gli sticker probabili, nell'ordine della pagina
        return all.filter { $0.looksSticker } + all.filter { !$0.looksSticker }
    }

    public static func looksLikeHTML(_ data: Data) -> Bool {
        let head = String(decoding: data.prefix(512), as: UTF8.self).lowercased()
        return head.contains("<html") || head.contains("<!doctype")
    }
}

public enum StickerNames {
    private static let imageExtensions: Set<String> = ["gif", "webp", "png", "jpg", "jpeg", "bmp", "heic", "heif"]

    public static func hasImageExtension(_ name: String) -> Bool {
        guard let dot = name.lastIndex(of: ".") else { return false }
        return imageExtensions.contains(name[name.index(after: dot)...].lowercased())
    }

    public static func stripExtension(_ name: String) -> String {
        guard let dot = name.lastIndex(of: "."), dot > name.startIndex else { return name }
        let extLength = name.distance(from: dot, to: name.endIndex)
        return extLength <= 6 ? String(name[..<dot]) : name
    }
}
