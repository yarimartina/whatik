import Foundation

/// Rifinisce una regione trovata dal rilevatore di movimento usando un fotogramma a colori:
/// il riquadro viene allargato ai bordi dello sticker fermo sullo sfondo e si scartano i casi
/// evidentemente sbagliati (regioni che toccano il bordo, strisce, aree enormi).
public enum StickerRefiner {

    public struct Params: Sendable {
        public var borderFraction: Float = 0.01
        public var maxAspect: Float = 2.2
        public var maxAreaFraction: Float = 0.6
        public var maxStaticSideFraction: Float = 0.6
        public var margin: Float = 0.06
        public init() {}
    }

    /// - Parameters:
    ///   - box: regione del movimento (left, top, width, height) in pixel dell'analisi `aw`x`ah`
    ///   - rgb: fotogramma a colori `fw`x`fh`
    ///   - seed: punto (in pixel del fotogramma) da cui cercare lo sticker fermo; nil = centro della regione
    /// - Returns: riquadro quadrato normalizzato, oppure nil se la regione va scartata
    public static func refine(box: [Int], aw: Int, ah: Int, rgb: [ARGB], fw: Int, fh: Int, seed: (Int, Int)? = nil, params: Params = Params()) -> CropSpec? {
        let sx = Float(fw) / Float(aw)
        let sy = Float(fh) / Float(ah)
        let ml = roundInt(Float(box[0]) * sx)
        let mt = roundInt(Float(box[1]) * sy)
        let mr = min(fw, roundInt(Float(box[0] + box[2]) * sx))
        let mb = min(fh, roundInt(Float(box[1] + box[3]) * sy))
        if mr <= ml || mb <= mt { return nil }

        let border = max(1, roundInt(Float(fw) * params.borderFraction))
        if ml <= border || mt <= border || mr >= fw - border || mb >= fh - border { return nil }

        var l = ml, t = mt, r = mr, b = mb
        let cx = seed?.0 ?? (ml + mr) / 2
        let cy = seed?.1 ?? (mt + mb) / 2
        let still = StaticStickerFinder.find(rgb, width: fw, height: fh, px: cx, py: cy)
        if still.found {
            let sl = still.box[0], st = still.box[1], sw = still.box[2], sh = still.box[3]
            let sr = sl + sw
            let sb = st + sh
            let intersects = sl < mr && ml < sr && st < mb && mt < sb
            let plausible = Float(max(sw, sh)) <= Float(fw) * params.maxStaticSideFraction
            if intersects && plausible {
                l = min(l, sl); t = min(t, st); r = max(r, sr); b = max(b, sb)
            }
        }
        let w = r - l
        let h = b - t
        if w <= 0 || h <= 0 { return nil }
        if l <= border || t <= border || r >= fw - border || b >= fh - border { return nil }
        if Float(max(w, h)) / Float(min(w, h)) > params.maxAspect { return nil }
        if Int64(w) * Int64(h) > Int64(Float(fw) * Float(fh) * params.maxAreaFraction) { return nil }
        return StaticStickerFinder.toCrop([l, t, w, h], width: fw, height: fh, margin: params.margin)
    }
}
