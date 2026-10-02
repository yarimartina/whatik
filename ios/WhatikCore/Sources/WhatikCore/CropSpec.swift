import Foundation

/// Riquadro di ritaglio rettangolare in coordinate normalizzate (frazioni di larghezza e
/// altezza dell'immagine), così lo stesso ritaglio vale per anteprime e fotogrammi a
/// risoluzioni diverse. Il risultato viene poi centrato su un canvas quadrato trasparente.
public struct CropSpec: Equatable, Hashable, Codable, Sendable {
    public var left: Float
    public var top: Float
    public var right: Float
    public var bottom: Float

    public init(left: Float, top: Float, right: Float, bottom: Float) {
        self.left = left; self.top = top; self.right = right; self.bottom = bottom
    }

    public var width: Float { right - left }
    public var height: Float { bottom - top }
    public var cx: Float { (left + right) / 2 }
    public var cy: Float { (top + bottom) / 2 }

    /// Lato minimo come frazione dell'immagine.
    public static let minSize: Float = 0.03
    public static let full = CropSpec(left: 0, top: 0, right: 1, bottom: 1)

    /// Riporta il riquadro dentro l'immagine, con lati di almeno `minSize`, mantenendo le dimensioni dove possibile.
    public func normalized() -> CropSpec {
        let l = min(left, right)
        let r = max(left, right)
        let t = min(top, bottom)
        let b = max(top, bottom)
        let w = (r - l).clamped(CropSpec.minSize, 1)
        let h = (b - t).clamped(CropSpec.minSize, 1)
        let nl = ((l + r) / 2 - w / 2).clamped(0, 1 - w)
        let nt = ((t + b) / 2 - h / 2).clamped(0, 1 - h)
        return CropSpec(left: nl, top: nt, right: nl + w, bottom: nt + h)
    }

    /// Rettangolo in pixel (left, top, width, height) per un'immagine `width`x`height`, sempre dentro i bordi.
    public func toPixels(width: Int, height: Int) -> (left: Int, top: Int, width: Int, height: Int) {
        let n = normalized()
        let w = roundInt(n.width * Float(width)).clamped(1, width)
        let h = roundInt(n.height * Float(height)).clamped(1, height)
        let l = roundInt(n.left * Float(width)).clamped(0, width - w)
        let t = roundInt(n.top * Float(height)).clamped(0, height - h)
        return (l, t, w, h)
    }

    /// Lo stesso riquadro come lo applicherà `toPixels` (arrotondato ai pixel e dentro i bordi).
    public func effective(width: Int, height: Int) -> CropSpec {
        let p = toPixels(width: width, height: height)
        return CropSpec(
            left: Float(p.left) / Float(width), top: Float(p.top) / Float(height),
            right: Float(p.left + p.width) / Float(width), bottom: Float(p.top + p.height) / Float(height)
        )
    }

    /// Quadrato con lato pari a `sizeFraction` del lato minore, centrato in (`cx`, `cy`) normalizzati.
    public static func square(cx: Float, cy: Float, sizeFraction: Float, width: Int, height: Int) -> CropSpec {
        let side = sizeFraction.clamped(minSize, 1) * Float(min(width, height))
        let w = side / Float(width)
        let h = side / Float(height)
        return CropSpec(left: cx - w / 2, top: cy - h / 2, right: cx + w / 2, bottom: cy + h / 2).normalized()
    }

    public static func fromPixels(left l: Int, top t: Int, width w: Int, height h: Int, imageWidth: Int, imageHeight: Int) -> CropSpec {
        CropSpec(
            left: Float(l) / Float(imageWidth), top: Float(t) / Float(imageHeight),
            right: Float(l + w) / Float(imageWidth), bottom: Float(t + h) / Float(imageHeight)
        ).normalized()
    }
}
