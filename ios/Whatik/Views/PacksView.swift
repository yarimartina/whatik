import SwiftUI
import WhatikCore

struct PacksView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        NavigationStack {
            Group {
                if model.packs.isEmpty {
                    VStack(spacing: 14) {
                        Image(systemName: "shippingbox").font(.system(size: 48)).foregroundColor(.secondary)
                        Text("Nessun pack").font(.title3.bold())
                        Text("Seleziona gli sticker in Libreria e tocca Copia su WhatsApp.")
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                    .padding(32)
                } else {
                    List {
                        ForEach(model.packs) { pack in
                            NavigationLink(value: pack.identifier) {
                                PackRow(pack: pack, trayURL: model.store.trayURL(pack))
                            }
                        }
                    }
                }
            }
            .navigationTitle("Pack")
            .navigationDestination(for: String.self) { identifier in
                PackDetailView(packId: identifier)
            }
        }
    }
}

struct PackRow: View {
    let pack: StickerPack
    let trayURL: URL

    var body: some View {
        HStack(spacing: 12) {
            StickerThumb(url: trayURL, maxPixel: 96)
                .frame(width: 48, height: 48)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text(pack.name).font(.headline)
                Text("\(pack.stickers.count) sticker · \(pack.animated ? "animato" : "statico") · \(pack.publisher)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                if !pack.isAddable {
                    Text(pack.stickers.count < PackPlanner.minStickers ? "Servono almeno 3 sticker" : "Massimo 30 sticker")
                        .font(.caption)
                        .foregroundColor(.orange)
                }
            }
        }
    }
}

struct PackDetailView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let packId: String

    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var showAdd = false
    @State private var showMerge = false
    @State private var showRename = false
    @State private var confirmDelete = false

    private let columns = [GridItem(.adaptive(minimum: 84), spacing: 8)]
    private var pack: StickerPack? { model.packs.first { $0.identifier == packId } }

    var body: some View {
        Group {
            if let pack = pack {
                content(pack)
            } else {
                Text("Pack eliminato").foregroundColor(.secondary)
            }
        }
    }

    private func content(_ pack: StickerPack) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("\(pack.stickers.count) sticker · \(pack.animated ? "animato" : "statico") · di \(pack.publisher)")
                    .foregroundColor(.secondary)
                LazyVGrid(columns: columns, spacing: 8) {
                    ForEach(pack.stickers, id: \.fileName) { sticker in
                        StickerCell(url: model.store.stickerURL(pack, sticker), animated: pack.animated, selected: selection.contains(sticker.fileName), selecting: selecting)
                            .onTapGesture {
                                guard selecting else { return }
                                if selection.contains(sticker.fileName) { selection.remove(sticker.fileName) } else { selection.insert(sticker.fileName) }
                            }
                    }
                }
                if !pack.isAddable {
                    Label(pack.stickers.count < PackPlanner.minStickers
                          ? "WhatsApp vuole almeno 3 sticker per pack: aggiungine altri dalla libreria."
                          : "WhatsApp accetta al massimo 30 sticker per pack.", systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundColor(.orange)
                }
            }
            .padding()
        }
        .navigationTitle(pack.name)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 12) {
                if selecting {
                    Button(role: .destructive) {
                        let names = selection
                        selection.removeAll()
                        selecting = false
                        Task { await model.removeFromPack(pack, fileNames: names) }
                    } label: {
                        Label("Rimuovi (\(selection.count))", systemImage: "trash").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .disabled(selection.isEmpty)
                } else {
                    Button {
                        Task { await model.sendToWhatsApp(pack) }
                    } label: {
                        Label("Aggiungi a WhatsApp", systemImage: "paperplane.fill").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!pack.isAddable)
                }
            }
            .padding()
            .background(.bar)
        }
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button(selecting ? "Fine" : "Seleziona") {
                    selecting.toggle()
                    if !selecting { selection.removeAll() }
                }
                Menu {
                    Button { showAdd = true } label: { Label("Aggiungi dalla libreria", systemImage: "plus") }
                        .disabled(pack.stickers.count >= PackPlanner.maxStickers)
                    Button { showRename = true } label: { Label("Rinomina", systemImage: "pencil") }
                    Button { showMerge = true } label: { Label("Unisci con altri pack", systemImage: "arrow.triangle.merge") }
                        .disabled(model.packs.count < 2)
                    Button(role: .destructive) { confirmDelete = true } label: { Label("Elimina pack", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .confirmationDialog("Eliminare il pack \(pack.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Elimina", role: .destructive) {
                model.deletePack(pack)
                dismiss()
            }
        } message: {
            Text("Il pack sparisce da Whatik; se l'hai già aggiunto, su WhatsApp resta finché non lo togli lì.")
        }
        .sheet(isPresented: $showAdd) { AddFromLibrarySheet(pack: pack).environmentObject(model) }
        .sheet(isPresented: $showRename) { RenameSheet(pack: pack).environmentObject(model) }
        .sheet(isPresented: $showMerge) { MergeSheet(pack: pack).environmentObject(model) }
    }
}

struct AddFromLibrarySheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let pack: StickerPack
    @State private var selection = Set<String>()

    private let columns = [GridItem(.adaptive(minimum: 84), spacing: 8)]
    private var available: [StickerItem] {
        let inPack = Set(pack.stickers.compactMap { $0.sourceId })
        return model.items.filter { !inPack.contains($0.id) }
    }
    private var free: Int { max(0, PackPlanner.maxStickers - pack.stickers.count) }

    var body: some View {
        NavigationStack {
            ScrollView {
                Text("Posti liberi: \(free). Gli sticker vengono convertiti in \(pack.animated ? "animati (i fermi diventano due fotogrammi)" : "statici (degli animati resta il primo fotogramma)").")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)
                LazyVGrid(columns: columns, spacing: 8) {
                    ForEach(available) { item in
                        StickerCell(url: model.library.fileURL(item), animated: item.animated, selected: selection.contains(item.id), selecting: true)
                            .onTapGesture {
                                if selection.contains(item.id) { selection.remove(item.id) } else if selection.count < free { selection.insert(item.id) }
                            }
                    }
                }
                .padding()
            }
            .navigationTitle("Aggiungi al pack")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Aggiungi (\(selection.count))") {
                        let ids = model.items.map { $0.id }.filter { selection.contains($0) }
                        dismiss()
                        Task { await model.addToPack(pack, itemIds: ids) }
                    }
                    .disabled(selection.isEmpty)
                }
            }
        }
    }
}

struct RenameSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let pack: StickerPack
    @State private var name = ""
    @State private var publisher = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Nome", text: $name)
                TextField("Autore", text: $publisher)
            }
            .navigationTitle("Rinomina")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Salva") {
                        let n = name, p = publisher
                        dismiss()
                        Task { await model.rename(pack, name: n, publisher: p) }
                    }
                }
            }
            .onAppear {
                name = pack.name
                publisher = pack.publisher
            }
        }
    }
}

struct MergeSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let pack: StickerPack
    @State private var chosen = Set<String>()
    @State private var resultAnimated = true
    @State private var deleteSources = true

    private var others: [StickerPack] { model.packs.filter { $0.identifier != pack.identifier } }
    private var chosenPacks: [StickerPack] { others.filter { chosen.contains($0.identifier) } }
    private var mixed: Bool { chosenPacks.contains { $0.animated != pack.animated } }
    private var total: Int { pack.stickers.count + chosenPacks.reduce(0) { $0 + $1.stickers.count } }

    var body: some View {
        NavigationStack {
            Form {
                Section("Pack da unire a \(pack.name)") {
                    ForEach(others) { other in
                        Button {
                            if chosen.contains(other.identifier) { chosen.remove(other.identifier) } else { chosen.insert(other.identifier) }
                        } label: {
                            HStack {
                                PackRow(pack: other, trayURL: model.store.trayURL(other))
                                Spacer()
                                Image(systemName: chosen.contains(other.identifier) ? "checkmark.circle.fill" : "circle")
                            }
                        }
                        .foregroundColor(.primary)
                    }
                }
                if mixed {
                    Section {
                        Picker("Risultato", selection: $resultAnimated) {
                            Text("Animato").tag(true)
                            Text("Statico").tag(false)
                        }
                        .pickerStyle(.segmented)
                    } footer: {
                        Text(resultAnimated ? "Gli sticker fermi diventano animazioni di due fotogrammi." : "Degli sticker animati resta il primo fotogramma.")
                    }
                }
                Section {
                    Toggle("Elimina i pack uniti", isOn: $deleteSources)
                } footer: {
                    Text(total > PackPlanner.maxStickers
                         ? "Totale \(total) sticker: oltre i 30 finiscono in pack nuovi numerati."
                         : "Totale \(total) sticker.")
                }
            }
            .navigationTitle("Unisci")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Unisci") {
                        let sources = chosenPacks.map { $0.identifier }
                        let animated = mixed ? resultAnimated : pack.animated
                        let delete = deleteSources
                        dismiss()
                        Task { await model.merge(pack, sources: sources, resultAnimated: animated, deleteSources: delete) }
                    }
                    .disabled(chosen.isEmpty)
                }
            }
            .onAppear { resultAnimated = true }
        }
    }
}
