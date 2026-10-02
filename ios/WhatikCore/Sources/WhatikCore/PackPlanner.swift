import Foundation

/// Decide come distribuire gli sticker selezionati nei pack WhatsApp: da 3 a 30 sticker per
/// pack e pack composti solo da sticker statici oppure solo da sticker animati.
public enum PackPlanner {
    public static let minStickers = 3
    public static let maxStickers = 30

    public struct Item: Equatable, Hashable, Sendable {
        public var id: String
        public var animated: Bool
        public init(id: String, animated: Bool) { self.id = id; self.animated = animated }
    }

    /// Come trattare una selezione che mescola sticker fermi e animati.
    public enum MixMode: String, CaseIterable, Sendable {
        /// Un solo pack animato: gli sticker fermi diventano animazioni di due fotogrammi identici.
        case allAnimated
        /// Pack distinti per tipo, come richiede WhatsApp alla lettera.
        case separate
        /// Tutto statico: degli animati resta il primo fotogramma.
        case allStatic
    }

    public struct Target: Equatable, Sendable {
        public var identifier: String
        public var name: String
        public var animated: Bool
        public var stickerCount: Int
        public init(identifier: String, name: String, animated: Bool, stickerCount: Int) {
            self.identifier = identifier; self.name = name; self.animated = animated; self.stickerCount = stickerCount
        }
    }

    public struct PlannedPack: Equatable, Sendable {
        /// identificatore del pack esistente da estendere, oppure nil per un pack nuovo.
        public var existingIdentifier: String?
        public var animated: Bool
        public var items: [Item]
        /// numero di sticker che il pack avrà alla fine.
        public var resultingCount: Int
        /// progressivo (1-based) usato per nominare i pack nuovi quando sono più di uno.
        public var newIndex: Int
        public var isNew: Bool { existingIdentifier == nil }
        public var isAddable: Bool { resultingCount >= PackPlanner.minStickers && resultingCount <= PackPlanner.maxStickers }
    }

    public struct Plan: Equatable, Sendable {
        public var packs: [PlannedPack]
        public var newPackCount: Int { packs.filter { $0.isNew }.count }
        public var tooSmall: [PlannedPack] { packs.filter { !$0.isAddable } }
        public var staticCount: Int { packs.filter { !$0.animated }.reduce(0) { $0 + $1.items.count } }
        public var animatedCount: Int { packs.filter { $0.animated }.reduce(0) { $0 + $1.items.count } }
    }

    public static func plan(items: [Item], mixMode: MixMode, staticTarget: Target? = nil, animatedTarget: Target? = nil) -> Plan {
        let normalized: [Item]
        switch mixMode {
        case .allAnimated: normalized = items.map { Item(id: $0.id, animated: true) }
        case .allStatic: normalized = items.map { Item(id: $0.id, animated: false) }
        case .separate: normalized = items
        }
        var packs: [PlannedPack] = []
        var newIndex = 0
        for animated in [false, true] {
            let group = normalized.filter { $0.animated == animated }
            if group.isEmpty { continue }
            let target = animated ? animatedTarget : staticTarget
            var remaining = group[...]
            if let target = target, target.animated == animated {
                let free = max(0, maxStickers - target.stickerCount)
                let take = Array(remaining.prefix(free))
                if !take.isEmpty || free == 0 {
                    packs.append(PlannedPack(existingIdentifier: target.identifier, animated: animated, items: take, resultingCount: target.stickerCount + take.count, newIndex: 0))
                }
                remaining = remaining.dropFirst(take.count)
            }
            var chunks: [[Item]] = []
            var rest = Array(remaining)
            while !rest.isEmpty {
                chunks.append(Array(rest.prefix(maxStickers)))
                rest = Array(rest.dropFirst(maxStickers))
            }
            // l'ultimo pack nuovo non resta sotto il minimo: prende qualche sticker dal precedente
            if chunks.count >= 2 {
                let lastIndex = chunks.count - 1
                while chunks[lastIndex].count < minStickers && chunks[lastIndex - 1].count > minStickers {
                    let moved = chunks[lastIndex - 1].removeLast()
                    chunks[lastIndex].insert(moved, at: 0)
                }
            }
            for chunk in chunks {
                newIndex += 1
                packs.append(PlannedPack(existingIdentifier: nil, animated: animated, items: chunk, resultingCount: chunk.count, newIndex: newIndex))
            }
        }
        return Plan(packs: packs)
    }

    /// Nome del pack nuovo n-esimo: "Nome", "Nome 2", "Nome 3"... con suffisso per gli animati se serve.
    public static func packName(baseName: String, plan: Plan, pack: PlannedPack) -> String {
        let trimmed = baseName.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = trimmed.isEmpty ? "Sticker" : trimmed
        let hasBothKinds = plan.packs.contains { $0.isNew && $0.animated } && plan.packs.contains { $0.isNew && !$0.animated }
        let kind = hasBothKinds ? (pack.animated ? " animati" : " statici") : ""
        let sameKindNew = plan.packs.filter { $0.isNew && $0.animated == pack.animated }
        let position = sameKindNew.firstIndex(of: pack).map { $0 + 1 } ?? 1
        let ordinal = sameKindNew.count > 1 ? " \(position)" : ""
        return String((base + kind + ordinal).prefix(128))
    }
}
