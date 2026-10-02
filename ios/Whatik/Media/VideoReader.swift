import AVFoundation
import CoreImage
import WhatikCore

/// Lettura sequenziale dei fotogrammi di un video (registrazione dello schermo) con AVAssetReader.
final class VideoReader {
    let url: URL
    let durationMs: Int
    let width: Int
    let height: Int
    private let asset: AVURLAsset
    private let track: AVAssetTrack
    private let context = CIContext(options: [.cacheIntermediates: false])

    init(url: URL) throws {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else { throw WhatikError.conversion("Il file non contiene video") }
        self.url = url
        self.asset = asset
        self.track = track
        durationMs = max(0, Int((CMTimeGetSeconds(asset.duration) * 1000).rounded()))
        let rect = CGRect(origin: .zero, size: track.naturalSize).applying(track.preferredTransform)
        width = Int(abs(rect.width).rounded())
        height = Int(abs(rect.height).rounded())
        guard width > 0, height > 0 else { throw WhatikError.conversion("Video senza dimensioni") }
    }

    /// Fotogrammi fra `fromMs` e `toMs`, dritti e con origine in (0, 0): (istante in ms, immagine).
    func read(fromMs: Int, toMs: Int, _ handle: (Int, CIImage) throws -> Bool) throws {
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { throw WhatikError.conversion("Video non leggibile") }
        reader.add(output)
        let start = CMTime(value: CMTimeValue(max(0, fromMs)), timescale: 1000)
        let end = CMTime(value: CMTimeValue(max(fromMs + 1, toMs)), timescale: 1000)
        reader.timeRange = CMTimeRange(start: start, end: end)
        guard reader.startReading() else { throw reader.error ?? WhatikError.conversion("Video non leggibile") }
        defer { if reader.status == .reading { reader.cancelReading() } }
        let transform = track.preferredTransform
        while let sample = output.copyNextSampleBuffer() {
            guard let buffer = CMSampleBufferGetImageBuffer(sample) else { continue }
            let ms = Int((CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)) * 1000).rounded())
            var image = CIImage(cvPixelBuffer: buffer)
            if !transform.isIdentity {
                image = image.transformed(by: transform)
                image = image.transformed(by: CGAffineTransform(translationX: -image.extent.origin.x, y: -image.extent.origin.y))
            }
            let keepGoing = try autoreleasepool { try handle(ms, image) }
            if !keepGoing { break }
        }
        if reader.status == .failed { throw reader.error ?? WhatikError.conversion("Lettura del video interrotta") }
    }

    /// Dimensione di uscita di un ritaglio limitato a `maxWidth` di larghezza.
    func outputSize(crop: CropSpec?, maxWidth: Int?) -> (width: Int, height: Int) {
        let p = (crop ?? .full).toPixels(width: width, height: height)
        guard let mw = maxWidth, p.width > mw else { return (p.width, p.height) }
        return (mw, max(1, Int((Double(p.height) * Double(mw) / Double(p.width)).rounded())))
    }

    /// Ritaglio (coordinate normalizzate, origine in alto a sinistra) ridotto a `maxWidth`.
    func raster(_ image: CIImage, crop: CropSpec?, maxWidth: Int?) throws -> Raster {
        let p = (crop ?? .full).toPixels(width: width, height: height)
        let out = outputSize(crop: crop, maxWidth: maxWidth)
        // CoreImage ha l'origine in basso a sinistra
        let rect = CGRect(x: p.left, y: height - p.top - p.height, width: p.width, height: p.height)
        var img = image.cropped(to: rect).transformed(by: CGAffineTransform(translationX: -rect.minX, y: -rect.minY))
        if out.width != p.width || out.height != p.height {
            let scale = Double(out.height) / Double(p.height)
            let aspect = (Double(out.width) / Double(p.width)) / scale
            img = img.clampedToExtent().applyingFilter("CILanczosScaleTransform", parameters: [
                kCIInputScaleKey: scale,
                kCIInputAspectRatioKey: aspect,
            ])
        }
        guard let cg = context.createCGImage(img, from: CGRect(x: 0, y: 0, width: out.width, height: out.height)) else {
            throw WhatikError.conversion("Fotogramma non convertibile")
        }
        return try Raster(cgImage: cg, width: out.width, height: out.height)
    }

    /// Il primo fotogramma a partire da `ms`.
    func frame(atMs ms: Int, crop: CropSpec?, maxWidth: Int?) throws -> Raster? {
        var result: Raster?
        try read(fromMs: max(0, ms), toMs: ms + 1000) { _, image in
            result = try raster(image, crop: crop, maxWidth: maxWidth)
            return false
        }
        return result
    }
}

/// Fotogrammi di un tratto di video a frequenza fissa, ritagliati: la sorgente di uno sticker animato.
final class VideoFrameSource: FrameSource {
    private let reader: VideoReader
    private let crop: CropSpec
    private let startMs: Int
    private let intervalMs: Int
    private let maxWidth: Int
    let width: Int
    let height: Int
    let durationsMs: [Int]

    init(url: URL, crop: CropSpec, startMs: Int, endMs: Int, fps: Int = 15, maxWidth: Int = 720) throws {
        let reader = try VideoReader(url: url)
        self.reader = reader
        self.crop = crop
        self.startMs = startMs
        self.maxWidth = maxWidth
        intervalMs = max(1, 1000 / max(1, fps))
        let size = reader.outputSize(crop: crop, maxWidth: maxWidth)
        width = size.width
        height = size.height
        let count = max(2, Int((Double(max(1, endMs - startMs)) / Double(intervalMs)).rounded(.up)))
        durationsMs = [Int](repeating: intervalMs, count: count)
    }

    func produce(_ consume: (Int, Raster) throws -> Bool) throws {
        var index = 0
        var previous: CIImage?
        var stopped = false
        let count = durationsMs.count
        try reader.read(fromMs: startMs, toMs: startMs + count * intervalMs + 200) { ms, image in
            // ogni istante richiesto prende l'ultimo fotogramma decodificato prima di lui
            while index < count && startMs + index * intervalMs < ms {
                let frame = previous ?? image
                if try !consume(index, try reader.raster(frame, crop: crop, maxWidth: maxWidth)) {
                    stopped = true
                    return false
                }
                index += 1
            }
            previous = image
            return index < count
        }
        if stopped { return }
        guard let last = previous else { throw WhatikError.noFrames }
        while index < count {
            if try !consume(index, try reader.raster(last, crop: crop, maxWidth: maxWidth)) { return }
            index += 1
        }
    }
}
