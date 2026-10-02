import UIKit
import WhatikCore
import WhatikMedia

/// Uno sticker trovato in una registrazione o in uno screenshot.
struct Candidate: Identifiable {
    let id = UUID()
    var crop: CropSpec
    var startMs: Int
    var endMs: Int
    var animated: Bool
    var preview: UIImage
}

struct AnalysisResult: Identifiable {
    enum Source {
        case video(URL)
        case image(Raster, Data)
    }

    let id = UUID()
    var title: String
    var candidates: [Candidate]
    var source: Source
    /// Colore del pannello: serve a rendere trasparente lo sfondo delle tessere.
    var backgroundHint: ARGB?
    var message: String?
}

struct CreationReport {
    var items: [StickerItem]
    var failures: [String]
}

/// La stessa logica della modalità "Tutti" di Android, applicata a una registrazione dello
/// schermo: tessere del pannello, loop per le animate, sfondo trasparente.
enum Analyzer {
    static let analysisWidth = 160
    static let gridWidth = 480
    static let sampleIntervalMs = 100
    static let maxAnalysisMs = 12_000

    private struct GridPick {
        var result: TileGridFinder.Result
        var frame: Raster
        var timeMs: Int
    }

    static func analyzeVideo(url: URL, progress: ProgressReporter) throws -> AnalysisResult {
        let reader = try VideoReader(url: url)
        let end = min(reader.durationMs, maxAnalysisMs)
        guard end >= 1500 else {
            throw WhatikError.conversion("Registrazione troppo corta: tieni il pannello fermo almeno 3 secondi")
        }

        // 1) tessere del pannello: si prova qualche istante e si tiene quello con più tessere
        progress.report(0, 100, "Cerco il pannello degli sticker")
        var best: GridPick?
        for t in [end / 2, end / 4, end * 3 / 4] {
            guard let frame = try reader.frame(atMs: t, crop: nil, maxWidth: gridWidth) else { continue }
            let result = TileGridFinder.analyze(frame.pixels, width: frame.width, height: frame.height)
            if best == nil || result.tiles.count > (best?.result.tiles.count ?? 0) {
                best = GridPick(result: result, frame: frame, timeMs: t)
            }
        }

        // 2) zona da analizzare: solo il pannello, come la modalità "Tutti" di Android. Il video
        //    che continua a girare sopra il pannello farebbe sembrare tutto uno scorrimento.
        var zone = CropSpec.full
        if let pick = best, !pick.result.tiles.isEmpty {
            var l = Int.max, t = Int.max, r = 0, b = 0
            for tile in pick.result.tiles {
                l = min(l, tile[0]); t = min(t, tile[1])
                r = max(r, tile[0] + tile[2]); b = max(b, tile[1] + tile[3])
            }
            let margin = max(2, pick.frame.width / 50)
            let zl = max(0, l - margin), zt = max(0, t - margin)
            let zr = min(pick.frame.width, r + margin), zb = min(pick.frame.height, b + margin)
            zone = CropSpec.fromPixels(left: zl, top: zt, width: zr - zl, height: zb - zt, imageWidth: pick.frame.width, imageHeight: pick.frame.height)
        }

        // 3) movimento e durata del loop dentro la zona
        var grays: [[Int]] = []
        var times: [Int64] = []
        var nextSample = 0
        var aw = 0, ah = 0
        try reader.read(fromMs: 0, toMs: end) { ms, image in
            if ms + 5 < nextSample { return true }
            nextSample = ms + sampleIntervalMs
            let small = try reader.raster(image, crop: zone, maxWidth: analysisWidth)
            aw = small.width
            ah = small.height
            grays.append(small.gray)
            times.append(Int64(ms))
            progress.report(ms, max(1, end), "Fotogrammi letti: \(grays.count)")
            return true
        }
        let proposals = grays.count >= 8 ? StickerDetector.detect(frames: grays, width: aw, height: ah, timesMs: times) : []
        /// centro di una regione in movimento, in coordinate normalizzate dell'intero fotogramma
        func center(_ p: StickerDetector.Proposal) -> (x: Float, y: Float) {
            let cx = (Float(p.box[0]) + Float(p.box[2]) / 2) / Float(max(1, aw))
            let cy = (Float(p.box[1]) + Float(p.box[3]) / 2) / Float(max(1, ah))
            return (zone.left + cx * zone.width, zone.top + cy * zone.height)
        }

        if let pick = best, !pick.result.tiles.isEmpty {
            var candidates: [Candidate] = []
            for tile in pick.result.tiles {
                let crop = CropSpec.fromPixels(left: tile[0], top: tile[1], width: tile[2], height: tile[3], imageWidth: pick.frame.width, imageHeight: pick.frame.height)
                // un loop il cui centro cade nella tessera: sticker animato con quei tempi
                let hit = proposals.first { p in
                    let c = center(p)
                    return c.x >= crop.left && c.x <= crop.right && c.y >= crop.top && c.y <= crop.bottom
                }
                let preview = pick.frame.cropped(crop).uiImage ?? UIImage()
                if let hit = hit {
                    candidates.append(Candidate(crop: crop, startMs: Int(hit.startMs), endMs: Int(hit.endMs), animated: true, preview: preview))
                } else {
                    candidates.append(Candidate(crop: crop, startMs: pick.timeMs, endMs: pick.timeMs, animated: false, preview: preview))
                }
            }
            var message: String?
            if pick.result.cutTiles > 0 {
                message = "\(pick.result.cutTiles) sticker tagliati dal bordo dello schermo sono stati saltati: scorri il pannello e registra di nuovo per prenderli."
            }
            let animatedCount = candidates.filter { $0.animated }.count
            return AnalysisResult(
                title: "\(candidates.count) sticker (\(animatedCount) animati)", candidates: candidates,
                source: .video(url), backgroundHint: pick.result.background, message: message
            )
        }

        // nessuna griglia: le regioni animate di tutto lo schermo, allargate ai bordi dello sticker fermo
        guard let colorFrame = best?.frame else { throw WhatikError.noFrames }
        var candidates: [Candidate] = []
        for p in proposals {
            guard let crop = StickerRefiner.refine(box: p.box, aw: aw, ah: ah, rgb: colorFrame.pixels, fw: colorFrame.width, fh: colorFrame.height) else { continue }
            let preview = colorFrame.cropped(crop).uiImage ?? UIImage()
            candidates.append(Candidate(crop: crop, startMs: Int(p.startMs), endMs: Int(p.endMs), animated: true, preview: preview))
        }
        return AnalysisResult(
            title: "\(candidates.count) sticker animati", candidates: candidates, source: .video(url), backgroundHint: nil,
            message: candidates.isEmpty
                ? "Nessuno sticker trovato. Registra il pannello degli sticker tenendolo fermo per qualche secondo."
                : "Griglia del pannello non riconosciuta: trovati solo gli sticker che si muovono."
        )
    }

    static func analyzeScreenshot(data: Data, progress: ProgressReporter) throws -> AnalysisResult {
        progress.report(0, 2, "Leggo lo screenshot")
        let full = try ImageIOFrameSource(data, maxSide: 4096).firstFrame()
        let small = full.scaled(toWidth: gridWidth)
        progress.report(1, 2, "Cerco le tessere")
        let grid = TileGridFinder.analyze(small.pixels, width: small.width, height: small.height)
        let candidates = grid.tiles.map { tile -> Candidate in
            let crop = CropSpec.fromPixels(left: tile[0], top: tile[1], width: tile[2], height: tile[3], imageWidth: small.width, imageHeight: small.height)
            let preview = full.cropped(crop).uiImage ?? UIImage()
            return Candidate(crop: crop, startMs: 0, endMs: 0, animated: false, preview: preview)
        }
        var message: String?
        if candidates.isEmpty {
            message = "Nessuna griglia di sticker riconosciuta. Puoi importare lo screenshot in Libreria e ritagliarlo a mano."
        } else if grid.cutTiles > 0 {
            message = "\(grid.cutTiles) sticker tagliati dal bordo sono stati saltati."
        }
        progress.report(2, 2)
        return AnalysisResult(
            title: "\(candidates.count) sticker fermi", candidates: candidates, source: .image(full, data),
            backgroundHint: grid.background, message: message
        )
    }

    static func createStickers(_ result: AnalysisResult, _ chosen: [Candidate], library: StickerLibrary, progress: ProgressReporter) throws -> CreationReport {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH.mm.ss"
        let stamp = formatter.string(from: Date())
        var items: [StickerItem] = []
        var failures: [String] = []
        var reader: VideoReader?
        if case .video(let url) = result.source { reader = try VideoReader(url: url) }
        for (i, candidate) in chosen.enumerated() {
            progress.report(i, chosen.count, "Sticker \(i + 1) di \(chosen.count)")
            do {
                let base: FrameSource
                switch result.source {
                case .video(let url):
                    if candidate.animated {
                        base = CachedSource(try VideoFrameSource(url: url, crop: candidate.crop, startMs: candidate.startMs, endMs: candidate.endMs))
                    } else {
                        guard let frame = try reader?.frame(atMs: candidate.startMs, crop: candidate.crop, maxWidth: 1024) else { throw WhatikError.noFrames }
                        base = StillSource(frame)
                    }
                case .image(let raster, _):
                    base = StillSource(raster.cropped(candidate.crop))
                }
                let cleaned = StickerCleaningSource(base, backgroundHint: result.backgroundHint)
                let converted = try StickerConverter.convert(cleaned, forceStatic: !candidate.animated)
                let name = chosen.count > 1 ? "TikTok \(stamp) \(i + 1)" : "TikTok \(stamp)"
                switch library.importData(converted.bytes, name: name, source: "recording", probe: MediaDecoder.probe) {
                case .added(let item), .duplicate(let item):
                    items.append(item)
                case .failed(_, let reason):
                    failures.append(reason)
                }
            } catch {
                failures.append("\(i + 1): \(error.localizedDescription)")
            }
        }
        progress.report(chosen.count, chosen.count)
        return CreationReport(items: items, failures: failures)
    }
}
