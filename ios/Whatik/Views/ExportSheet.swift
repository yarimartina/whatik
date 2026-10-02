import SwiftUI
import WhatikCore
import WhatikMedia

/// Nome, autore, emoji, gestione dei tipi misti e destinazione dei nuovi pack.
struct ExportSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let ids: [String]
    var onDone: () -> Void = {}

    @State private var name = "TikTok"
    @State private var publisher = "Whatik"
    @State private var emoji = "😀"
    @State private var mix = PackPlanner.MixMode.allAnimated
    @State private var targetId = ""

    private var selected: [StickerItem] { model.items.filter { ids.contains($0.id) } }
    private var hasAnimated: Bool { selected.contains { $0.animated } }
    private var hasStatic: Bool { selected.contains { !$0.animated } }
    private var target: StickerPack? { model.packs.first { $0.identifier == targetId } }
    private var emojis: [String] {
        let list = emoji.filter { !$0.isWhitespace }.prefix(3).map { String($0) }
        return list.isEmpty ? ["😀"] : list
    }

    private var plan: PackPlanner.Plan {
        let t = target.map { PackPlanner.Target(identifier: $0.identifier, name: $0.name, animated: $0.animated, stickerCount: $0.stickers.count) }
        return PackPlanner.plan(
            items: selected.map { PackPlanner.Item(id: $0.id, animated: $0.animated) }, mixMode: effectiveMix,
            staticTarget: t?.animated == false ? t : nil, animatedTarget: t?.animated == true ? t : nil
        )
    }

    private var effectiveMix: PackPlanner.MixMode { hasAnimated && hasStatic ? mix : .separate }

    var body: some View {
        NavigationStack {
            Form {
                Section("Pack") {
                    TextField("Nome", text: $name)
                    TextField("Autore", text: $publisher)
                    TextField("Emoji (fino a 3)", text: $emoji)
                }
                if hasAnimated && hasStatic {
                    Section {
                        Picker("Fermi e animati", selection: $mix) {
                            Text("Un solo pack animato").tag(PackPlanner.MixMode.allAnimated)
                            Text("Pack separati per tipo").tag(PackPlanner.MixMode.separate)
                            Text("Tutto statico").tag(PackPlanner.MixMode.allStatic)
                        }
                        .pickerStyle(.inline)
                    } header: {
                        Text("Sticker fermi e animati insieme")
                    } footer: {
                        Text(mixExplanation)
                    }
                }
                Section("Destinazione") {
                    Picker("Aggiungi a", selection: $targetId) {
                        Text("Nuovo pack").tag("")
                        ForEach(model.packs.filter { $0.stickers.count < PackPlanner.maxStickers }) { pack in
                            Text("\(pack.name) (\(pack.stickers.count), \(pack.animated ? "animato" : "statico"))").tag(pack.identifier)
                        }
                    }
                }
                Section("Riepilogo") {
                    ForEach(Array(plan.packs.enumerated()), id: \.offset) { _, planned in
                        HStack {
                            Image(systemName: planned.animated ? "play.circle" : "photo")
                            Text(planned.isNew ? PackPlanner.packName(baseName: name, plan: plan, pack: planned) : (target?.name ?? "Pack esistente"))
                            Spacer()
                            Text("+\(planned.items.count) → \(planned.resultingCount)").foregroundColor(planned.isAddable ? .secondary : .orange)
                        }
                    }
                    if !plan.tooSmall.isEmpty {
                        Label("WhatsApp accetta pack da 3 a 30 sticker: i pack con meno di 3 sticker si creano ma non si possono ancora aggiungere.", systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundColor(.orange)
                    }
                }
            }
            .navigationTitle("Copia su WhatsApp")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annulla") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Crea") { create() }.disabled(selected.isEmpty)
                }
            }
        }
    }

    private var mixExplanation: String {
        switch mix {
        case .allAnimated: return "Come Sticker Maker: gli sticker fermi diventano animazioni di due fotogrammi identici, così stanno nello stesso pack degli animati."
        case .separate: return "Un pack per gli statici e uno per gli animati, come chiede WhatsApp alla lettera."
        case .allStatic: return "Degli sticker animati resta solo il primo fotogramma."
        }
    }

    private func create() {
        let config = PackOperations.ExportConfig(baseName: name, publisher: publisher, mixMode: effectiveMix, emojis: emojis)
        let ids = self.ids
        let target = self.target
        onDone()
        dismiss()
        Task { _ = await model.export(ids: ids, config: config, target: target) }
    }
}
