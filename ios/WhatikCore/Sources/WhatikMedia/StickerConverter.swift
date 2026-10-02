import Foundation
import WhatikCore

/// Converte qualunque sorgente di fotogrammi in uno sticker conforme a WhatsApp:
/// 512x512 WebP, statico <= 100 KB, animato <= 500 KB, <= 10 s, fotogrammi >= 8 ms;
/// icona del pack 96x96 PNG <= 50 KB.
public enum StickerConverter {
    public static let stickerSize = 512
    public static let traySize = 96
    public static let maxStaticBytes = 100 * 1024
    public static let maxAnimatedBytes = 500 * 1024
    public static let maxTrayBytes = 50 * 1024
    /// Durata di ciascuno dei due fotogrammi di uno sticker fermo reso animato.
    static let stillFrameMs = 500
    static let initialMaxFrames = 100
    static let maxAttempts = 9

    public struct Result: Sendable {
        public var bytes: Data
        public var animated: Bool
        public var frameCount: Int
        public var quality: Int
    }

    public static func convert(_ source: FrameSource, forceStatic: Bool) throws -> Result {
        if forceStatic || source.frameCount < 2 { return try convertStatic(source) }
        return try convertAnimated(source)
    }

    /// In un pack animato anche gli sticker fermi devono essere WebP animati (WhatsApp non mescola
    /// i tipi): diventano due fotogrammi identici, come fa Sticker Maker.
    public static func convertForPack(_ source: FrameSource, packAnimated: Bool) throws -> Result {
        let result = try convert(source, forceStatic: !packAnimated)
        return packAnimated && !result.animated ? try animateStill(result.bytes, quality: result.quality) : result
    }

    /// Trasforma uno sticker WebP statico in un'animazione di due fotogrammi identici.
    public static func animateStill(_ staticWebP: Data, quality: Int = 100) throws -> Result {
        let still: Data
        if let f = WebPCodec.features(staticWebP), f.width == stickerSize, f.height == stickerSize, !f.animated {
            still = staticWebP
        } else {
            still = try WebPCodec.encode(try WebPCodec.decodeFirstFrame(staticWebP).fitted(toSquare: stickerSize), quality: 90, lossless: false)
        }
        let animated = try WebPCodec.muxStills([(still, stillFrameMs), (still, stillFrameMs)], width: stickerSize, height: stickerSize)
        if animated.count > maxAnimatedBytes { throw WhatikError.conversion("Sticker troppo pesante per un pack animato") }
        return Result(bytes: animated, animated: true, frameCount: 2, quality: quality)
    }

    /// Adatta uno sticker WebP già convertito al tipo richiesto.
    public static func convertType(_ webp: Data, animated: Bool) throws -> Data {
        let isAnimated = WebPCodec.features(webp)?.animated ?? false
        if animated && !isAnimated { return try animateStill(webp).bytes }
        if !animated && isAnimated { return try convert(StillSource(try WebPCodec.decodeFirstFrame(webp)), forceStatic: true).bytes }
        return webp
    }

    /// Icona 96x96 PNG dal primo fotogramma di uno sticker WebP già convertito.
    public static func makeTrayIcon(_ stickerWebP: Data) throws -> Data {
        let frame = try WebPCodec.decodeFirstFrame(stickerWebP)
        let png = PNGEncoder.encode(frame.fitted(toSquare: traySize))
        if png.count > maxTrayBytes { throw WhatikError.conversion("Icona del pack troppo grande") }
        return png
    }

    // MARK: statico

    private static func convertStatic(_ source: FrameSource) throws -> Result {
        let fitted = try source.firstFrame().fitted(toSquare: stickerSize)
        let lossless = try WebPCodec.encode(fitted, quality: 100, lossless: true)
        if lossless.count <= maxStaticBytes { return Result(bytes: lossless, animated: false, frameCount: 1, quality: 100) }
        for quality in [90, 80, 70, 60, 50, 40, 30, 20] {
            let lossy = try WebPCodec.encode(fitted, quality: quality, lossless: false)
            if lossy.count <= maxStaticBytes { return Result(bytes: lossy, animated: false, frameCount: 1, quality: quality) }
        }
        throw WhatikError.conversion("Impossibile ridurre lo sticker sotto i 100 KB")
    }

    // MARK: animato

    private static func convertAnimated(_ source: FrameSource) throws -> Result {
        let durations = AnimationTiming.trimmedDurations(source.durationsMs)
        let trimmedCount = durations.count
        var maxFrames = min(trimmedCount, initialMaxFrames)
        var quality = 75
        var lastSize = -1
        for _ in 0..<maxAttempts {
            let kept = AnimationTiming.selectFrames(count: trimmedCount, maxFrames: maxFrames)
            let keptDurations = AnimationTiming.mergeDurations(durations, kept: kept)
            let keptIndex = Dictionary(uniqueKeysWithValues: kept.enumerated().map { ($1, $0) })
            let lastKept = kept[kept.count - 1]
            var produced = 0
            let encoded = try WebPCodec.encodeAnimation(width: stickerSize, height: stickerSize, quality: quality) { add in
                try source.produce { index, frame in
                    if let position = keptIndex[index] {
                        try add(frame.fitted(toSquare: stickerSize), keptDurations[position])
                        produced += 1
                    }
                    return index < lastKept
                }
            }
            if produced != kept.count { throw WhatikError.conversion("Decodificati \(produced) fotogrammi su \(kept.count)") }
            // fotogrammi tutti uguali: l'encoder li fonde in uno solo, che WhatsApp non accetta come animato
            if (WebPCodec.features(encoded)?.frameCount ?? 0) < 2 {
                let still = try convertStatic(source)
                return try animateStill(still.bytes, quality: still.quality)
            }
            if encoded.count <= maxAnimatedBytes { return Result(bytes: encoded, animated: true, frameCount: kept.count, quality: quality) }
            lastSize = encoded.count
            let ratio = Double(maxAnimatedBytes) * 0.92 / Double(encoded.count)
            if ratio >= 0.6 && quality > 35 {
                quality = max(30, quality - 20)
            } else {
                maxFrames = Int(Double(kept.count) * ratio * 0.9).clamped(4, max(4, kept.count - 1))
                if quality > 50 { quality = 50 }
            }
        }
        throw WhatikError.conversion("Animazione troppo pesante anche dopo la riduzione (\(lastSize / 1024) KB)")
    }
}
