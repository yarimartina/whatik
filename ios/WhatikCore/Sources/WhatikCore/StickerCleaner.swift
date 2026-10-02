import Foundation

/// Pulizia degli sticker presi dal pannello di TikTok (stessa logica dell'app Android).
///
/// 1. Bande: le righe e le colonne ai bordi fatte tutte del colore del pannello si tolgono
///    ritagliando, senza trasparenze.
/// 2. Ritaglio a sagoma: lo sfondo dentro lo sticker diventa trasparente solo se ciò che resta
///    è un unico soggetto e il colore tolto è proprio quello del pannello. Se resta tanti pezzi
///    separati (testo, caselle, un meme) lo sfondo fa parte dello sticker e resta com'è.
/// 3. Angoli: gli angoli arrotondati della tessera diventano trasparenti, con un limite di area.
public enum StickerCleaner {

    public struct Params: Sendable {
        /// Differenza massima dal pannello per le bande (stretta: il bianco di un meme è vicinissimo).
        public var bandTolerance = 6
        /// Quota minima di una riga/colonna vicina al pannello perché sia una banda.
        public var bandFraction: Float = 0.96
        /// Differenza massima della media di una riga/colonna dal pannello: il rumore si annulla, lo sfondo di un meme no.
        public var bandMeanTolerance = 3
        /// Tolleranza per il ritaglio a sagoma di un soggetto unico.
        public var cutoutTolerance = 14
        /// Quota minima del contorno del contenuto vicina al pannello per tentare il ritaglio a sagoma.
        public var cutoutEdgeFraction: Float = 0.6
        /// Differenza massima fra la media dello sfondo tolto e il pannello: oltre, è lo sfondo proprio dello sticker.
        public var cutoutMeanTolerance = 4
        /// Quota minima del contenuto rimasto nel pezzo più grande perché sia un soggetto unico.
        public var subjectShare: Float = 0.97
        /// Sotto questa quota del riquadro il soggetto è troppo piccolo: niente ritaglio a sagoma.
        public var minSubjectFraction: Float = 0.05
        /// Tolleranza e area massima per gli angoli arrotondati.
        public var cornerTolerance = 28
        public var cornerAreaCap: Float = 0.05
        /// Oltre la tolleranza e fino a qui i pixel di contorno diventano semitrasparenti.
        public var haloTolerance = 60
        /// Margine trasparente attorno a un soggetto ritagliato a sagoma (frazione del lato maggiore).
        public var cutoutMargin: Float = 0.03
        public init() {}
    }

    /// Decisione presa sul primo fotogramma e applicata uguale a tutti.
    public struct Plan: Sendable, Equatable {
        /// Contenuto dopo il taglio delle bande (left, top, width, height).
        public var content: [Int]
        /// true se lo sfondo dentro il contenuto si toglie (soggetto unico).
        public var cutout: Bool
    }

    public static func plan(_ argb: [ARGB], width: Int, height: Int, background: ARGB, params: Params = Params()) -> Plan? {
        guard let rect = trimBands(argb, width: width, height: height, background: background, params: params) else { return nil }
        var cutout = false
        if perimeterFraction(argb, width: width, rect: rect, background: background, tolerance: params.cutoutTolerance) >= params.cutoutEdgeFraction {
            let removed = flood(argb, width: width, rect: rect, background: background, tolerance: params.cutoutTolerance, cornersOnly: false)
            cutout = meanDistance(argb, removed: removed, width: width, rect: rect, background: background) <= params.cutoutMeanTolerance &&
                singleSubject(removed, width: width, rect: rect, params: params)
        }
        return Plan(content: rect, cutout: cutout)
    }

    /// Applica il piano a un fotogramma (trasparenze in `argb`) e restituisce il riquadro da tenere.
    @discardableResult
    public static func apply(_ argb: inout [ARGB], width: Int, height: Int, background: ARGB, plan: Plan, params: Params = Params()) -> [Int] {
        let rect = plan.content
        let l = rect[0], t = rect[1], w = rect[2], h = rect[3]
        let tolerance = plan.cutout ? params.cutoutTolerance : params.cornerTolerance
        var removed = flood(argb, width: width, rect: rect, background: background, tolerance: tolerance, cornersOnly: !plan.cutout)
        // angoli: se il colore "sfondo" dilaga nello sticker non sono angoli arrotondati, si lascia stare
        if !plan.cutout && Float(removed.lazy.filter { $0 }.count) > Float(w * h) * params.cornerAreaCap {
            removed = [Bool](repeating: false, count: removed.count)
        }
        var bl = l + w, bt = t + h, br = -1, bb = -1
        let span = max(params.haloTolerance - tolerance, 1)
        for y in t..<(t + h) {
            for x in l..<(l + w) {
                let p = y * width + x
                if removed[p] { argb[p] &= 0xFFFFFF; continue }
                let touches = (x > l && removed[p - 1]) || (x < l + w - 1 && removed[p + 1]) ||
                    (y > t && removed[p - width]) || (y < t + h - 1 && removed[p + width])
                if touches {
                    let diff = colorDistance(argb[p], background)
                    if diff < params.haloTolerance {
                        let a = (max(diff - tolerance, 0) * 255 / span).clamped(0, 255)
                        argb[p] = (ARGB(a) << 24) | (argb[p] & 0xFFFFFF)
                        if a == 0 { continue }
                    }
                }
                bl = min(bl, x); br = max(br, x); bt = min(bt, y); bb = max(bb, y)
            }
        }
        if !plan.cutout || br < 0 { return rect }
        // soggetto ritagliato a sagoma: riquadro stretto con un po' di margine trasparente; il margine
        // può uscire dal contenuto (sulle bande tolte), quella parte diventa trasparente
        let m = roundInt(Float(max(w, h)) * params.cutoutMargin)
        let nl = max(bl - m, 0), nt = max(bt - m, 0)
        let nr = min(br + 1 + m, width), nb = min(bb + 1 + m, height)
        for y in nt..<nb {
            for x in nl..<nr where !(l..<(l + w)).contains(x) || !(t..<(t + h)).contains(y) {
                argb[y * width + x] &= 0xFFFFFF
            }
        }
        return [nl, nt, nr - nl, nb - nt]
    }

    /// Contenuto senza le bande del colore del pannello sui quattro lati; nil se è tutto pannello.
    static func trimBands(_ argb: [ARGB], width: Int, height: Int, background: ARGB, params: Params) -> [Int]? {
        var l = 0, t = 0, r = width, b = height
        func band(_ pixels: [ARGB]) -> Bool {
            if pixels.isEmpty { return false }
            var near = 0, sr = 0, sg = 0, sb = 0
            for c in pixels {
                sr += red(c); sg += green(c); sb += blue(c)
                if colorDistance(c, background) <= params.bandTolerance { near += 1 }
            }
            let n = pixels.count
            if Float(near) < Float(n) * params.bandFraction { return false }
            return colorDistance(rgb(sr / n, sg / n, sb / n), background) <= params.bandMeanTolerance
        }
        func row(_ y: Int) -> [ARGB] { (l..<r).map { argb[y * width + $0] } }
        func col(_ x: Int) -> [ARGB] { (t..<b).map { argb[$0 * width + x] } }
        var changed = true
        while changed {
            changed = false
            while t < b && band(row(t)) { t += 1; changed = true }
            while b > t && band(row(b - 1)) { b -= 1; changed = true }
            while l < r && band(col(l)) { l += 1; changed = true }
            while r > l && band(col(r - 1)) { r -= 1; changed = true }
        }
        if r - l < 2 || b - t < 2 { return nil }
        // la riga di passaggio fra banda e contenuto è una sfumatura: si toglie anche quella
        if t > 0 && b - t > 4 { t += 1 }
        if b < height && b - t > 4 { b -= 1 }
        if l > 0 && r - l > 4 { l += 1 }
        if r < width && r - l > 4 { r -= 1 }
        return [l, t, r - l, b - t]
    }

    private static func meanDistance(_ argb: [ARGB], removed: [Bool], width: Int, rect: [Int], background: ARGB) -> Int {
        var sr = 0, sg = 0, sb = 0, n = 0
        for y in rect[1]..<(rect[1] + rect[3]) {
            for x in rect[0]..<(rect[0] + rect[2]) where removed[y * width + x] {
                let c = argb[y * width + x]
                sr += red(c); sg += green(c); sb += blue(c); n += 1
            }
        }
        if n == 0 { return 0 }
        return colorDistance(rgb(sr / n, sg / n, sb / n), background)
    }

    private static func perimeterFraction(_ argb: [ARGB], width: Int, rect: [Int], background: ARGB, tolerance: Int) -> Float {
        let l = rect[0], t = rect[1], w = rect[2], h = rect[3]
        var near = 0, total = 0
        func visit(_ x: Int, _ y: Int) { total += 1; if colorDistance(argb[y * width + x], background) <= tolerance { near += 1 } }
        for x in l..<(l + w) { visit(x, t); if h > 1 { visit(x, t + h - 1) } }
        if h > 2 { for y in (t + 1)..<(t + h - 1) { visit(l, y); if w > 1 { visit(l + w - 1, y) } } }
        return total == 0 ? 0 : Float(near) / Float(total)
    }

    /// Pixel vicini al pannello raggiungibili dal contorno del riquadro (o solo dai suoi angoli).
    private static func flood(_ argb: [ARGB], width: Int, rect: [Int], background: ARGB, tolerance: Int, cornersOnly: Bool) -> [Bool] {
        let l = rect[0], t = rect[1], w = rect[2], h = rect[3]
        var removed = [Bool](repeating: false, count: argb.count)
        var stack: [Int] = []
        stack.reserveCapacity(w * h)
        func seed(_ p: Int) {
            if !removed[p] && colorDistance(argb[p], background) <= tolerance { removed[p] = true; stack.append(p) }
        }
        if cornersOnly {
            seed(t * width + l); seed(t * width + l + w - 1); seed((t + h - 1) * width + l); seed((t + h - 1) * width + l + w - 1)
        } else {
            for x in l..<(l + w) { seed(t * width + x); seed((t + h - 1) * width + x) }
            for y in t..<(t + h) { seed(y * width + l); seed(y * width + l + w - 1) }
        }
        while let p = stack.popLast() {
            let x = p % width, y = p / width
            if x > l { seed(p - 1) }
            if x < l + w - 1 { seed(p + 1) }
            if y > t { seed(p - width) }
            if y < t + h - 1 { seed(p + width) }
        }
        return removed
    }

    /// true se ciò che resta dopo aver tolto lo sfondo è un pezzo solo (non testo o tanti elementi).
    private static func singleSubject(_ removed: [Bool], width: Int, rect: [Int], params: Params) -> Bool {
        let l = rect[0], t = rect[1], w = rect[2], h = rect[3]
        var remaining = [Bool](repeating: false, count: removed.count)
        var total = 0
        for y in t..<(t + h) { for x in l..<(l + w) where !removed[y * width + x] { remaining[y * width + x] = true; total += 1 } }
        if Float(total) < Float(w * h) * params.minSubjectFraction { return false }
        var largest = 0
        var stack: [Int] = []
        for y in t..<(t + h) {
            for x in l..<(l + w) {
                let start = y * width + x
                if !remaining[start] { continue }
                remaining[start] = false
                stack.append(start)
                var size = 0
                while let p = stack.popLast() {
                    size += 1
                    let px = p % width, py = p / width
                    if px > l && remaining[p - 1] { remaining[p - 1] = false; stack.append(p - 1) }
                    if px < l + w - 1 && remaining[p + 1] { remaining[p + 1] = false; stack.append(p + 1) }
                    if py > t && remaining[p - width] { remaining[p - width] = false; stack.append(p - width) }
                    if py < t + h - 1 && remaining[p + width] { remaining[p + width] = false; stack.append(p + width) }
                }
                largest = max(largest, size)
            }
        }
        return Float(largest) >= Float(total) * params.subjectShare
    }
}
