import Foundation

/// Sorgente di fotogrammi già composti (ogni fotogramma è l'immagine completa). I fotogrammi
/// vengono prodotti in sequenza perché GIF e WebP animati dipendono dal precedente.
public protocol FrameSource {
    var width: Int { get }
    var height: Int { get }
    /// Durata di ogni fotogramma in millisecondi (il numero di elementi è il numero di fotogrammi).
    var durationsMs: [Int] { get }
    /// Chiama `consume` per ogni fotogramma; restituendo false si interrompe.
    func produce(_ consume: (Int, Raster) throws -> Bool) throws
}

public extension FrameSource {
    var frameCount: Int { durationsMs.count }

    /// Primo fotogramma.
    func firstFrame() throws -> Raster {
        var result: Raster?
        try produce { _, frame in result = frame; return false }
        guard let frame = result else { throw WhatikError.noFrames }
        return frame
    }
}

public enum WhatikError: Error, LocalizedError, Equatable {
    case noFrames
    case unsupportedImage
    case conversion(String)
    case storage(String)

    public var errorDescription: String? {
        switch self {
        case .noFrames: return "Nessun fotogramma"
        case .unsupportedImage: return "Non è un'immagine supportata"
        case .conversion(let message): return message
        case .storage(let message): return message
        }
    }
}

/// Un'immagine ferma.
public struct StillSource: FrameSource {
    public let raster: Raster
    public init(_ raster: Raster) { self.raster = raster }
    public var width: Int { raster.width }
    public var height: Int { raster.height }
    public var durationsMs: [Int] { [0] }
    public func produce(_ consume: (Int, Raster) throws -> Bool) throws { _ = try consume(0, raster) }
}

/// Fotogrammi già in memoria (test, piccole animazioni).
public struct ArraySource: FrameSource {
    public let frames: [Raster]
    public let durationsMs: [Int]
    public init(frames: [Raster], durationsMs: [Int]) {
        precondition(!frames.isEmpty && frames.count == durationsMs.count)
        self.frames = frames
        self.durationsMs = durationsMs
    }
    public var width: Int { frames[0].width }
    public var height: Int { frames[0].height }
    public func produce(_ consume: (Int, Raster) throws -> Bool) throws {
        for (i, frame) in frames.enumerated() where try !consume(i, frame) { return }
    }
}

/// Applica un ritaglio a ogni fotogramma.
public struct CroppedSource: FrameSource {
    public let inner: FrameSource
    public let crop: CropSpec
    public init(_ inner: FrameSource, crop: CropSpec) { self.inner = inner; self.crop = crop }
    public var width: Int { crop.toPixels(width: inner.width, height: inner.height).width }
    public var height: Int { crop.toPixels(width: inner.width, height: inner.height).height }
    public var durationsMs: [Int] { inner.durationsMs }
    public func produce(_ consume: (Int, Raster) throws -> Bool) throws {
        try inner.produce { i, frame in try consume(i, frame.cropped(crop)) }
    }
}

/// Tiene solo i fotogrammi il cui istante iniziale cade in [startMs, endMs].
public struct TrimmedSource: FrameSource {
    public let inner: FrameSource
    private let kept: [Int]

    public init(_ inner: FrameSource, startMs: Int, endMs: Int) {
        self.inner = inner
        let starts = TrimmedSource.startTimes(inner.durationsMs)
        let indices = starts.indices.filter { starts[$0] >= startMs && starts[$0] <= endMs }
        kept = indices.isEmpty ? [0] : indices
    }

    public var width: Int { inner.width }
    public var height: Int { inner.height }
    public var durationsMs: [Int] { kept.map { inner.durationsMs[$0] } }

    public func produce(_ consume: (Int, Raster) throws -> Bool) throws {
        let keptSet = Set(kept)
        let last = kept[kept.count - 1]
        var next = 0
        try inner.produce { index, frame in
            if keptSet.contains(index) {
                let keepGoing = try consume(next, frame)
                next += 1
                return keepGoing && index < last
            }
            return index < last
        }
    }

    /// Istanti iniziali cumulativi dei fotogrammi.
    public static func startTimes(_ durations: [Int]) -> [Int] {
        var t = 0
        return durations.map { d in defer { t += d }; return t }
    }
}

/// Toglie lo sfondo uniforme attorno allo sticker (vedi `BackgroundRemover`) e restringe il
/// riquadro al contenuto: per gli animati il riquadro è l'unione su tutti i fotogrammi.
/// Se non c'è uno sfondo da togliere i fotogrammi passano invariati.
public final class BackgroundRemovingSource: FrameSource {
    private let inner: FrameSource
    private let hint: ARGB?
    private let params: BackgroundRemover.Params
    private var prepared = false
    private var background: ARGB?
    private var trim: [Int]?

    public init(_ inner: FrameSource, backgroundHint: ARGB? = nil, params: BackgroundRemover.Params = .init()) {
        self.inner = inner
        self.hint = backgroundHint
        self.params = params
    }

    public var width: Int { prepareQuietly(); return trim?[2] ?? inner.width }
    public var height: Int { prepareQuietly(); return trim?[3] ?? inner.height }
    public var durationsMs: [Int] { inner.durationsMs }

    private func prepareQuietly() { try? prepare() }

    private func prepare() throws {
        if prepared { return }
        prepared = true
        var bg: ARGB?
        var unionBox: [Int]?
        var frameW = 0, frameH = 0
        try inner.produce { i, frame in
            var px = frame.pixels
            if i == 0 {
                frameW = frame.width; frameH = frame.height
                bg = BackgroundRemover.resolveBackground(px, width: frameW, height: frameH, hint: hint, params: params)
            }
            guard let b = bg, frame.width == frameW, frame.height == frameH else { return false }
            if let box = BackgroundRemover.removeConnected(&px, width: frameW, height: frameH, background: b, params: params).box {
                unionBox = unionBox.map { BackgroundRemover.union($0, box) } ?? box
            }
            return true
        }
        guard let b = bg, let u = unionBox, frameW > 0 else { return }
        background = b
        trim = BackgroundRemover.expand(u, width: frameW, height: frameH, params: params)
    }

    public func produce(_ consume: (Int, Raster) throws -> Bool) throws {
        try prepare()
        guard let bg = background, let t = trim else {
            try inner.produce(consume)
            return
        }
        try inner.produce { i, frame in
            var px = frame.pixels
            BackgroundRemover.removeConnected(&px, width: frame.width, height: frame.height, background: bg, params: params)
            let cleaned = Raster(width: frame.width, height: frame.height, pixels: px)
            return try consume(i, cleaned.cropped(left: t[0], top: t[1], width: t[2], height: t[3]))
        }
    }
}

/// Tiene in memoria i fotogrammi di una sorgente costosa (un video), letti una sola volta:
/// conversione e rimozione dello sfondo li ripercorrono più volte.
public final class CachedSource: FrameSource {
    private let inner: FrameSource
    private var frames: [Raster]?

    public init(_ inner: FrameSource) { self.inner = inner }

    public var width: Int { inner.width }
    public var height: Int { inner.height }
    public var durationsMs: [Int] { inner.durationsMs }

    public func produce(_ consume: (Int, Raster) throws -> Bool) throws {
        if frames == nil {
            var all: [Raster] = []
            try inner.produce { _, frame in all.append(frame); return true }
            guard all.count == inner.durationsMs.count else { throw WhatikError.conversion("Letti \(all.count) fotogrammi su \(inner.durationsMs.count)") }
            frames = all
        }
        for (i, frame) in (frames ?? []).enumerated() where try !consume(i, frame) { return }
    }
}
