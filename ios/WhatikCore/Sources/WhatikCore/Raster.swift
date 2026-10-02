import Foundation

/// Immagine in memoria: un pixel ARGB non premoltiplicato (0xAARRGGBB) per elemento, riga per
/// riga. È lo stesso formato degli `IntArray` dell'app Android, così gli algoritmi restano
/// identici. Le conversioni da/verso CoreGraphics e libwebp passano per `rgbaBytes`.
public struct Raster: Equatable, Sendable {
    public let width: Int
    public let height: Int
    public var pixels: [ARGB]

    public init(width: Int, height: Int, pixels: [ARGB]) {
        precondition(width > 0 && height > 0 && pixels.count == width * height, "Raster \(width)x\(height) con \(pixels.count) pixel")
        self.width = width
        self.height = height
        self.pixels = pixels
    }

    /// Immagine tutta di un colore (trasparente per default).
    public init(width: Int, height: Int, fill: ARGB = 0) {
        self.init(width: width, height: height, pixels: [ARGB](repeating: fill, count: width * height))
    }

    /// Da byte RGBA non premoltiplicati (4 byte per pixel).
    public init(width: Int, height: Int, rgba: UnsafeBufferPointer<UInt8>) {
        precondition(rgba.count >= width * height * 4)
        var px = [ARGB](repeating: 0, count: width * height)
        for p in 0..<(width * height) {
            let i = p * 4
            px[p] = (ARGB(rgba[i + 3]) << 24) | (ARGB(rgba[i]) << 16) | (ARGB(rgba[i + 1]) << 8) | ARGB(rgba[i + 2])
        }
        self.init(width: width, height: height, pixels: px)
    }

    public init(width: Int, height: Int, rgba: [UInt8]) {
        self = rgba.withUnsafeBufferPointer { Raster(width: width, height: height, rgba: $0) }
    }

    /// Byte RGBA non premoltiplicati (per libwebp e per il PNG).
    public var rgbaBytes: [UInt8] {
        var out = [UInt8](repeating: 0, count: width * height * 4)
        for p in 0..<pixels.count {
            let c = pixels[p]
            let i = p * 4
            out[i] = UInt8((c >> 16) & 0xFF)
            out[i + 1] = UInt8((c >> 8) & 0xFF)
            out[i + 2] = UInt8(c & 0xFF)
            out[i + 3] = UInt8((c >> 24) & 0xFF)
        }
        return out
    }

    public subscript(x: Int, y: Int) -> ARGB {
        get { pixels[y * width + x] }
        set { pixels[y * width + x] = newValue }
    }

    /// Ritaglio con un `CropSpec` normalizzato.
    public func cropped(_ crop: CropSpec) -> Raster {
        let r = crop.toPixels(width: width, height: height)
        return cropped(left: r.left, top: r.top, width: r.width, height: r.height)
    }

    /// Ritaglio in pixel (limitato all'immagine).
    public func cropped(left: Int, top: Int, width w: Int, height h: Int) -> Raster {
        let l = left.clamped(0, width - 1)
        let t = top.clamped(0, height - 1)
        let cw = w.clamped(1, width - l)
        let ch = h.clamped(1, height - t)
        if l == 0 && t == 0 && cw == width && ch == height { return self }
        var out = [ARGB](repeating: 0, count: cw * ch)
        for y in 0..<ch {
            let src = (t + y) * width + l
            for x in 0..<cw { out[y * cw + x] = pixels[src + x] }
        }
        return Raster(width: cw, height: ch, pixels: out)
    }

    /// Ridimensionata a `w`x`h`: media d'area quando si riduce, bilineare quando si ingrandisce.
    /// Si lavora con alfa premoltiplicato, così i bordi trasparenti non si scuriscono.
    public func resized(width w: Int, height h: Int) -> Raster {
        if w == width && h == height { return self }
        // premoltiplicato in Float, 4 canali
        var src = [Float](repeating: 0, count: width * height * 4)
        for p in 0..<pixels.count {
            let c = pixels[p]
            let a = Float(alpha(c)) / 255
            src[p * 4] = Float(red(c)) * a
            src[p * 4 + 1] = Float(green(c)) * a
            src[p * 4 + 2] = Float(blue(c)) * a
            src[p * 4 + 3] = a
        }
        let horizontal = Raster.resample(src, length: width, lines: height, stride: 1, lineStride: width, to: w)
        // ora horizontal è w x height, righe contigue
        let vertical = Raster.resample(horizontal, length: height, lines: w, stride: w, lineStride: 1, to: h)
        // vertical è memorizzato a righe w x h
        var out = [ARGB](repeating: 0, count: w * h)
        for p in 0..<(w * h) {
            let a = vertical[p * 4 + 3]
            if a <= 0.0001 { out[p] = 0; continue }
            let r = Int((vertical[p * 4] / a).rounded()).clamped(0, 255)
            let g = Int((vertical[p * 4 + 1] / a).rounded()).clamped(0, 255)
            let b = Int((vertical[p * 4 + 2] / a).rounded()).clamped(0, 255)
            out[p] = argb(Int((a * 255).rounded()).clamped(0, 255), r, g, b)
        }
        return Raster(width: w, height: h, pixels: out)
    }

    /// Ricampiona lungo un asse. `src` ha 4 Float per pixel; ogni "linea" ha `length` campioni
    /// distanziati di `stride` pixel e le linee partono a `lineStride` pixel l'una dall'altra.
    /// Il risultato è memorizzato a righe con lo stesso orientamento (larghezza `to` se si
    /// ricampiona in orizzontale, altezza `to` se in verticale).
    private static func resample(_ src: [Float], length: Int, lines: Int, stride: Int, lineStride: Int, to target: Int) -> [Float] {
        var out = [Float](repeating: 0, count: target * lines * 4)
        let scale = Float(length) / Float(target)
        // l'output conserva l'orientamento: in orizzontale (stride 1) le righe hanno `target` pixel,
        // in verticale (lineStride 1) le colonne sono le linee e l'output ha `lines` colonne
        let outStride = stride == 1 ? 1 : lines
        let outLineStride = stride == 1 ? target : 1
        for line in 0..<lines {
            let base = line * lineStride
            let outBase = line * outLineStride
            for o in 0..<target {
                var acc0: Float = 0, acc1: Float = 0, acc2: Float = 0, acc3: Float = 0
                if scale >= 1 {
                    // riduzione: media pesata dei campioni coperti
                    let start = Float(o) * scale
                    let end = start + scale
                    var i = Int(start)
                    var total: Float = 0
                    while Float(i) < end && i < length {
                        let w = min(Float(i + 1), end) - max(Float(i), start)
                        if w > 0 {
                            let s = (base + i * stride) * 4
                            acc0 += src[s] * w; acc1 += src[s + 1] * w; acc2 += src[s + 2] * w; acc3 += src[s + 3] * w
                            total += w
                        }
                        i += 1
                    }
                    if total > 0 { acc0 /= total; acc1 /= total; acc2 /= total; acc3 /= total }
                } else {
                    // ingrandimento: bilineare
                    let pos = (Float(o) + 0.5) * scale - 0.5
                    let i0 = Int(pos.rounded(.down)).clamped(0, length - 1)
                    let i1 = min(i0 + 1, length - 1)
                    let f = (pos - Float(i0)).clamped(0, 1)
                    let s0 = (base + i0 * stride) * 4
                    let s1 = (base + i1 * stride) * 4
                    acc0 = src[s0] * (1 - f) + src[s1] * f
                    acc1 = src[s0 + 1] * (1 - f) + src[s1 + 1] * f
                    acc2 = src[s0 + 2] * (1 - f) + src[s1 + 2] * f
                    acc3 = src[s0 + 3] * (1 - f) + src[s1 + 3] * f
                }
                let d = (outBase + o * outStride) * 4
                out[d] = acc0; out[d + 1] = acc1; out[d + 2] = acc2; out[d + 3] = acc3
            }
        }
        return out
    }

    /// Ridimensiona mantenendo le proporzioni e centra su un canvas quadrato trasparente.
    public func fitted(toSquare size: Int) -> Raster {
        let scale = min(Float(size) / Float(width), Float(size) / Float(height))
        let w = max(1, min(size, roundInt(Float(width) * scale)))
        let h = max(1, min(size, roundInt(Float(height) * scale)))
        let scaled = resized(width: w, height: h)
        if w == size && h == size { return scaled }
        var out = Raster(width: size, height: size)
        let left = (size - w) / 2
        let top = (size - h) / 2
        for y in 0..<h {
            for x in 0..<w { out.pixels[(top + y) * size + left + x] = scaled.pixels[y * w + x] }
        }
        return out
    }

    /// Versione ridotta a `targetWidth` di larghezza (proporzionale), per l'analisi.
    public func scaled(toWidth targetWidth: Int) -> Raster {
        let h = max(8, roundInt(Float(height) * Float(targetWidth) / Float(width)))
        return resized(width: targetWidth, height: h)
    }

    /// Grigio 0..255 (pesi 299/587/114).
    public var gray: [Int] { PixelMath.toGray(pixels) }
}
