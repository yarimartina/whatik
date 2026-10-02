import Foundation

/// Toglie lo sfondo uniforme attorno a uno sticker catturato (il bianco della tessera).
/// Diventano trasparenti solo i pixel di colore sfondo raggiungibili dal bordo, con una
/// sfumatura sui pixel di contorno: il bianco dentro lo sticker resta. Se il contenuto è una
/// foto rettangolare (lati pieni) dentro la foto si puliscono solo gli angoli arrotondati.
public enum BackgroundRemover {

    public struct Params: Sendable {
        public var tolerance = 28
        public var haloTolerance = 80
        public var minEdgeFraction: Float = 0.3
        public var cornerAreaCap: Float = 0.05
        public var margin: Float = 0.03
        public var whiteMin = 232
        public var solidSide: Float = 0.6
        public var photoSides = 3
        public init() {}
    }

    /// Riquadro (l,t,w,h) del contenuto rimasto (nil se è sparito tutto) e pixel resi trasparenti.
    public struct Result: Sendable {
        public var box: [Int]?
        public var removed: Int
    }

    /// Colore di sfondo da usare: `hint` rifinito sui pixel del bordo, altrimenti il colore
    /// dominante del bordo; se il bordo non è uniforme ma gli angoli sono bianchi, bianco.
    public static func resolveBackground(_ argb: [ARGB], width: Int, height: Int, hint: ARGB?, params: Params = Params()) -> ARGB? {
        if let hint = hint {
            var r = 0, g = 0, b = 0, n = 0
            forEachEdge(width: width, height: height) { p in
                let c = argb[p]
                if colorDistance(c, hint) <= params.tolerance { r += red(c); g += green(c); b += blue(c); n += 1 }
            }
            return n == 0 ? (hint & 0xFFFFFF) : rgb(r / n, g / n, b / n)
        }
        if let bg = edgeBackground(argb, width: width, height: height, params: params) { return bg }
        let corners = [0, width - 1, (height - 1) * width, width * height - 1]
        let white = corners.filter { p in
            let c = argb[p]
            return red(c) >= params.whiteMin && green(c) >= params.whiteMin && blue(c) >= params.whiteMin
        }.count
        return white >= 3 ? 0xFFFFFF : nil
    }

    /// Colore dominante del bordo (media del gruppo più numeroso), nil se non copre `minEdgeFraction` del bordo.
    public static func edgeBackground(_ argb: [ARGB], width: Int, height: Int, params: Params = Params()) -> ARGB? {
        var bins: [Int: [Int]] = [:]
        var total = 0
        forEachEdge(width: width, height: height) { p in
            let c = argb[p]
            let bin = Int(((c >> 20) & 0xF) << 8) | Int(((c >> 12) & 0xF) << 4) | Int((c >> 4) & 0xF)
            var acc = bins[bin] ?? [0, 0, 0, 0]
            acc[0] += 1; acc[1] += red(c); acc[2] += green(c); acc[3] += blue(c)
            bins[bin] = acc
            total += 1
        }
        guard let best = bins.values.max(by: { $0[0] < $1[0] }) else { return nil }
        if Float(best[0]) / Float(total) < params.minEdgeFraction { return nil }
        return rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0])
    }

    /// Rende trasparenti (in `argb`) i pixel vicini a `background` raggiungibili dal bordo, con sfumatura.
    @discardableResult
    public static func removeConnected(_ argb: inout [ARGB], width: Int, height: Int, background: ARGB, params: Params = Params()) -> Result {
        let n = width * height
        var removed = [Bool](repeating: false, count: n)
        var stack = [Int](repeating: 0, count: n)
        var top = 0
        var edgeMatches = 0
        var edgeTotal = 0
        forEachEdge(width: width, height: height) { p in
            edgeTotal += 1
            if colorDistance(argb[p], background) <= params.tolerance { edgeMatches += 1 }
        }
        let fromEdges = edgeTotal > 0 && Float(edgeMatches) / Float(edgeTotal) >= params.minEdgeFraction
        if fromEdges {
            forEachEdge(width: width, height: height) { p in
                if !removed[p] && colorDistance(argb[p], background) <= params.tolerance { removed[p] = true; stack[top] = p; top += 1 }
            }
        } else {
            for p in [0, width - 1, (height - 1) * width, n - 1] where p >= 0 && p < n {
                if !removed[p] && colorDistance(argb[p], background) <= params.tolerance { removed[p] = true; stack[top] = p; top += 1 }
            }
        }
        var count = 0
        while top > 0 {
            top -= 1
            let p = stack[top]
            count += 1
            let x = p % width
            let y = p / width
            if x > 0 { let q = p - 1; if !removed[q] && colorDistance(argb[q], background) <= params.tolerance { removed[q] = true; stack[top] = q; top += 1 } }
            if x < width - 1 { let q = p + 1; if !removed[q] && colorDistance(argb[q], background) <= params.tolerance { removed[q] = true; stack[top] = q; top += 1 } }
            if y > 0 { let q = p - width; if !removed[q] && colorDistance(argb[q], background) <= params.tolerance { removed[q] = true; stack[top] = q; top += 1 } }
            if y < height - 1 { let q = p + width; if !removed[q] && colorDistance(argb[q], background) <= params.tolerance { removed[q] = true; stack[top] = q; top += 1 } }
        }
        if count == 0 { return Result(box: [0, 0, width, height], removed: 0) }
        if !fromEdges && Float(count) > Float(n) * params.cornerAreaCap { return Result(box: [0, 0, width, height], removed: 0) }
        count = protectPhoto(argb, width: width, height: height, background: background, removed: &removed, params: params, count: count)

        // contorno sfumato
        let span = max(1, params.haloTolerance - params.tolerance)
        var l = width, t = height, r = -1, b = -1
        for p in 0..<n {
            if removed[p] { argb[p] = argb[p] & 0xFFFFFF; continue }
            let x = p % width
            let y = p / width
            let touches = (x > 0 && removed[p - 1]) || (x < width - 1 && removed[p + 1]) || (y > 0 && removed[p - width]) || (y < height - 1 && removed[p + width])
            if touches {
                let diff = colorDistance(argb[p], background)
                if diff < params.haloTolerance {
                    let a = (max(0, diff - params.tolerance) * 255 / span).clamped(0, 255)
                    argb[p] = (ARGB(a) << 24) | (argb[p] & 0xFFFFFF)
                    if a == 0 { continue }
                }
            }
            if x < l { l = x }
            if x > r { r = x }
            if y < t { t = y }
            if y > b { b = y }
        }
        let box: [Int]? = r < 0 ? nil : [l, t, r - l + 1, b - t + 1]
        return Result(box: box, removed: count)
    }

    /// Se il contenuto è una foto rettangolare, dentro quel riquadro lo sfondo tolto viene
    /// ripristinato tranne gli angoli arrotondati. Restituisce il nuovo numero di pixel tolti.
    private static func protectPhoto(_ argb: [ARGB], width: Int, height: Int, background: ARGB, removed: inout [Bool], params: Params, count: Int) -> Int {
        var cl = width, ct = height, cr = -1, cb = -1
        for p in argb.indices {
            if colorDistance(argb[p], background) <= params.tolerance { continue }
            let x = p % width
            let y = p / width
            if x < cl { cl = x }
            if x > cr { cr = x }
            if y < ct { ct = y }
            if y > cb { cb = y }
        }
        if cr < 0 || cr - cl < 4 || cb - ct < 4 { return count }
        func sideFraction(xs: ClosedRange<Int>, ys: ClosedRange<Int>) -> Float {
            var total = 0, content = 0
            for y in ys { for x in xs { total += 1; if colorDistance(argb[y * width + x], background) > params.tolerance { content += 1 } } }
            return total == 0 ? 0 : Float(content) / Float(total)
        }
        var solid = 0
        if sideFraction(xs: cl...cr, ys: ct...ct) >= params.solidSide { solid += 1 }
        if sideFraction(xs: cl...cr, ys: cb...cb) >= params.solidSide { solid += 1 }
        if sideFraction(xs: cl...cl, ys: ct...cb) >= params.solidSide { solid += 1 }
        if sideFraction(xs: cr...cr, ys: ct...cb) >= params.solidSide { solid += 1 }
        if solid < params.photoSides { return count }
        var keep = [Bool](repeating: false, count: argb.count)
        var stack = [Int](repeating: 0, count: argb.count)
        var top = 0
        var kept = 0
        for p in [ct * width + cl, ct * width + cr, cb * width + cl, cb * width + cr] where removed[p] && !keep[p] {
            keep[p] = true; stack[top] = p; top += 1
        }
        while top > 0 {
            top -= 1
            let p = stack[top]
            kept += 1
            let x = p % width
            let y = p / width
            if x > cl { let q = p - 1; if removed[q] && !keep[q] { keep[q] = true; stack[top] = q; top += 1 } }
            if x < cr { let q = p + 1; if removed[q] && !keep[q] { keep[q] = true; stack[top] = q; top += 1 } }
            if y > ct { let q = p - width; if removed[q] && !keep[q] { keep[q] = true; stack[top] = q; top += 1 } }
            if y < cb { let q = p + width; if removed[q] && !keep[q] { keep[q] = true; stack[top] = q; top += 1 } }
        }
        let area = (cr - cl + 1) * (cb - ct + 1)
        let keepCorners = Float(kept) <= Float(area) * params.cornerAreaCap
        var restored = 0
        for y in ct...cb {
            for x in cl...cr {
                let p = y * width + x
                if removed[p] && !(keepCorners && keep[p]) { removed[p] = false; restored += 1 }
            }
        }
        return count - restored
    }

    /// Riquadro `box` allargato del margine e limitato al fotogramma.
    public static func expand(_ box: [Int], width: Int, height: Int, params: Params = Params()) -> [Int] {
        let m = Int(Float(max(width, height)) * params.margin)
        let l = max(0, box[0] - m)
        let t = max(0, box[1] - m)
        let r = min(width, box[0] + box[2] + m)
        let b = min(height, box[1] + box[3] + m)
        return [l, t, r - l, b - t]
    }

    public static func union(_ a: [Int], _ b: [Int]) -> [Int] {
        let l = min(a[0], b[0])
        let t = min(a[1], b[1])
        let r = max(a[0] + a[2], b[0] + b[2])
        let btm = max(a[1] + a[3], b[1] + b[3])
        return [l, t, r - l, btm - t]
    }

    private static func forEachEdge(width: Int, height: Int, _ visit: (Int) -> Void) {
        if width <= 0 || height <= 0 { return }
        for x in 0..<width { visit(x); if height > 1 { visit((height - 1) * width + x) } }
        if height > 2 { for y in 1..<(height - 1) { visit(y * width); if width > 1 { visit(y * width + width - 1) } } }
    }
}
