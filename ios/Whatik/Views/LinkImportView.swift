import SwiftUI
import WhatikCore

/// Importazione da un link: file diretto o pagina in cui cercare le immagini.
struct LinkImportView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    var initialText = ""

    @State private var text = ""
    @State private var candidates: [RemoteCandidate] = []
    @State private var chosen = Set<String>()
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://…", text: $text)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Button("Incolla") { text = UIPasteboard.general.string ?? text }
                        Spacer()
                        Button("Cerca") { Task { await search() } }
                            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty || loading)
                    }
                } footer: {
                    Text("Link diretto a un file .webp, .awebp, .gif o .png, oppure una pagina da cui elencare le immagini. Le pagine di TikTok spesso bloccano le richieste automatiche.")
                }
                if loading {
                    HStack { Spacer(); ProgressView(); Spacer() }
                }
                if let error = error {
                    Text(error).foregroundColor(.red)
                }
                if !candidates.isEmpty {
                    Section("Immagini trovate (\(candidates.count))") {
                        ForEach(candidates) { candidate in
                            HStack(spacing: 12) {
                                AsyncImage(url: URL(string: candidate.url)) { image in
                                    image.resizable().scaledToFit()
                                } placeholder: {
                                    Color.gray.opacity(0.15)
                                }
                                .frame(width: 56, height: 56)
                                VStack(alignment: .leading) {
                                    Text(candidate.name).lineLimit(1)
                                    if candidate.looksSticker {
                                        Text("probabile sticker").font(.caption).foregroundColor(.secondary)
                                    }
                                }
                                Spacer()
                                Image(systemName: chosen.contains(candidate.url) ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(.accentColor)
                            }
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if chosen.contains(candidate.url) { chosen.remove(candidate.url) } else { chosen.insert(candidate.url) }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Da link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if !candidates.isEmpty {
                        Button("Importa (\(chosen.count))") {
                            let picked = candidates.filter { chosen.contains($0.url) }
                            dismiss()
                            Task { await model.importRemote(picked) }
                        }
                        .disabled(chosen.isEmpty)
                    }
                }
            }
            .onAppear {
                if text.isEmpty { text = initialText }
                if !initialText.isEmpty { Task { await search() } }
            }
        }
    }

    private func search() async {
        guard let link = URLParsing.extractURL(text) else {
            error = "Nessun link valido"
            return
        }
        error = nil
        loading = true
        let fetched = await LinkImporter.fetch(link)
        loading = false
        switch fetched {
        case .image(let data, let name):
            dismiss()
            await model.importData([AppModel.Incoming(data: data, name: name)], source: "link")
        case .page(let found):
            candidates = found
            chosen = Set(found.filter { $0.looksSticker }.map { $0.url })
            if found.isEmpty { error = "Nessuna immagine trovata nella pagina" }
        case .failure(let message):
            error = message
        }
    }
}
