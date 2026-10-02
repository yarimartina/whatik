import SwiftUI
import PhotosUI

struct CaptureView: View {
    @EnvironmentObject var model: AppModel
    @State private var videoItem: PhotosPickerItem?
    @State private var imageItem: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("Gli sticker che salvi su TikTok scorrendo i video non si possono scaricare: Whatik li ricava dallo schermo e li trasforma in pack per WhatsApp.")
                        .foregroundColor(.secondary)

                    card(title: "Pannello completo", subtitle: "Consigliato: prende tutti gli sticker che vedi, fermi e animati") {
                        step(1, "Su TikTok apri i commenti di un video, tocca l'icona degli sticker e scegli Salvati o Usati di recente.")
                        step(2, "Avvia la registrazione dello schermo dal Centro di Controllo (se manca: Impostazioni → Centro di Controllo → Registrazione schermo).")
                        step(3, "Torna sul pannello e tienilo fermo 6–8 secondi senza scorrere: Whatik deve vedere almeno due giri di ogni animazione.")
                        step(4, "Ferma la registrazione e scegli il video qui sotto. Whatik trova le tessere del pannello, capisce quali sono animate e quanto dura il loop, rende trasparente lo sfondo bianco.")
                        PhotosPicker(selection: $videoItem, matching: .videos) {
                            Label("Scegli la registrazione", systemImage: "film")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    }

                    card(title: "Screenshot del pannello", subtitle: "Più veloce, ma solo sticker fermi") {
                        Text("Fai uno screenshot del pannello degli sticker: ogni tessera diventa uno sticker con lo sfondo trasparente.")
                            .font(.subheadline)
                        PhotosPicker(selection: $imageItem, matching: .images) {
                            Label("Scegli lo screenshot", systemImage: "photo")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                    }

                    Button {
                        model.openTikTok()
                    } label: {
                        Label("Apri TikTok", systemImage: "arrow.up.forward.app")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    Text("Su Android Whatik mostra una bolla sopra TikTok e cattura dal vivo. iOS non permette alle app di disegnare sopra le altre né di registrare lo schermo da sole, quindi qui si usa il registratore di sistema e l'analisi avviene dopo, con la stessa logica.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                .padding()
            }
            .navigationTitle("Cattura")
            .onChange(of: videoItem) { item in
                guard let item = item else { return }
                videoItem = nil
                Task { await model.analyzePickedVideo(item) }
            }
            .onChange(of: imageItem) { item in
                guard let item = item else { return }
                imageItem = nil
                Task { await model.analyzePickedScreenshot(item) }
            }
        }
    }

    private func card<Content: View>(title: String, subtitle: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.title3.bold())
            Text(subtitle).font(.subheadline).foregroundColor(.secondary)
            content()
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private func step(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(number)")
                .font(.caption.bold())
                .foregroundColor(.white)
                .frame(width: 22, height: 22)
                .background(Color.accentColor, in: Circle())
            Text(text).font(.subheadline)
        }
    }
}

struct AnalysisView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let result: AnalysisResult
    @State private var selected: Set<UUID> = []

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 10)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let message = result.message {
                        Label(message, systemImage: "info.circle")
                            .font(.subheadline)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.yellow.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
                    }
                    if result.candidates.isEmpty, case .image(_, let data) = result.source {
                        Button {
                            dismiss()
                            Task { await model.importData([AppModel.Incoming(data: data, name: "Screenshot")], source: "screenshot") }
                        } label: {
                            Label("Importa lo screenshot per ritagliarlo", systemImage: "crop")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(result.candidates) { candidate in
                            candidateCell(candidate)
                                .onTapGesture {
                                    if selected.contains(candidate.id) { selected.remove(candidate.id) } else { selected.insert(candidate.id) }
                                }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(result.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annulla") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Crea (\(selected.count))") {
                        let chosen = selected
                        Task { await model.createStickers(from: result, selected: chosen) }
                    }
                    .disabled(selected.isEmpty)
                }
                ToolbarItem(placement: .bottomBar) {
                    if !result.candidates.isEmpty {
                        Button(selected.count == result.candidates.count ? "Deseleziona tutto" : "Seleziona tutto") {
                            if selected.count == result.candidates.count { selected.removeAll() } else { selected = Set(result.candidates.map { $0.id }) }
                        }
                    }
                }
            }
            .onAppear { selected = Set(result.candidates.map { $0.id }) }
        }
    }

    private func candidateCell(_ candidate: Candidate) -> some View {
        let isSelected = selected.contains(candidate.id)
        return Image(uiImage: candidate.preview)
            .resizable()
            .scaledToFit()
            .padding(4)
            .frame(maxWidth: .infinity)
            .aspectRatio(1, contentMode: .fit)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(isSelected ? Color.accentColor : Color.clear, lineWidth: 3))
            .overlay(alignment: .topTrailing) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundColor(isSelected ? .accentColor : .white)
                    .shadow(radius: 2)
                    .padding(6)
            }
            .overlay(alignment: .bottomLeading) {
                Text(candidate.animated ? String(format: "%.1f s", Double(candidate.endMs - candidate.startMs) / 1000) : "fermo")
                    .font(.caption2.bold())
                    .foregroundColor(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(candidate.animated ? Color.pink.opacity(0.85) : Color.black.opacity(0.6), in: Capsule())
                    .padding(6)
            }
            .contentShape(Rectangle())
    }
}
