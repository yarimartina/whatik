import Foundation

/// Uno sticker importato nella libreria dell'app (file originale, non ancora convertito).
public struct StickerItem: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var id: String
    public var fileName: String
    public var displayName: String
    public var mimeType: String
    public var animated: Bool
    public var width: Int
    public var height: Int
    public var sizeBytes: Int
    public var importedAt: Date
    /// Da dove arriva: "photos", "files", "link", "paste", "recording", "screenshot", "editor".
    public var source: String
    public var sha256: String
}

/// Uno sticker già convertito e inserito in un pack.
public struct PackSticker: Codable, Equatable, Hashable, Sendable {
    public var fileName: String
    public var emojis: [String]
    public var accessibilityText: String?
    /// id dello `StickerItem` di origine, se ancora in libreria.
    public var sourceId: String?

    public init(fileName: String, emojis: [String], accessibilityText: String? = nil, sourceId: String? = nil) {
        self.fileName = fileName; self.emojis = emojis; self.accessibilityText = accessibilityText; self.sourceId = sourceId
    }
}

/// Un pack WhatsApp generato dall'app (cartella packs/<identifier>/).
public struct StickerPack: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var identifier: String
    public var name: String
    public var publisher: String
    public var animated: Bool
    public var stickers: [PackSticker]
    public var createdAt: Date
    public var updatedAt: Date
    public var trayFile: String = "tray.png"
    /// Incrementato a ogni modifica.
    public var imageDataVersion: Int = 1

    public var id: String { identifier }
    public var isAddable: Bool { stickers.count >= PackPlanner.minStickers && stickers.count <= PackPlanner.maxStickers }
}

/// Uno sticker convertito pronto da depositare in un pack.
public struct ConvertedSticker: Sendable {
    public var bytes: Data
    public var animated: Bool
    public var emojis: [String]
    public var accessibilityText: String?
    public var sourceId: String?

    public init(bytes: Data, animated: Bool, emojis: [String], accessibilityText: String?, sourceId: String?) {
        self.bytes = bytes; self.animated = animated; self.emojis = emojis; self.accessibilityText = accessibilityText; self.sourceId = sourceId
    }
}
