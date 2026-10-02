import Foundation
import WhatikCore

/// Crea i pack da una selezione della libreria ed esegue le operazioni sui pack esistenti
/// (aggiunta dalla libreria, unione, cambio di tipo). La decodifica dei file originali è
/// iniettata (`open`), perché su iOS passa da ImageIO.
public final class PackOperations: @unchecked Sendable {
    public typealias Opener = (Data) throws -> FrameSource

    public struct Failure: Sendable, Equatable { public var name: String; public var reason: String }

    public struct ExportConfig: Sendable {
        public var baseName: String
        public var publisher: String
        public var mixMode: PackPlanner.MixMode
        public var emojis: [String]
        public init(baseName: String, publisher: String, mixMode: PackPlanner.MixMode, emojis: [String]) {
            self.baseName = baseName; self.publisher = publisher; self.mixMode = mixMode; self.emojis = emojis
        }
    }

    public struct Report: Sendable {
        public var packs: [StickerPack]
        public var added: Int
        public var skipped: Int
        public var failures: [Failure]
    }

    private let library: StickerLibrary
    private let store: PackStore
    private let open: Opener

    public init(library: StickerLibrary, store: PackStore, open: @escaping Opener) {
        self.library = library
        self.store = store
        self.open = open
    }

    private func convert(_ item: StickerItem, packAnimated: Bool) throws -> StickerConverter.Result {
        let source = try open(try library.data(item))
        return try StickerConverter.convertForPack(source, packAnimated: packAnimated)
    }

    private func accessibility(_ item: StickerItem, animated: Bool) -> String { String(item.displayName.prefix(animated ? 255 : 125)) }

    /// Esegue un piano: converte ogni sticker e lo deposita nel pack previsto (nuovo o esistente).
    public func export(plan: PackPlanner.Plan, config: ExportConfig, progress: (Int, Int, String) -> Void) throws -> Report {
        let total = plan.packs.reduce(0) { $0 + $1.items.count }
        var done = 0
        var packs: [StickerPack] = []
        var failures: [Failure] = []
        var added = 0
        for planned in plan.packs {
            let targetId = try planned.existingIdentifier
                ?? store.createPack(name: PackPlanner.packName(baseName: config.baseName, plan: plan, pack: planned), publisher: config.publisher, animated: planned.animated).identifier
            var converted: [ConvertedSticker] = []
            for plannedItem in planned.items {
                guard let item = library.find(plannedItem.id) else {
                    failures.append(Failure(name: plannedItem.id, reason: "Sticker non più in libreria"))
                    done += 1
                    continue
                }
                progress(done, total, item.displayName)
                do {
                    let result = try convert(item, packAnimated: planned.animated)
                    if result.animated != planned.animated {
                        failures.append(Failure(name: item.displayName, reason: "risultato inatteso"))
                    } else {
                        converted.append(ConvertedSticker(bytes: result.bytes, animated: result.animated, emojis: config.emojis, accessibilityText: accessibility(item, animated: result.animated), sourceId: item.id))
                    }
                } catch {
                    failures.append(Failure(name: item.displayName, reason: error.localizedDescription))
                }
                done += 1
                progress(done, total, item.displayName)
            }
            if !converted.isEmpty {
                packs.append(try store.addStickers(targetId, converted, trayIcon: StickerConverter.makeTrayIcon))
                added += converted.count
            } else if planned.isNew {
                store.delete(targetId)
            } else if let pack = store.load(targetId) {
                packs.append(pack)
            }
        }
        return Report(packs: packs, added: added, skipped: 0, failures: failures)
    }

    /// Aggiunge sticker della libreria al pack, convertendoli al suo tipo, fino a 30.
    public func addLibraryItems(packId: String, itemIds: [String], emojis: [String], progress: (Int, Int) -> Void) throws -> Report {
        guard let pack = store.load(packId) else { throw WhatikError.storage("Pack inesistente") }
        let free = max(0, PackPlanner.maxStickers - pack.stickers.count)
        let chosen = Array(itemIds.prefix(free))
        var failures: [Failure] = []
        var converted: [ConvertedSticker] = []
        for (i, id) in chosen.enumerated() {
            progress(i, chosen.count)
            guard let item = library.find(id) else { continue }
            do {
                let result = try convert(item, packAnimated: pack.animated)
                converted.append(ConvertedSticker(bytes: result.bytes, animated: result.animated, emojis: emojis, accessibilityText: accessibility(item, animated: result.animated), sourceId: item.id))
            } catch {
                failures.append(Failure(name: item.displayName, reason: error.localizedDescription))
            }
        }
        var result = pack
        if !converted.isEmpty { result = try store.addStickers(packId, converted, trayIcon: StickerConverter.makeTrayIcon) }
        progress(chosen.count, chosen.count)
        return Report(packs: [result], added: converted.count, skipped: itemIds.count - chosen.count, failures: failures)
    }

    /// Converte tutti gli sticker di un pack al tipo indicato.
    @discardableResult
    public func convertPackType(_ pack: StickerPack, animated: Bool) throws -> StickerPack {
        if pack.animated == animated { return pack }
        let converted = try pack.stickers.map { sticker in (sticker, try StickerConverter.convertType(try store.readSticker(pack, sticker), animated: animated)) }
        let updated = try store.replaceAllStickers(pack.identifier, animated: animated, stickers: converted)
        try? FileManager.default.removeItem(at: store.trayURL(updated))
        try store.ensureTray(updated, trayIcon: StickerConverter.makeTrayIcon)
        return updated
    }

    /// Unisce i pack `sourceIds` dentro `targetId`; oltre i 30 sticker crea pack nuovi numerati.
    public func merge(targetId: String, sourceIds: [String], resultAnimated: Bool, deleteSources: Bool, progress: (Int, Int) -> Void) throws -> Report {
        guard var target = store.load(targetId) else { throw WhatikError.storage("Pack inesistente") }
        let sources = sourceIds.compactMap { store.load($0) }.filter { $0.identifier != targetId }
        let total = target.stickers.count + sources.reduce(0) { $0 + $1.stickers.count }
        var failures: [Failure] = []
        if target.animated != resultAnimated { target = try convertPackType(target, animated: resultAnimated) }
        var done = target.stickers.count
        progress(done, total)
        var incoming: [ConvertedSticker] = []
        for source in sources {
            for sticker in source.stickers {
                do {
                    let bytes = try StickerConverter.convertType(try store.readSticker(source, sticker), animated: resultAnimated)
                    incoming.append(ConvertedSticker(bytes: bytes, animated: resultAnimated, emojis: sticker.emojis, accessibilityText: sticker.accessibilityText, sourceId: sticker.sourceId))
                } catch {
                    failures.append(Failure(name: source.name, reason: error.localizedDescription))
                }
                done += 1
                progress(done, total)
            }
        }
        let free = max(0, PackPlanner.maxStickers - target.stickers.count)
        let toTarget = Array(incoming.prefix(free))
        var packs: [StickerPack] = []
        packs.append(toTarget.isEmpty ? target : try store.addStickers(targetId, toTarget, trayIcon: StickerConverter.makeTrayIcon))
        var rest = Array(incoming.dropFirst(free))
        var index = 2
        while !rest.isEmpty {
            let chunk = Array(rest.prefix(PackPlanner.maxStickers))
            rest = Array(rest.dropFirst(PackPlanner.maxStickers))
            let created = try store.createPack(name: "\(target.name) \(index)", publisher: target.publisher, animated: resultAnimated)
            packs.append(try store.addStickers(created.identifier, chunk, trayIcon: StickerConverter.makeTrayIcon))
            index += 1
        }
        if deleteSources { sources.forEach { store.delete($0.identifier) } }
        return Report(packs: packs, added: toTarget.count, skipped: 0, failures: failures)
    }

    /// Dati da passare a WhatsApp per un pack (icona rigenerata se manca).
    public func whatsAppPayload(_ pack: StickerPack) throws -> (json: Data, problems: [String]) {
        try store.ensureTray(pack, trayIcon: StickerConverter.makeTrayIcon)
        let tray = try Data(contentsOf: store.trayURL(pack))
        let stickers = try pack.stickers.map { WhatsAppPayload.Sticker(webp: try store.readSticker(pack, $0), emojis: $0.emojis, accessibilityText: $0.accessibilityText) }
        let problems = WhatsAppPayload.validate(pack: pack, trayPNG: tray, stickers: stickers)
        return (try WhatsAppPayload.json(pack: pack, trayPNG: tray, stickers: stickers), problems)
    }
}
