import SwiftUI
import WhatikCore
import WhatikMedia

/// Ritaglio di uno sticker della libreria e, per gli animati, scelta dell'intervallo da tenere.
struct EditorView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let item: StickerItem

    @State private var preview: UIImage?
    @State private var loadError: String?
    @State private var crop = CropSpec.full
    @State private var durationMs = 0
    @State private var startMs = 0.0
    @State private var endMs = 0.0
    @State private var removeBackground = false
    @State private var replaceOriginal = false

    struct Loaded {
        var image: UIImage
        var durationMs: Int
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                ZStack {
                    Color.black
                    if let preview = preview {
                        CropEditor(image: preview, crop: $crop).padding(12)
                    } else if let loadError = loadError {
                        Text(loadError).foregroundColor(.white).padding()
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))

                HStack {
                    Button("Tutta l'immagine") { crop = .full }
                    Spacer()
                    Button("Rendi quadrato") { makeSquare() }
                }
                .buttonStyle(.bordered)

                if item.animated && durationMs > 0 {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(String(format: "Intervallo: %.1f – %.1f s di %.1f s", startMs / 1000, endMs / 1000, Double(durationMs) / 1000))
                            .font(.subheadline)
                        HStack {
                            Text("Inizio").font(.caption).frame(width: 40, alignment: .leading)
                            Slider(value: $startMs, in: 0...Double(durationMs))
                        }
                        HStack {
                            Text("Fine").font(.caption).frame(width: 40, alignment: .leading)
                            Slider(value: $endMs, in: 0...Double(durationMs))
                        }
                    }
                }
                Toggle("Rendi trasparente lo sfondo uniforme", isOn: $removeBackground)
                Toggle("Sostituisci l'originale", isOn: $replaceOriginal)
            }
            .padding()
            .navigationTitle(item.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annulla") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Salva") { save() }.disabled(preview == nil)
                }
            }
            .task { await load() }
        }
    }

    private func load() async {
        let library = model.library
        let item = self.item
        do {
            let loaded = try await Background.run { () -> Loaded in
                let source = try MediaDecoder.open(try library.data(item))
                guard let image = try source.firstFrame().uiImage else { throw WhatikError.unsupportedImage }
                return Loaded(image: image, durationMs: source.durationsMs.reduce(0, +))
            }
            preview = loaded.image
            durationMs = loaded.durationMs
            endMs = Double(loaded.durationMs)
        } catch {
            loadError = error.localizedDescription
        }
    }

    /// Quadrato in pixel, centrato sul riquadro attuale.
    private func makeSquare() {
        guard let preview = preview else { return }
        let w = Float(preview.size.width), h = Float(preview.size.height)
        let side = min(crop.width * w, crop.height * h)
        let cx = crop.cx * w, cy = crop.cy * h
        crop = CropSpec(left: (cx - side / 2) / w, top: (cy - side / 2) / h, right: (cx + side / 2) / w, bottom: (cy + side / 2) / h).normalized()
    }

    private func save() {
        let crop = self.crop
        let start = Int(startMs), end = Int(endMs)
        let remove = removeBackground, replace = replaceOriginal
        let item = self.item
        dismiss()
        Task { await model.saveEdit(item: item, crop: crop, startMs: start, endMs: end, removeBackground: remove, replaceOriginal: replace) }
    }
}
