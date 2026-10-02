import Foundation

/// Trova i bordi di uno sticker fermo attorno a un punto: lo sticker è un'isola di colori su
/// uno sfondo uniforme. Si stima il colore di sfondo dal bordo di una finestra centrata sul
/// punto, si marcano i pixel diversi dallo sfondo e si prende la componente connessa che
/// contiene il punto.
public enum StaticStickerFinder {

    public struct Params: Sendable {
        public var windowHalfFraction: Float = 0.35
        public var ringFraction: Float = 0.15
        public var backgroundTolerance = 40
        public var snapRadiusFraction: Float = 0.05
        public var minAreaFraction: Float = 0.01
        public var maxBoxFraction: Float = 0.9
        public var fallbackFraction: Float = 0.3
        public init() {}
    }

    public struct Result: Sendable {
        /// Rettangolo (left, top, width, height) in pixel dell'immagine.
        public var box: [Int]
        /// false se è stato usato il quadrato di riserva centrato sul punto.
        public var found: Bool
    }

    public static func find(_ rgb: [ARGB], width: Int, height: Int, px: Int, py: Int, params: Params = Params()) -> Result {
        let x0 = px.clamped(0, width - 1)
        let y0 = py.clamped(0, height - 1)
        let half = max(8, roundInt(Float(width) * params.windowHalfFraction))
        let wl = max(0, x0 - half)
        let wt = max(0, y0 - half)
        let wr = min(width - 1, x0 + half)
        let wb = min(height - 1, y0 + half)
        let ww = wr - wl + 1
        let wh = wb - wt + 1
        if ww < 8 || wh < 8 { return fallback(width: width, height: height, x: x0, y: y0, params: params) }

        // 1) colore di sfondo: moda dei colori quantizzati sull'anello esterno della finestra
        let ring = max(2, roundInt(Float(min(ww, wh)) * params.ringFraction))
        var counts: [Int: Int] = [:]
        for y in wt...wb {
            for x in wl...wr {
                let onRing = x < wl + ring || x > wr - ring || y < wt + ring || y > wb - ring
                if !onRing { continue }
                counts[PixelMath.quantize(rgb[y * width + x]), default: 0] += 1
            }
        }
        guard let bgQ = counts.max(by: { $0.value < $1.value })?.key else { return fallback(width: width, height: height, x: x0, y: y0, params: params) }
        let bg = PixelMath.dequantize(bgQ)

        // 2) maschera dei pixel "non sfondo" nella finestra
        var mask = [Bool](repeating: false, count: ww * wh)
        for y in 0..<wh {
            for x in 0..<ww {
                mask[y * ww + x] = !PixelMath.isBackground(rgb[(wt + y) * width + (wl + x)], bg, tolerance: params.backgroundTolerance)
            }
        }
        let dilated = PixelMath.dilate(mask, width: ww, height: wh, radius: 2)

        // 3) punto di partenza: il tocco, oppure il pixel di sticker più vicino
        var sx = x0 - wl
        var sy = y0 - wt
        if !dilated[sy * ww + sx] {
            let radius = max(2, roundInt(Float(width) * params.snapRadiusFraction))
            var best = -1
            var bestD = Int.max
            for y in max(0, sy - radius)...min(wh - 1, sy + radius) {
                for x in max(0, sx - radius)...min(ww - 1, sx + radius) {
                    if !dilated[y * ww + x] { continue }
                    let d = abs(x - sx) + abs(y - sy)
                    if d < bestD { bestD = d; best = y * ww + x }
                }
            }
            if best < 0 { return fallback(width: width, height: height, x: x0, y: y0, params: params) }
            sx = best % ww
            sy = best / ww
        }

        // 4) componente connessa che contiene il punto
        guard let box = PixelMath.componentBox(dilated, width: ww, height: wh, sx: sx, sy: sy) else {
            return fallback(width: width, height: height, x: x0, y: y0, params: params)
        }
        let l = box[0], t = box[1], r = box[2], b = box[3]
        let area = (r - l) * (b - t)
        let windowArea = ww * wh
        if Float(area) < Float(windowArea) * params.minAreaFraction || Float(area) > Float(windowArea) * params.maxBoxFraction {
            return fallback(width: width, height: height, x: x0, y: y0, params: params)
        }
        // la dilatazione ha allargato di 2 px: li togliamo
        let left = max(0, wl + l + 2)
        let top = max(0, wt + t + 2)
        let right = min(width, wl + r - 2)
        let bottom = min(height, wt + b - 2)
        if right - left < 4 || bottom - top < 4 { return fallback(width: width, height: height, x: x0, y: y0, params: params) }
        return Result(box: [left, top, right - left, bottom - top], found: true)
    }

    /// Riquadro quadrato normalizzato attorno a un rettangolo, con margine.
    public static func toCrop(_ box: [Int], width: Int, height: Int, margin: Float = 0.08) -> CropSpec {
        let l = box[0], t = box[1], w = box[2], h = box[3]
        let side = Float(max(w, h)) * (1 + margin)
        return CropSpec.square(
            cx: (Float(l) + Float(w) / 2) / Float(width), cy: (Float(t) + Float(h) / 2) / Float(height),
            sizeFraction: side / Float(min(width, height)), width: width, height: height
        )
    }

    private static func fallback(width: Int, height: Int, x: Int, y: Int, params: Params) -> Result {
        let side = roundInt(Float(width) * params.fallbackFraction).clamped(8, min(width, height))
        let left = (x - side / 2).clamped(0, width - side)
        let top = (y - side / 2).clamped(0, height - side)
        return Result(box: [left, top, side, side], found: false)
    }
}
