import SwiftUI
import PhotosUI
import CoreTransferable
import UniformTypeIdentifiers
import WhatikCore
import WhatikMedia

/// Stato dell'app: libreria, pack, lavoro in corso e messaggi.
@MainActor
final class AppModel: ObservableObject {
    enum Tab: Hashable { case capture, library, packs, info }

    struct Busy {
        var title: String
        var detail = ""
        var progress: Double?
    }

    @Published var tab: Tab = .capture
    @Published private(set) var items: [StickerItem] = []
    @Published private(set) var packs: [StickerPack] = []
    @Published var busy: Busy?
    @Published var toast: String?
    @Published var analysis: AnalysisResult?
    /// Sticker appena creati, evidenziati nella libreria.
    @Published var highlighted: Set<String> = []

    let library: StickerLibrary
    let store: PackStore
    let operations: PackOperations

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Whatik", isDirectory: true)
        library = StickerLibrary(root: base.appendingPathComponent("library", isDirectory: true))
        store = PackStore(root: base.appendingPathComponent("packs", isDirectory: true))
        operations = PackOperations(library: library, store: store, open: MediaDecoder.open)
        refresh()
        DemoMode.start(self)
    }

    func refresh() {
        items = library.items
        packs = store.loadAll()
    }

    func notify(_ text: String) {
        toast = text
        Task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            if self.toast == text { self.toast = nil }
        }
    }

    /// Lavoro pesante con il pannello di avanzamento; gli errori diventano un messaggio.
    func work<T>(_ title: String, _ job: @escaping (ProgressReporter) throws -> T) async -> T? {
        busy = Busy(title: title)
        let reporter = ProgressReporter { [weak self] fraction, detail in
            guard let self = self, self.busy != nil else { return }
            self.busy?.progress = fraction
            if !detail.isEmpty { self.busy?.detail = detail }
        }
        defer {
            busy = nil
            refresh()
        }
        do {
            return try await Background.run { try job(reporter) }
        } catch {
            notify(error.localizedDescription)
            return nil
        }
    }

    // MARK: importazione

    struct Incoming {
        var data: Data
        var name: String
    }

    func importData(_ incoming: [Incoming], source: String) async {
        guard !incoming.isEmpty else { return }
        let lib = library
        let results = await work("Importo \(incoming.count) immagini", { (progress: ProgressReporter) -> [StickerLibrary.ImportResult] in
            incoming.enumerated().map { i, entry in
                progress.report(i, incoming.count, entry.name)
                return lib.importData(entry.data, name: entry.name, source: source, probe: MediaDecoder.probe)
            }
        })
        guard let results = results else { return }
        report(results)
    }

    private func report(_ results: [StickerLibrary.ImportResult]) {
        var added = 0, duplicates = 0
        var failures: [String] = []
        for r in results {
            switch r {
            case .added(let item): added += 1; highlighted.insert(item.id)
            case .duplicate: duplicates += 1
            case .failed(let name, let reason): failures.append("\(name): \(reason)")
            }
        }
        var parts: [String] = []
        if added > 0 { parts.append(added == 1 ? "1 sticker aggiunto" : "\(added) sticker aggiunti") }
        if duplicates > 0 { parts.append(duplicates == 1 ? "1 già presente" : "\(duplicates) già presenti") }
        if !failures.isEmpty { parts.append("\(failures.count) non validi") }
        notify(parts.isEmpty ? "Niente da importare" : parts.joined(separator: ", "))
        if added > 0 { tab = .library }
    }

    /// Foto e video scelti dal rullino: le immagini vanno in libreria, un video viene analizzato.
    func importPhotos(_ picked: [PhotosPickerItem]) async {
        var incoming: [Incoming] = []
        var video: URL?
        for (i, item) in picked.enumerated() {
            let isMovie = item.supportedContentTypes.contains { $0.conforms(to: .movie) }
            if isMovie {
                if video == nil, let movie = try? await item.loadTransferable(type: PickedMovie.self) { video = movie.url }
                continue
            }
            if let data = try? await item.loadTransferable(type: Data.self) {
                incoming.append(Incoming(data: data, name: "Foto \(i + 1)"))
            }
        }
        await importData(incoming, source: "photos")
        if let video = video { await analyzeVideo(url: video) }
    }

    func importFiles(_ urls: [URL]) async {
        var incoming: [Incoming] = []
        var video: URL?
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .movie) {
                if video == nil {
                    let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + url.pathExtension)
                    if (try? FileManager.default.copyItem(at: url, to: copy)) != nil { video = copy }
                }
                continue
            }
            if let data = try? Data(contentsOf: url) {
                incoming.append(Incoming(data: data, name: url.lastPathComponent))
            }
        }
        await importData(incoming, source: "files")
        if let video = video { await analyzeVideo(url: video) }
    }

    /// Immagine o link dagli appunti.
    func pasteFromClipboard() async -> String? {
        let board = UIPasteboard.general
        let types = ["com.compuserve.gif", "org.webmproject.webp", "public.png", "public.jpeg", "public.heic", "public.image"]
        for type in types {
            if let data = board.data(forPasteboardType: type) {
                await importData([Incoming(data: data, name: "Incollato")], source: "paste")
                return nil
            }
        }
        if let image = board.image, let data = image.pngData() {
            await importData([Incoming(data: data, name: "Incollato")], source: "paste")
            return nil
        }
        if let text = board.string, let url = URLParsing.extractURL(text) { return url }
        notify("Negli appunti non c'è un'immagine né un link")
        return nil
    }

    func importRemote(_ candidates: [RemoteCandidate]) async {
        var incoming: [Incoming] = []
        busy = Busy(title: "Scarico \(candidates.count) immagini")
        for (i, candidate) in candidates.enumerated() {
            busy?.progress = Double(i) / Double(max(1, candidates.count))
            guard let url = URL(string: candidate.url), let data = try? await LinkImporter.download(url) else { continue }
            incoming.append(Incoming(data: data, name: candidate.name))
        }
        busy = nil
        if incoming.isEmpty { notify("Download non riuscito"); return }
        await importData(incoming, source: "link")
    }

    func deleteItems(_ ids: Set<String>) {
        library.delete(ids)
        highlighted.subtract(ids)
        refresh()
    }

    // MARK: analisi di registrazioni e screenshot

    func analyzeVideo(url: URL) async {
        let result = await work("Analizzo la registrazione", { progress in try Analyzer.analyzeVideo(url: url, progress: progress) })
        if let result = result { analysis = result }
    }

    func analyzePickedVideo(_ item: PhotosPickerItem) async {
        busy = Busy(title: "Carico la registrazione")
        let movie = try? await item.loadTransferable(type: PickedMovie.self)
        busy = nil
        guard let movie = movie else { notify("Impossibile leggere il video"); return }
        await analyzeVideo(url: movie.url)
    }

    func analyzePickedScreenshot(_ item: PhotosPickerItem) async {
        busy = Busy(title: "Carico lo screenshot")
        let data = try? await item.loadTransferable(type: Data.self)
        busy = nil
        guard let data = data else { notify("Impossibile leggere l'immagine"); return }
        let result = await work("Cerco gli sticker nello screenshot", { progress in try Analyzer.analyzeScreenshot(data: data, progress: progress) })
        if let result = result { analysis = result }
    }

    func createStickers(from result: AnalysisResult, selected: Set<UUID>) async {
        let chosen = result.candidates.filter { selected.contains($0.id) }
        guard !chosen.isEmpty else { return }
        analysis = nil
        let lib = library
        let created = await work("Creo gli sticker", { progress in try Analyzer.createStickers(result, chosen, library: lib, progress: progress) })
        guard let created = created else { return }
        highlighted.formUnion(created.items.map { $0.id })
        var text = created.items.count == 1 ? "1 sticker creato" : "\(created.items.count) sticker creati"
        if !created.failures.isEmpty { text += ", \(created.failures.count) non riusciti" }
        notify(text)
        if !created.items.isEmpty { tab = .library }
    }

    // MARK: modifica

    func saveEdit(item: StickerItem, crop: CropSpec, startMs: Int, endMs: Int, removeBackground: Bool, replaceOriginal: Bool) async {
        let lib = library
        let created = await work("Salvo lo sticker", { (_: ProgressReporter) -> StickerItem in
            var source: FrameSource = CroppedSource(try MediaDecoder.open(try lib.data(item)), crop: crop)
            if item.animated { source = TrimmedSource(source, startMs: min(startMs, endMs), endMs: max(startMs, endMs)) }
            if removeBackground { source = BackgroundRemovingSource(source) }
            let converted = try StickerConverter.convert(source, forceStatic: !item.animated)
            let result = lib.importData(converted.bytes, name: "\(item.displayName) ritaglio", source: "editor", probe: MediaDecoder.probe)
            guard let newItem = result.item else { throw WhatikError.conversion("Sticker non salvato") }
            if replaceOriginal, case .added = result { lib.delete([item.id]) }
            return newItem
        })
        if let created = created {
            highlighted.insert(created.id)
            notify("Sticker salvato")
        }
    }

    // MARK: pack

    func export(ids: [String], config: PackOperations.ExportConfig, target: StickerPack?) async -> [StickerPack] {
        let chosen = items.filter { ids.contains($0.id) }
        let plannerItems = chosen.map { PackPlanner.Item(id: $0.id, animated: $0.animated) }
        let t = target.map { PackPlanner.Target(identifier: $0.identifier, name: $0.name, animated: $0.animated, stickerCount: $0.stickers.count) }
        let plan = PackPlanner.plan(items: plannerItems, mixMode: config.mixMode,
                                    staticTarget: t?.animated == false ? t : nil,
                                    animatedTarget: t?.animated == true ? t : nil)
        let ops = operations
        let report = await work("Creo i pack", { progress in
            try ops.export(plan: plan, config: config) { done, total, name in progress.report(done, total, name) }
        })
        guard let report = report else { return [] }
        var text = report.packs.count == 1 ? "Pack pronto: \(report.packs[0].name)" : "\(report.packs.count) pack pronti"
        if !report.failures.isEmpty { text += " (\(report.failures.count) sticker non convertiti)" }
        notify(text)
        tab = .packs
        return report.packs
    }

    func addToPack(_ pack: StickerPack, itemIds: [String]) async {
        let ops = operations
        let emojis = pack.stickers.first?.emojis ?? ["😀"]
        let report = await work("Aggiungo al pack", { progress in
            try ops.addLibraryItems(packId: pack.identifier, itemIds: itemIds, emojis: emojis) { done, total in progress.report(done, total) }
        })
        guard let report = report else { return }
        var text = report.added == 1 ? "1 sticker aggiunto" : "\(report.added) sticker aggiunti"
        if report.skipped > 0 { text += ", \(report.skipped) oltre il limite di 30" }
        notify(text)
    }

    func removeFromPack(_ pack: StickerPack, fileNames: Set<String>) async {
        let store = self.store
        _ = await work("Rimuovo gli sticker", { _ in try store.removeStickers(pack.identifier, fileNames: fileNames) })
    }

    func rename(_ pack: StickerPack, name: String, publisher: String) async {
        let store = self.store
        _ = await work("Rinomino il pack", { _ in try store.rename(pack.identifier, name: name, publisher: publisher) })
    }

    func deletePack(_ pack: StickerPack) {
        store.delete(pack.identifier)
        refresh()
    }

    func merge(_ target: StickerPack, sources: [String], resultAnimated: Bool, deleteSources: Bool) async {
        let ops = operations
        let report = await work("Unisco i pack", { progress in
            try ops.merge(targetId: target.identifier, sourceIds: sources, resultAnimated: resultAnimated, deleteSources: deleteSources) { done, total in progress.report(done, total) }
        })
        guard let report = report else { return }
        notify(report.packs.count > 1 ? "Unione fatta: \(report.packs.count - 1) pack nuovi per gli sticker oltre i 30" : "Unione fatta")
    }

    func sendToWhatsApp(_ pack: StickerPack) async {
        guard pack.isAddable else {
            notify("WhatsApp vuole da 3 a 30 sticker per pack (qui ce ne sono \(pack.stickers.count))")
            return
        }
        let ops = operations
        let payload = await work("Preparo il pack per WhatsApp", { _ in try ops.whatsAppPayload(pack) })
        guard let payload = payload else { return }
        if !payload.problems.isEmpty {
            notify(payload.problems.joined(separator: "\n"))
            return
        }
        if !WhatsAppSender.send(payload.json) {
            notify("WhatsApp non risulta installato")
        }
    }

    func openTikTok() {
        for scheme in ["tiktok://", "snssdk1233://"] {
            if let url = URL(string: scheme), UIApplication.shared.canOpenURL(url) {
                UIApplication.shared.open(url)
                return
            }
        }
        if let web = URL(string: "https://www.tiktok.com") { UIApplication.shared.open(web) }
    }
}

/// Video scelto dal rullino, copiato in una cartella temporanea dell'app.
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + ext)
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedMovie(url: copy)
        }
    }
}
