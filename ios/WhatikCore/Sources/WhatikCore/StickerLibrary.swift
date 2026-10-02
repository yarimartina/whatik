import Foundation

/// Libreria degli sticker importati: file originali in <root>/ e un index.json.
public final class StickerLibrary: @unchecked Sendable {
    public static let maxImportBytes = 30 * 1024 * 1024

    /// Dimensioni e animazione di un file, ricavate dal decodificatore della piattaforma.
    public struct Probe: Sendable {
        public var width: Int
        public var height: Int
        public var animated: Bool
        public init(width: Int, height: Int, animated: Bool) { self.width = width; self.height = height; self.animated = animated }
    }

    public enum ImportResult: Sendable {
        case added(StickerItem)
        case duplicate(StickerItem)
        case failed(name: String, reason: String)

        public var item: StickerItem? {
            switch self {
            case .added(let item), .duplicate(let item): return item
            case .failed: return nil
            }
        }
    }

    public let root: URL
    private let indexURL: URL
    private let lock = NSLock()
    private var cached: [StickerItem]

    public init(root: URL) {
        self.root = root
        self.indexURL = root.appendingPathComponent("index.json")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        cached = []
        cached = loadIndex()
    }

    public var items: [StickerItem] { lock.lock(); defer { lock.unlock() }; return cached }

    public func find(_ id: String) -> StickerItem? { items.first { $0.id == id } }

    public func fileURL(_ item: StickerItem) -> URL { root.appendingPathComponent(item.fileName) }

    public func data(_ item: StickerItem) throws -> Data { try Data(contentsOf: fileURL(item)) }

    public func importData(_ data: Data, name: String, source: String, probe: (Data) throws -> Probe) -> ImportResult {
        if data.isEmpty { return .failed(name: name, reason: "File vuoto") }
        if data.count > StickerLibrary.maxImportBytes { return .failed(name: name, reason: "File troppo grande (max 30 MB)") }
        let kind = ImageKind.sniff(data)
        guard let info = try? probe(data), info.width > 0, info.height > 0 else {
            return .failed(name: name, reason: "Non è un'immagine supportata")
        }
        let hash = SHA256.hex(data)
        lock.lock(); defer { lock.unlock() }
        if let existing = cached.first(where: { $0.sha256 == hash }) { return .duplicate(existing) }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let fileName = "\(id).\(kind.fileExtension)"
        do {
            try data.write(to: root.appendingPathComponent(fileName))
        } catch {
            return .failed(name: name, reason: error.localizedDescription)
        }
        let item = StickerItem(
            id: id, fileName: fileName, displayName: String(StickerNames.stripExtension(name).prefix(80)),
            mimeType: kind.mimeType, animated: info.animated, width: info.width, height: info.height,
            sizeBytes: data.count, importedAt: Date(), source: source, sha256: hash
        )
        let updated = [item] + cached
        saveIndex(updated)
        cached = updated
        return .added(item)
    }

    public func delete(_ ids: Set<String>) {
        lock.lock(); defer { lock.unlock() }
        let removed = cached.filter { ids.contains($0.id) }
        for item in removed { try? FileManager.default.removeItem(at: fileURL(item)) }
        cached = cached.filter { !ids.contains($0.id) }
        saveIndex(cached)
    }

    public func rename(_ id: String, to name: String) {
        lock.lock(); defer { lock.unlock() }
        guard let i = cached.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return }
        cached[i].displayName = String(trimmed.prefix(80))
        saveIndex(cached)
    }

    private func loadIndex() -> [StickerItem] {
        guard let data = try? Data(contentsOf: indexURL), let items = try? PackStore.decoder.decode([StickerItem].self, from: data) else { return [] }
        return items.filter { FileManager.default.fileExists(atPath: root.appendingPathComponent($0.fileName).path) }
    }

    private func saveIndex(_ items: [StickerItem]) {
        if let data = try? PackStore.encoder.encode(items) { try? data.write(to: indexURL, options: .atomic) }
    }
}
