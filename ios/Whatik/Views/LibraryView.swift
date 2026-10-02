import SwiftUI
import PhotosUI
import WhatikCore

struct ExportRequest: Identifiable {
    let id = UUID()
    let ids: [String]
}

struct LibraryView: View {
    @EnvironmentObject var model: AppModel
    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var linkPrefill: LinkPrefill?
    @State private var editing: StickerItem?
    @State private var exportRequest: ExportRequest?
    @State private var confirmDelete = false

    private let columns = [GridItem(.adaptive(minimum: 100), spacing: 8)]

    var body: some View {
        NavigationStack {
            ScrollView {
                if model.items.isEmpty {
                    emptyState
                } else {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(model.items) { item in
                            StickerCell(
                                url: model.library.fileURL(item), animated: item.animated,
                                selected: selection.contains(item.id), selecting: selecting,
                                highlighted: model.highlighted.contains(item.id)
                            )
                            .onTapGesture {
                                if selecting { toggle(item) } else { editing = item }
                            }
                            .contextMenu {
                                Button { editing = item } label: { Label("Ritaglia", systemImage: "crop") }
                                Button {
                                    selecting = true
                                    selection.insert(item.id)
                                } label: { Label("Seleziona", systemImage: "checkmark.circle") }
                                Button(role: .destructive) { model.deleteItems([item.id]) } label: { Label("Elimina", systemImage: "trash") }
                            }
                        }
                    }
                    .padding(8)
                }
            }
            .navigationTitle("Libreria")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    if selecting {
                        Button(selection.count == model.items.count ? "Nessuno" : "Tutti") {
                            if selection.count == model.items.count { selection.removeAll() } else { selection = Set(model.items.map { $0.id }) }
                        }
                    }
                }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    if !model.items.isEmpty {
                        Button(selecting ? "Fine" : "Seleziona") {
                            selecting.toggle()
                            if !selecting { selection.removeAll() }
                        }
                    }
                    addMenu
                }
            }
            .safeAreaInset(edge: .bottom) {
                if selecting && !selection.isEmpty {
                    HStack(spacing: 12) {
                        Button(role: .destructive) { confirmDelete = true } label: {
                            Image(systemName: "trash").frame(width: 44, height: 44)
                        }
                        .buttonStyle(.bordered)
                        Button {
                            exportRequest = ExportRequest(ids: model.items.map { $0.id }.filter { selection.contains($0) })
                        } label: {
                            Label("Copia su WhatsApp (\(selection.count))", systemImage: "paperplane.fill")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding()
                    .background(.bar)
                }
            }
            .confirmationDialog("Eliminare \(selection.count) sticker dalla libreria?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Elimina", role: .destructive) {
                    model.deleteItems(selection)
                    selection.removeAll()
                    selecting = false
                }
            }
            .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 50, matching: .any(of: [.images, .videos]))
            .onChange(of: photoItems) { items in
                guard !items.isEmpty else { return }
                let picked = items
                photoItems = []
                Task { await model.importPhotos(picked) }
            }
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.image, .movie], allowsMultipleSelection: true) { result in
                if case .success(let urls) = result {
                    Task { await model.importFiles(urls) }
                }
            }
            .sheet(item: $editing) { item in
                EditorView(item: item).environmentObject(model)
            }
            .sheet(item: $exportRequest) { request in
                ExportSheet(ids: request.ids) {
                    selecting = false
                    selection.removeAll()
                }
                .environmentObject(model)
            }
            .sheet(item: $linkPrefill) { prefill in
                LinkImportView(initialText: prefill.text).environmentObject(model)
            }
            .onDisappear { model.highlighted.removeAll() }
        }
    }

    private var addMenu: some View {
        Menu {
            Button { showPhotos = true } label: { Label("Dal rullino", systemImage: "photo.on.rectangle") }
            Button { showFiles = true } label: { Label("Da File", systemImage: "folder") }
            Button { linkPrefill = LinkPrefill(text: "") } label: { Label("Da link", systemImage: "link") }
            Button {
                Task {
                    if let link = await model.pasteFromClipboard() { linkPrefill = LinkPrefill(text: link) }
                }
            } label: { Label("Incolla", systemImage: "doc.on.clipboard") }
        } label: {
            Image(systemName: "plus")
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "face.smiling").font(.system(size: 48)).foregroundColor(.secondary)
            Text("Nessuno sticker").font(.title3.bold())
            Text("Cattura gli sticker dalla scheda Cattura, oppure importa immagini, GIF e WebP con il pulsante +.")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
            Button("Vai a Cattura") { model.tab = .capture }.buttonStyle(.borderedProminent)
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }

    private func toggle(_ item: StickerItem) {
        if selection.contains(item.id) { selection.remove(item.id) } else { selection.insert(item.id) }
    }
}

struct LinkPrefill: Identifiable {
    let id = UUID()
    let text: String
}
