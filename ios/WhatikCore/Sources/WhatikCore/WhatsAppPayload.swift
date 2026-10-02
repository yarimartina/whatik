import Foundation

/// Dati che l'app WhatsApp per iOS legge dagli appunti (tipo "net.whatsapp.third-party.sticker-pack")
/// quando si apre whatsapp://stickerPack: JSON con il pack, l'icona PNG e gli sticker WebP in base64.
public enum WhatsAppPayload {
    public static let pasteboardType = "net.whatsapp.third-party.sticker-pack"
    public static let openURL = "whatsapp://stickerPack"

    public struct Sticker: Sendable {
        public var webp: Data
        public var emojis: [String]
        public var accessibilityText: String?
        public init(webp: Data, emojis: [String], accessibilityText: String? = nil) {
            self.webp = webp; self.emojis = emojis; self.accessibilityText = accessibilityText
        }
    }

    /// Problemi che impedirebbero l'importazione (stesse regole dell'app di esempio di WhatsApp).
    public static func validate(pack: StickerPack, trayPNG: Data, stickers: [Sticker]) -> [String] {
        var problems: [String] = []
        if stickers.count < PackPlanner.minStickers { problems.append("Servono almeno \(PackPlanner.minStickers) sticker (ce ne sono \(stickers.count))") }
        if stickers.count > PackPlanner.maxStickers { problems.append("Al massimo \(PackPlanner.maxStickers) sticker per pack") }
        if trayPNG.count > 50 * 1024 { problems.append("Icona del pack oltre 50 KB") }
        let limit = pack.animated ? 500 * 1024 : 100 * 1024
        for (i, s) in stickers.enumerated() where s.webp.count > limit {
            problems.append("Sticker \(i + 1) oltre \(limit / 1024) KB")
        }
        if pack.name.isEmpty || pack.name.count > 128 { problems.append("Nome del pack non valido") }
        return problems
    }

    public static func json(pack: StickerPack, trayPNG: Data, stickers: [Sticker]) throws -> Data {
        let list: [[String: Any]] = stickers.map { s in
            var dict: [String: Any] = [
                "image_data": s.webp.base64EncodedString(),
                "emojis": s.emojis.isEmpty ? ["😀"] : Array(s.emojis.prefix(3)),
            ]
            if let text = s.accessibilityText, !text.isEmpty { dict["accessibility_text"] = String(text.prefix(pack.animated ? 255 : 125)) }
            return dict
        }
        let object: [String: Any] = [
            "identifier": pack.identifier,
            "name": pack.name,
            "publisher": pack.publisher,
            "tray_image": trayPNG.base64EncodedString(),
            "animated_sticker_pack": pack.animated,
            "image_data_version": String(pack.imageDataVersion),
            "stickers": list,
        ]
        return try JSONSerialization.data(withJSONObject: object, options: [])
    }
}
