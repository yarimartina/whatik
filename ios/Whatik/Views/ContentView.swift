import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        TabView(selection: $model.tab) {
            CaptureView()
                .tabItem { Label("Cattura", systemImage: "record.circle") }
                .tag(AppModel.Tab.capture)
            LibraryView()
                .tabItem { Label("Libreria", systemImage: "square.grid.2x2") }
                .tag(AppModel.Tab.library)
            PacksView()
                .tabItem { Label("Pack", systemImage: "shippingbox") }
                .tag(AppModel.Tab.packs)
            InfoView()
                .tabItem { Label("Info", systemImage: "info.circle") }
                .tag(AppModel.Tab.info)
        }
        .sheet(item: $model.analysis) { result in
            AnalysisView(result: result)
                .environmentObject(model)
        }
        .overlay {
            if let busy = model.busy {
                BusyOverlay(busy: busy)
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                Text(toast)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                    .padding(.horizontal, 16)
                    .padding(.bottom, 64)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .onTapGesture { model.toast = nil }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.toast)
    }
}

struct BusyOverlay: View {
    let busy: AppModel.Busy

    var body: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(spacing: 12) {
                Text(busy.title).font(.headline)
                if let progress = busy.progress {
                    ProgressView(value: progress).frame(width: 220)
                } else {
                    ProgressView()
                }
                if !busy.detail.isEmpty {
                    Text(busy.detail).font(.caption).foregroundColor(.secondary).lineLimit(2)
                }
            }
            .padding(24)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
            .padding(32)
        }
    }
}

/// Miniatura di un file immagine (anche WebP), caricata in background.
struct StickerThumb: View {
    let url: URL
    var maxPixel = 300
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image = image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                Color.clear
            }
        }
        .task(id: url) {
            image = await ThumbnailLoader.shared.image(for: url, maxPixel: maxPixel)
        }
    }
}

/// Cella della griglia di sticker con selezione e badge per gli animati.
struct StickerCell: View {
    let url: URL
    let animated: Bool
    var selected = false
    var selecting = false
    var highlighted = false

    var body: some View {
        StickerThumb(url: url)
            .padding(6)
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .background(Checkerboard().opacity(0.5))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(selected ? Color.accentColor : (highlighted ? Color.green : Color.clear), lineWidth: 3)
            )
            .overlay(alignment: .topTrailing) {
                if selecting {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundColor(selected ? .accentColor : .white)
                        .shadow(radius: 2)
                        .padding(6)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if animated {
                    Text("GIF")
                        .font(.caption2.bold())
                        .foregroundColor(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.black.opacity(0.6), in: Capsule())
                        .padding(6)
                }
            }
            .contentShape(Rectangle())
    }
}

/// Scacchiera chiara: rende visibile la trasparenza degli sticker.
struct Checkerboard: View {
    var body: some View {
        Canvas { context, size in
            let cell: CGFloat = 8
            let columns = Int(size.width / cell) + 1
            let rows = Int(size.height / cell) + 1
            for row in 0..<rows {
                for column in 0..<columns where (row + column) % 2 == 0 {
                    let rect = CGRect(x: CGFloat(column) * cell, y: CGFloat(row) * cell, width: cell, height: cell)
                    context.fill(Path(rect), with: .color(Color.gray.opacity(0.25)))
                }
            }
        }
        .background(Color(.secondarySystemBackground))
    }
}
