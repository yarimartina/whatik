import UIKit
import ImageIO
import WhatikCore
import WhatikMedia

/// Decodifica dei file importati: WebP con libwebp, tutto il resto (GIF, PNG, JPEG, HEIC) con ImageIO.
enum MediaDecoder {
    static func open(_ data: Data) throws -> FrameSource {
        if ImageKind.sniff(data) == .webp { return try WebPFrameSource(data) }
        return try ImageIOFrameSource(data)
    }

    static func probe(_ data: Data) throws -> StickerLibrary.Probe {
        if ImageKind.sniff(data) == .webp {
            guard let f = WebPCodec.features(data) else { throw WhatikError.unsupportedImage }
            return StickerLibrary.Probe(width: f.width, height: f.height, animated: f.animated)
        }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0,
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else { throw WhatikError.unsupportedImage }
        let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        guard w > 0, h > 0 else { throw WhatikError.unsupportedImage }
        return StickerLibrary.Probe(width: w, height: h, animated: CGImageSourceGetCount(source) > 1)
    }
}

/// Fotogrammi composti di un'immagine letta con ImageIO (anche GIF e PNG animati).
final class ImageIOFrameSource: FrameSource {
    private let source: CGImageSource
    private var still: Raster?
    let width: Int
    let height: Int
    let durationsMs: [Int]

    init(_ data: Data, maxSide: Int = 2048) throws {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { throw WhatikError.unsupportedImage }
        let count = CGImageSourceGetCount(source)
        guard count > 0 else { throw WhatikError.unsupportedImage }
        self.source = source
        if count == 1 {
            // immagine ferma: orientamento EXIF applicato e lato massimo limitato
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxSide,
            ]
            guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { throw WhatikError.unsupportedImage }
            let raster = try Raster(cgImage: image)
            still = raster
            width = raster.width
            height = raster.height
            durationsMs = [0]
        } else {
            guard let first = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw WhatikError.unsupportedImage }
            width = first.width
            height = first.height
            durationsMs = (0..<count).map { ImageIOFrameSource.delayMs(source, $0) }
        }
    }

    func produce(_ consume: (Int, Raster) throws -> Bool) throws {
        if let still = still {
            _ = try consume(0, still)
            return
        }
        for i in 0..<durationsMs.count {
            guard let image = CGImageSourceCreateImageAtIndex(source, i, nil) else { throw WhatikError.unsupportedImage }
            let raster = try autoreleasepool { try Raster(cgImage: image, width: width, height: height) }
            if try !consume(i, raster) { return }
        }
    }

    private static func delayMs(_ source: CGImageSource, _ index: Int) -> Int {
        guard let props = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] else { return 100 }
        let pairs: [(CFString, CFString, CFString)] = [
            (kCGImagePropertyGIFDictionary, kCGImagePropertyGIFUnclampedDelayTime, kCGImagePropertyGIFDelayTime),
            (kCGImagePropertyPNGDictionary, kCGImagePropertyAPNGUnclampedDelayTime, kCGImagePropertyAPNGDelayTime),
            (kCGImagePropertyHEICSDictionary, kCGImagePropertyHEICSUnclampedDelayTime, kCGImagePropertyHEICSDelayTime),
        ]
        for (dictKey, unclampedKey, clampedKey) in pairs {
            guard let dict = props[dictKey] as? [CFString: Any] else { continue }
            let seconds = (dict[unclampedKey] as? NSNumber)?.doubleValue ?? (dict[clampedKey] as? NSNumber)?.doubleValue ?? 0.1
            return Int((seconds * 1000).rounded())
        }
        return 100
    }
}

/// Miniature per le griglie (ImageIO legge anche i WebP da iOS 14), con cache.
final class ThumbnailLoader {
    static let shared = ThumbnailLoader()
    private let cache = NSCache<NSString, UIImage>()

    func image(for url: URL, maxPixel: Int) async -> UIImage? {
        let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)?.timeIntervalSince1970 ?? 0
        let key = "\(url.path)#\(modified)#\(maxPixel)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        let loaded = try? await Background.run { ThumbnailLoader.load(url, maxPixel) }
        guard let image = loaded ?? nil else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }

    static func load(_ url: URL, _ maxPixel: Int) -> UIImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        if let source = CGImageSourceCreateWithURL(url as CFURL, nil),
           let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) {
            return UIImage(cgImage: image)
        }
        if let data = try? Data(contentsOf: url), ImageKind.sniff(data) == .webp,
           let raster = try? WebPCodec.decodeFirstFrame(data) {
            return raster.uiImage
        }
        return nil
    }
}
