import Foundation

/// Trova automaticamente gli sticker animati in una registrazione dello schermo.
///
/// In TikTok lo sfondo (interfaccia, pannello degli sticker) è fermo mentre gli sticker si
/// muovono in continuazione. Su una sequenza di fotogrammi a bassa risoluzione in scala di
/// grigi si cerca la finestra temporale senza scorrimenti, si misura per ogni pixel quanto
/// spesso cambia, si raggruppano i pixel "vivi" in regioni e per ciascuna si stima il periodo
/// del loop per similarità fra fotogrammi. Trasposizione diretta della versione Android.
public enum StickerDetector {

    public struct Proposal: Sendable {
        /// Riquadro quadrato normalizzato, pronto per l'editor e il converter.
        public var crop: CropSpec
        public var startMs: Int64
        public var endMs: Int64
        /// Rettangolo rilevato (left, top, width, height) nei pixel del fotogramma analizzato.
        public var box: [Int]
        /// Quanto è "viva" la regione (0..1): serve per ordinare e filtrare.
        public var score: Float
    }

    public struct Params: Sendable {
        public var pixelThreshold = 20
        public var globalChangeMax: Float = 0.35
        public var minChangeFrequency: Float = 0.2
        public var minAreaFraction: Float = 0.003
        public var maxBoxFraction: Float = 0.7
        public var margin: Float = 0.12
        public var defaultClipMs: Int64 = 4000
        public var maxClipMs: Int64 = 10_000
        public var minClipMs: Int64 = 300
        public var minStableDiffs = 3
        public var ignoreTopFraction: Float = 0
        public var ignoreBottomFraction: Float = 0
        public var splitAspect: Float = 1.4
        public var gapFraction: Float = 0.12
        public var maxRingActivity: Float = 0.3
        public var compareLagMs: Int64 = 250
        public var minPeriodMs: Int64 = 400
        public var minLoopSimilarity: Float = 0.9
        public var darkFrameGray = 40
        public init() {}
    }

    /// - Parameters:
    ///   - frames: fotogrammi in scala di grigi (0..255), tutti `width`x`height`
    ///   - timesMs: istante di ogni fotogramma
    public static func detect(frames: [[Int]], width: Int, height: Int, timesMs: [Int64], params: Params = Params()) -> [Proposal] {
        precondition(frames.count == timesMs.count)
        let pixels = width * height

        // 0) per il movimento si confrontano fotogrammi a ~compareLagMs di distanza
        var sample: [Int] = []
        for i in frames.indices {
            if sample.isEmpty || timesMs[i] - timesMs[sample[sample.count - 1]] >= params.compareLagMs { sample.append(i) }
        }
        let n = sample.count
        if n < params.minStableDiffs + 1 { return [] }

        // 1) maschere di cambiamento fra fotogrammi campionati e frazione globale
        var changed = [[Bool]](repeating: [], count: n - 1)
        var globalChange = [Float](repeating: 0, count: n - 1)
        for i in 1..<n {
            let a = frames[sample[i - 1]]
            let b = frames[sample[i]]
            var count = 0
            var mask = [Bool](repeating: false, count: pixels)
            for p in 0..<pixels where abs(a[p] - b[p]) > params.pixelThreshold {
                mask[p] = true; count += 1
            }
            changed[i - 1] = mask
            globalChange[i - 1] = Float(count) / Float(pixels)
        }

        // 2) finestra stabile più lunga (niente scorrimenti), riportata agli indici dei fotogrammi reali
        guard let window = longestStableWindow(globalChange, params: params) else { return [] }
        let (firstDiff, lastDiff) = window
        let numDiffs = lastDiff - firstDiff + 1
        let firstFrame = sample[firstDiff]
        let lastFrame = sample[lastDiff + 1]

        // 3) frequenza di cambiamento per pixel dentro la finestra
        var freq = [Float](repeating: 0, count: pixels)
        for d in firstDiff...lastDiff {
            let mask = changed[d]
            for p in 0..<pixels where mask[p] { freq[p] += 1 }
        }
        var alive = [Bool](repeating: false, count: pixels)
        var aliveCount = 0
        let topLimit = roundInt(Float(height) * params.ignoreTopFraction)
        let bottomLimit = height - roundInt(Float(height) * params.ignoreBottomFraction)
        for p in 0..<pixels {
            freq[p] /= Float(numDiffs)
            let row = p / width
            if row < topLimit || row >= bottomLimit { continue }
            if freq[p] >= params.minChangeFrequency { alive[p] = true; aliveCount += 1 }
        }
        if aliveCount == 0 { return [] }

        // 4) dilatazione leggera e componenti connesse
        let dilated = PixelMath.dilate(alive, width: width, height: height, radius: 1)
        let boxes = connectedBoxes(dilated, width: width, height: height, minArea: max(4, roundInt(Float(pixels) * params.minAreaFraction)))
        let merged = mergeBoxes(boxes, gap: 2).flatMap { splitByGaps(alive, width: width, height: height, box: $0, params: params) }

        // 5) proposte
        var proposals: [Proposal] = []
        for box in merged {
            let l = box[0], t = box[1], r = box[2], b = box[3]
            let w = r - l
            let h = b - t
            if w < 3 || h < 3 { continue }
            if Float(w) * Float(h) / Float(pixels) > params.maxBoxFraction { continue }
            if ringActivity(alive, width: width, height: height, l: l, t: t, r: r, b: b) > params.maxRingActivity { continue }
            let activity = regionActivity(freq, width: width, l: l, t: t, r: r, b: b)
            let periodFrames = estimatePeriod(frames: frames, timesMs: timesMs, width: width, l: l, t: t, r: r, b: b, firstFrame: firstFrame, lastFrame: lastFrame, params: params)
            var startFrame = firstFrame
            if periodFrames > 0 {
                // il loop riparte dopo l'eventuale fotogramma nero: si comincia da lì
                let upper = min(firstFrame + periodFrames, lastFrame)
                var lastDark: Int? = nil
                if upper > firstFrame {
                    for i in firstFrame..<upper where darkFraction(frames[i], width: width, l: l, t: t, r: r, b: b, threshold: params.darkFrameGray) >= 0.8 {
                        lastDark = i
                    }
                }
                if let d = lastDark, d + 1 + periodFrames <= lastFrame + 1 { startFrame = d + 1 }
            }
            let startMs = timesMs[startFrame]
            let windowEndMs = timesMs[lastFrame]
            var endMs: Int64
            if periodFrames > 0 && startFrame + periodFrames <= lastFrame {
                endMs = timesMs[startFrame + periodFrames]
            } else {
                endMs = min(windowEndMs, startMs + params.defaultClipMs)
            }
            endMs = endMs.clamped(startMs + params.minClipMs, startMs + params.maxClipMs)
            if endMs > windowEndMs { endMs = max(windowEndMs, startMs + params.minClipMs) }
            let side = Float(max(w, h)) * (1 + params.margin)
            let cx = Float(l + r) / 2
            let cy = Float(t + b) / 2
            let crop = CropSpec.square(cx: cx / Float(width), cy: cy / Float(height), sizeFraction: side / Float(min(width, height)), width: width, height: height)
            proposals.append(Proposal(crop: crop, startMs: startMs, endMs: endMs, box: [l, t, w, h], score: activity))
        }
        // ordine di lettura: dall'alto in basso, da sinistra a destra (a parità di riga)
        let rowSize = max(1, height / 6)
        return proposals.sorted { a, b in
            let ra = a.box[1] / rowSize, rb = b.box[1] / rowSize
            return ra != rb ? ra < rb : a.box[0] < b.box[0]
        }
    }

    /// Coppia (primo, ultimo) indice di confronto della finestra stabile più lunga.
    static func longestStableWindow(_ globalChange: [Float], params: Params) -> (Int, Int)? {
        var bestStart = -1
        var bestLen = 0
        var start = -1
        for i in globalChange.indices {
            if globalChange[i] <= params.globalChangeMax {
                if start < 0 { start = i }
                let len = i - start + 1
                if len > bestLen { bestLen = len; bestStart = start }
            } else {
                start = -1
            }
        }
        return bestLen >= params.minStableDiffs ? (bestStart, bestStart + bestLen - 1) : nil
    }

    /// Rettangoli (left, top, right, bottom esclusivi) delle componenti connesse abbastanza grandi.
    static func connectedBoxes(_ mask: [Bool], width: Int, height: Int, minArea: Int) -> [[Int]] {
        var visited = [Bool](repeating: false, count: mask.count)
        var stack = [Int](repeating: 0, count: mask.count)
        var boxes: [[Int]] = []
        for seed in mask.indices {
            if !mask[seed] || visited[seed] { continue }
            var top = 0
            stack[top] = seed; top += 1
            visited[seed] = true
            var area = 0
            var l = width, t = height, r = -1, b = -1
            while top > 0 {
                top -= 1
                let p = stack[top]
                area += 1
                let x = p % width
                let y = p / width
                if x < l { l = x }
                if x > r { r = x }
                if y < t { t = y }
                if y > b { b = y }
                if x > 0 && mask[p - 1] && !visited[p - 1] { visited[p - 1] = true; stack[top] = p - 1; top += 1 }
                if x < width - 1 && mask[p + 1] && !visited[p + 1] { visited[p + 1] = true; stack[top] = p + 1; top += 1 }
                if y > 0 && mask[p - width] && !visited[p - width] { visited[p - width] = true; stack[top] = p - width; top += 1 }
                if y < height - 1 && mask[p + width] && !visited[p + width] { visited[p + width] = true; stack[top] = p + width; top += 1 }
            }
            if area >= minArea { boxes.append([l, t, r + 1, b + 1]) }
        }
        return boxes
    }

    public static func mergeBoxes(_ boxes: [[Int]], gap: Int) -> [[Int]] {
        var list = boxes
        var merged = true
        while merged {
            merged = false
            outer: for i in list.indices {
                for j in (i + 1)..<max(i + 1, list.count) {
                    let a = list[i], b = list[j]
                    let overlapX = a[0] - gap < b[2] && b[0] - gap < a[2]
                    let overlapY = a[1] - gap < b[3] && b[1] - gap < a[3]
                    if overlapX && overlapY {
                        list[i] = [min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])]
                        list.remove(at: j)
                        merged = true
                        break outer
                    }
                }
            }
        }
        return list
    }

    /// Separa una regione allungata (fila o colonna di sticker attaccati) nei punti in cui il
    /// profilo di attività per colonna/riga scende quasi a zero.
    static func splitByGaps(_ alive: [Bool], width: Int, height: Int, box: [Int], params: Params) -> [[Int]] {
        let l = box[0], t = box[1], r = box[2], b = box[3]
        let w = r - l
        let h = b - t
        if w <= 0 || h <= 0 { return [box] }
        let horizontal = Float(w) / Float(h) >= params.splitAspect
        let vertical = Float(h) / Float(w) >= params.splitAspect
        if !horizontal && !vertical { return [box] }
        let length = horizontal ? w : h
        var profile = [Int](repeating: 0, count: length)
        for y in t..<b { for x in l..<r where alive[y * width + x] { profile[horizontal ? x - l : y - t] += 1 } }
        let maxValue = profile.max() ?? 0
        if maxValue == 0 { return [box] }
        let threshold = Float(maxValue) * params.gapFraction
        var pieces: [[Int]] = []
        var start = -1
        for i in 0...length {
            let active = i < length && Float(profile[i]) > threshold
            if active && start < 0 { start = i }
            if !active && start >= 0 {
                pieces.append(horizontal ? [l + start, t, l + i, b] : [l, t + start, r, t + i])
                start = -1
            }
        }
        if pieces.count <= 1 { return [box] }
        // rifinisce ogni pezzo sull'altro asse
        return pieces.map { piece in
            let pl = piece[0], pt = piece[1], pr = piece[2], pb = piece[3]
            var nl = pr, nt = pb, nr = pl, nb = pt
            for y in pt..<pb {
                for x in pl..<pr where alive[y * width + x] {
                    if x < nl { nl = x }
                    if x + 1 > nr { nr = x + 1 }
                    if y < nt { nt = y }
                    if y + 1 > nb { nb = y + 1 }
                }
            }
            return (nr > nl && nb > nt) ? [nl, nt, nr, nb] : piece
        }
    }

    /// Frazione di pixel "vivi" nell'anello attorno alla regione (spessore 10% del lato maggiore, min 2 px).
    public static func ringActivity(_ alive: [Bool], width: Int, height: Int, l: Int, t: Int, r: Int, b: Int) -> Float {
        let ring = max(2, roundInt(Float(max(r - l, b - t)) * 0.1))
        let ol = max(0, l - ring)
        let ot = max(0, t - ring)
        let orr = min(width, r + ring)
        let ob = min(height, b + ring)
        var count = 0
        var total = 0
        for y in ot..<ob {
            for x in ol..<orr {
                if x >= l && x < r && y >= t && y < b { continue }
                total += 1
                if alive[y * width + x] { count += 1 }
            }
        }
        return total == 0 ? 0 : Float(count) / Float(total)
    }

    private static func regionActivity(_ freq: [Float], width: Int, l: Int, t: Int, r: Int, b: Int) -> Float {
        var sum: Float = 0
        var count = 0
        for y in t..<b { for x in l..<r { sum += freq[y * width + x]; count += 1 } }
        return count == 0 ? 0 : sum / Float(count)
    }

    /// Frazione di pixel della regione più scuri di `threshold`.
    private static func darkFraction(_ frame: [Int], width: Int, l: Int, t: Int, r: Int, b: Int, threshold: Int) -> Float {
        var dark = 0
        var count = 0
        for y in t..<b { for x in l..<r { if frame[y * width + x] < threshold { dark += 1 }; count += 1 } }
        return count == 0 ? 0 : Float(dark) / Float(count)
    }

    /// Periodo del loop (in fotogrammi) tramite similarità fra fotogrammi a distanza di un lag.
    /// Restituisce 0 se la regione è quasi ferma o nessun lag convince.
    static func estimatePeriod(frames: [[Int]], timesMs: [Int64], width: Int, l: Int, t: Int, r: Int, b: Int, firstFrame: Int, lastFrame: Int, params: Params) -> Int {
        let count = lastFrame - firstFrame + 1
        if count < 6 { return 0 }
        let cells = 6
        let cellsTotal = cells * cells
        let w = r - l
        let h = b - t
        if w < cells || h < cells { return 0 }
        var signature = [[Float]](repeating: [Float](repeating: 0, count: cellsTotal), count: count)
        for i in 0..<count {
            let f = frames[firstFrame + i]
            var sig = [Float](repeating: 0, count: cellsTotal)
            for cy in 0..<cells {
                for cx in 0..<cells {
                    let x0 = l + w * cx / cells
                    let x1 = l + w * (cx + 1) / cells
                    let y0 = t + h * cy / cells
                    let y1 = t + h * (cy + 1) / cells
                    var sum = 0
                    var n = 0
                    for y in y0..<y1 { for x in x0..<x1 { sum += f[y * width + x]; n += 1 } }
                    sig[cy * cells + cx] = n == 0 ? 0 : Float(sum) / Float(n)
                }
            }
            let mean = sig.reduce(0, +) / Float(cellsTotal)
            for k in sig.indices { sig[k] -= mean }
            signature[i] = sig
        }
        // regione quasi ferma: nessun loop da misurare
        var variability = 0.0
        for k in 0..<cellsTotal {
            var m = 0.0
            for i in 0..<count { m += Double(signature[i][k]) }
            m /= Double(count)
            var v = 0.0
            for i in 0..<count { let d = Double(signature[i][k]) - m; v += d * d }
            variability += (v / Double(count)).squareRoot()
        }
        if variability / Double(cellsTotal) < 2.0 { return 0 }

        let norms: [Float] = (0..<count).map { i in
            Float(signature[i].reduce(0.0) { $0 + Double($1) * Double($1) }.squareRoot()) + 1e-3
        }
        guard let minLag = (1..<count).first(where: { timesMs[firstFrame + $0] - timesMs[firstFrame] >= params.minPeriodMs }) else { return 0 }
        let maxLag = count * 3 / 5
        if maxLag <= minLag { return 0 }
        var sims = [Float](repeating: 0, count: maxLag + 1)
        for lag in minLag...maxLag {
            var acc: Float = 0
            var pairs = 0
            for i in 0..<(count - lag) {
                let a = signature[i]
                let c = signature[i + lag]
                var dot: Float = 0
                for k in 0..<cellsTotal { dot += a[k] * c[k] }
                acc += dot / (norms[i] * norms[i + lag])
                pairs += 1
            }
            sims[lag] = pairs == 0 ? 0 : acc / Float(pairs)
        }
        var best: Float = 0
        var lowest: Float = 1
        for lag in minLag...maxLag { if sims[lag] > best { best = sims[lag] }; if sims[lag] < lowest { lowest = sims[lag] } }
        if best < params.minLoopSimilarity { return 0 }
        let margin: Float = 0.05
        if best - lowest < margin { return 0 }
        for lag in minLag...maxLag {
            let isPeak = (lag == minLag || sims[lag] >= sims[lag - 1]) && (lag == maxLag || sims[lag] >= sims[lag + 1])
            if !isPeak || sims[lag] < best - 0.02 { continue }
            if lag == minLag {
                let harmonic = 2 * lag
                if harmonic > maxLag || sims[harmonic] < best - margin { continue }
            }
            let dipEnd = min(maxLag, 2 * lag)
            var dip = false
            for k in minLag...dipEnd where k != lag && sims[k] < best - margin { dip = true; break }
            if dip { return lag }
        }
        return 0
    }
}
