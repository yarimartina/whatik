import Foundation

/// Pack WhatsApp generati dall'app. Ogni pack vive in <root>/<identifier>/ con un pack.json,
/// gli sticker .webp e l'icona tray.png. Le operazioni sono sincrone e protette da un lock:
/// l'interfaccia le chiama fuori dal thread principale.
public final class PackStore: @unchecked Sendable {
    public static let packJSON = "pack.json"
    public let root: URL
    private let lock = NSLock()
    private let fm = FileManager.default

    public init(root: URL) {
        self.root = root
        try? fm.createDirectory(at: root, withIntermediateDirectories: true)
    }

    public func packDir(_ identifier: String) -> URL { root.appendingPathComponent(identifier, isDirectory: true) }
    public func trayURL(_ pack: StickerPack) -> URL { packDir(pack.identifier).appendingPathComponent(pack.trayFile) }
    public func stickerURL(_ pack: StickerPack, _ sticker: PackSticker) -> URL { packDir(pack.identifier).appendingPathComponent(sticker.fileName) }

    public func loadAll() -> [StickerPack] {
        let dirs = (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        return dirs.compactMap { readPack($0.appendingPathComponent(PackStore.packJSON)) }.sorted { $0.updatedAt > $1.updatedAt }
    }

    public func load(_ identifier: String) -> StickerPack? {
        guard PackStore.isSafeIdentifier(identifier) else { return nil }
        return readPack(packDir(identifier).appendingPathComponent(PackStore.packJSON))
    }

    @discardableResult
    public func createPack(name: String, publisher: String, animated: Bool) throws -> StickerPack {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        let pack = StickerPack(
            identifier: PackStore.newIdentifier(), name: PackStore.sanitizeText(name, fallback: "Sticker"),
            publisher: PackStore.sanitizeText(publisher, fallback: "Whatik"), animated: animated,
            stickers: [], createdAt: now, updatedAt: now
        )
        try fm.createDirectory(at: packDir(pack.identifier), withIntermediateDirectories: true)
        try writePack(pack)
        return pack
    }

    /// Aggiunge sticker già convertiti al pack e crea l'icona se manca.
    @discardableResult
    public func addStickers(_ identifier: String, _ converted: [ConvertedSticker], trayIcon: (Data) throws -> Data) throws -> StickerPack {
        lock.lock(); defer { lock.unlock() }
        guard var pack = load(identifier) else { throw WhatikError.storage("Pack \(identifier) inesistente") }
        let dir = packDir(identifier)
        var added: [PackSticker] = []
        for (i, sticker) in converted.enumerated() {
            guard sticker.animated == pack.animated else { throw WhatikError.storage("Tipo di sticker incompatibile con il pack") }
            let fileName = String(format: "s_%d_%06x.webp", pack.stickers.count + i + 1, Int.random(in: 0..<0xFFFFFF))
            try sticker.bytes.write(to: dir.appendingPathComponent(fileName))
            added.append(PackSticker(fileName: fileName, emojis: sticker.emojis, accessibilityText: sticker.accessibilityText, sourceId: sticker.sourceId))
        }
        let tray = dir.appendingPathComponent(pack.trayFile)
        if !fm.fileExists(atPath: tray.path), let first = converted.first {
            try trayIcon(first.bytes).write(to: tray)
        }
        pack.stickers += added
        pack.updatedAt = Date()
        pack.imageDataVersion += 1
        try writePack(pack)
        return pack
    }

    @discardableResult
    public func removeStickers(_ identifier: String, fileNames: Set<String>) throws -> StickerPack? {
        lock.lock(); defer { lock.unlock() }
        guard var pack = load(identifier) else { return nil }
        let remaining = pack.stickers.filter { !fileNames.contains($0.fileName) }
        if remaining.count == pack.stickers.count { return pack }
        for name in fileNames { try? fm.removeItem(at: packDir(identifier).appendingPathComponent(name)) }
        let removedFirst = pack.stickers.first.map { fileNames.contains($0.fileName) } ?? false
        pack.stickers = remaining
        pack.updatedAt = Date()
        pack.imageDataVersion += 1
        try writePack(pack)
        // l'icona era quella del primo sticker: se è stato tolto, verrà rigenerata
        if removedFirst { try? fm.removeItem(at: trayURL(pack)) }
        return pack
    }

    public func readSticker(_ pack: StickerPack, _ sticker: PackSticker) throws -> Data {
        try Data(contentsOf: stickerURL(pack, sticker))
    }

    /// Rigenera l'icona del pack dal primo sticker, se manca.
    public func ensureTray(_ pack: StickerPack, trayIcon: (Data) throws -> Data) throws {
        let tray = trayURL(pack)
        guard !fm.fileExists(atPath: tray.path), let first = pack.stickers.first else { return }
        try trayIcon(try readSticker(pack, first)).write(to: tray)
    }

    /// Sostituisce tutti gli sticker (es. cambio di tipo statico/animato), conservando emoji e nomi.
    @discardableResult
    public func replaceAllStickers(_ identifier: String, animated: Bool, stickers: [(PackSticker, Data)]) throws -> StickerPack {
        lock.lock(); defer { lock.unlock() }
        guard var pack = load(identifier) else { throw WhatikError.storage("Pack \(identifier) inesistente") }
        let dir = packDir(identifier)
        let oldFiles = Set(pack.stickers.map { $0.fileName })
        var replaced: [PackSticker] = []
        for (i, entry) in stickers.enumerated() {
            let fileName = String(format: "s_%d_%06x.webp", i + 1, Int.random(in: 0..<0xFFFFFF))
            try entry.1.write(to: dir.appendingPathComponent(fileName))
            var meta = entry.0
            meta.fileName = fileName
            replaced.append(meta)
        }
        for name in oldFiles { try? fm.removeItem(at: dir.appendingPathComponent(name)) }
        pack.animated = animated
        pack.stickers = replaced
        pack.updatedAt = Date()
        pack.imageDataVersion += 1
        try writePack(pack)
        return pack
    }

    @discardableResult
    public func rename(_ identifier: String, name: String, publisher: String) throws -> StickerPack? {
        lock.lock(); defer { lock.unlock() }
        guard var pack = load(identifier) else { return nil }
        pack.name = PackStore.sanitizeText(name, fallback: pack.name)
        pack.publisher = PackStore.sanitizeText(publisher, fallback: pack.publisher)
        pack.updatedAt = Date()
        try writePack(pack)
        return pack
    }

    public func delete(_ identifier: String) {
        lock.lock(); defer { lock.unlock() }
        if PackStore.isSafeIdentifier(identifier) { try? fm.removeItem(at: packDir(identifier)) }
    }

    private func readPack(_ url: URL) -> StickerPack? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? PackStore.decoder.decode(StickerPack.self, from: data)
    }

    private func writePack(_ pack: StickerPack) throws {
        let dir = packDir(pack.identifier)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        try PackStore.encoder.encode(pack).write(to: dir.appendingPathComponent(PackStore.packJSON), options: .atomic)
    }

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        e.dateEncodingStrategy = .millisecondsSince1970
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }()

    static func newIdentifier() -> String {
        let time = String(Int(Date().timeIntervalSince1970 * 1000), radix: 36)
        let rand = String(Int.random(in: 0..<(36 * 36 * 36 * 36)), radix: 36)
        return "whatik_\(time)_\(String(repeating: "0", count: max(0, 4 - rand.count)))\(rand)"
    }

    public static func isSafeIdentifier(_ id: String) -> Bool {
        guard !id.isEmpty, id.count <= 128, !id.contains("..") else { return false }
        return id.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) && $0.isASCII || "_.-".unicodeScalars.contains($0) }
    }

    /// Nomi per WhatsApp: lettere, numeri, spazi e . , ' - _ (max 128 caratteri).
    public static func sanitizeText(_ text: String, fallback: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(.whitespaces).union(CharacterSet(charactersIn: "_-.,'"))
        var cleaned = String(String.UnicodeScalarView(text.unicodeScalars.filter { allowed.contains($0) }))
        while cleaned.contains("..") { cleaned = cleaned.replacingOccurrences(of: "..", with: ".") }
        cleaned = String(cleaned.trimmingCharacters(in: .whitespacesAndNewlines).prefix(128))
        return cleaned.isEmpty ? fallback : cleaned
    }
}
