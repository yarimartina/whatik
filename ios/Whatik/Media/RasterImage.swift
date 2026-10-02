import UIKit
import CoreGraphics
import WhatikCore

extension Raster {
    /// Da un'immagine CoreGraphics, disegnata in `width`x`height` (di default la sua dimensione).
    init(cgImage: CGImage, width w: Int? = nil, height h: Int? = nil) throws {
        let width = w ?? cgImage.width
        let height = h ?? cgImage.height
        guard width > 0, height > 0, let space = CGColorSpace(name: CGColorSpace.sRGB) else { throw WhatikError.unsupportedImage }
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let drawn: Bool = bytes.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
            ) else { return false }
            context.interpolationQuality = .high
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { throw WhatikError.unsupportedImage }
        // CoreGraphics lavora con alfa premoltiplicato: si torna ai valori pieni
        for p in 0..<(width * height) {
            let a = Int(bytes[p * 4 + 3])
            if a == 0 || a == 255 { continue }
            for c in 0..<3 {
                bytes[p * 4 + c] = UInt8(min(255, (Int(bytes[p * 4 + c]) * 255 + a / 2) / a))
            }
        }
        self.init(width: width, height: height, rgba: bytes)
    }

    func cgImage() -> CGImage? {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let provider = CGDataProvider(data: Data(rgbaBytes) as CFData) else { return nil }
        return CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4, space: space,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue | CGBitmapInfo.byteOrder32Big.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
        )
    }

    var uiImage: UIImage? { cgImage().map { UIImage(cgImage: $0) } }
}
