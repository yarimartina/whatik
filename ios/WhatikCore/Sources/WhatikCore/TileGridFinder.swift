import Foundation

/// Trova le tessere del pannello sticker di TikTok in un singolo fotogramma: isole di
/// contenuto su uno sfondo uniforme, di dimensione da sticker, quasi quadrate e piene.
/// Scarta il video in alto, icone e testo, la tessera "+" e le tessere tagliate dal bordo.
public enum TileGridFinder {

    public struct Params: Sendable {
        public var minSideFraction: Float = 0.12
        public var maxSideFraction: Float = 0.42
        public var maxAspect: Float = 1.35
        public var minFill: Float = 0.55
        public var backgroundTolerance = 28
        public var borderFraction: Float = 0.01
        public var panelFraction: Float = 0.6
        public var sizeTolerance: Float = 0.15
        public init() {}
    }

    /// Tessere trovate (left, top, width, height), colore di sfondo del pannello e tessere scartate perché tagliate.
    public struct Result: Sendable {
        public var tiles: [[Int]]
        public var background: ARGB
        public var cutTiles: Int
    }

    public static func find(_ rgb: [ARGB], width: Int, height: Int, params: Params = Params()) -> [[Int]] {
        analyze(rgb, width: width, height: height, params: params).tiles
    }

    public static func analyze(_ rgb: [ARGB], width: Int, height: Int, params: Params = Params()) -> Result {
        // 1) sfondo del pannello: colore più frequente nella parte bassa del fotogramma
        // (media dei pixel del gruppo più frequente: serve preciso per togliere le bande)
        var counts: [Int: [Int]] = [:]
        let fromY = roundInt(Float(height) * (1 - params.panelFraction)).clamped(0, height - 1)
        for y in fromY..<height {
            for x in 0..<width {
                let c = rgb[y * width + x]
                counts[PixelMath.quantize(c), default: [0, 0, 0, 0]].withUnsafeMutableBufferPointer { a in
                    a[0] += 1; a[1] += red(c); a[2] += green(c); a[3] += blue(c)
                }
            }
        }
        guard let mode = counts.values.max(by: { $0[0] < $1[0] }) else { return Result(tiles: [], background: 0xFFFFFF, cutTiles: 0) }
        let bg = argb(0, mode[1] / mode[0], mode[2] / mode[0], mode[3] / mode[0])

        // 2) maschera dei pixel non sfondo, con una leggera dilatazione
        var mask = [Bool](repeating: false, count: width * height)
        for p in mask.indices { mask[p] = !PixelMath.isBackground(rgb[p], bg, tolerance: params.backgroundTolerance) }
        let dilated = PixelMath.dilate(mask, width: width, height: height, radius: 1)

        // 3) componenti connesse e filtri
        let minSide = roundInt(Float(width) * params.minSideFraction)
        let maxSide = roundInt(Float(width) * params.maxSideFraction)
        let border = max(1, roundInt(Float(width) * params.borderFraction))
        var visited = [Bool](repeating: false, count: mask.count)
        var stack = [Int](repeating: 0, count: mask.count)
        var tiles: [[Int]] = []
        for seed in dilated.indices {
            if !dilated[seed] || visited[seed] { continue }
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
                if x > 0 && dilated[p - 1] && !visited[p - 1] { visited[p - 1] = true; stack[top] = p - 1; top += 1 }
                if x < width - 1 && dilated[p + 1] && !visited[p + 1] { visited[p + 1] = true; stack[top] = p + 1; top += 1 }
                if y > 0 && dilated[p - width] && !visited[p - width] { visited[p - width] = true; stack[top] = p - width; top += 1 }
                if y < height - 1 && dilated[p + width] && !visited[p + width] { visited[p + width] = true; stack[top] = p + width; top += 1 }
            }
            let w = r - l + 1
            let h = b - t + 1
            if w < minSide || h < minSide || w > maxSide || h > maxSide { continue }
            if Float(max(w, h)) / Float(min(w, h)) > params.maxAspect { continue }
            if Float(area) / Float(w * h) < params.minFill { continue }
            if l <= border || t <= border || r >= width - 1 - border || b >= height - 1 - border { continue }
            tiles.append([l, t, w, h])
        }
        if tiles.isEmpty { return Result(tiles: tiles, background: bg, cutTiles: 0) }

        // 4) le tessere tagliate sono più piccole delle altre: si tengono quelle vicine alla mediana
        let sizes = tiles.map { max($0[2], $0[3]) }.sorted()
        let median = Float(sizes[sizes.count / 2])
        let whole = tiles.filter { Float(min($0[2], $0[3])) >= median * (1 - params.sizeTolerance) && Float(max($0[2], $0[3])) <= median * (1 + params.sizeTolerance) }
        let cut = tiles.count - whole.count
        if whole.isEmpty { return Result(tiles: whole, background: bg, cutTiles: cut) }

        // 5) completamento della griglia e 6) ordine di lettura
        let completed = completeGrid(whole, mask: mask, width: width, height: height, border: border, params: params)
        let rowTolerance = Double(completed.map { $0[3] }.reduce(0, +)) / Double(completed.count) / 2
        let sorted = completed.sorted { a, b in
            let ra = roundInt(Double(a[1]) / rowTolerance), rb = roundInt(Double(b[1]) / rowTolerance)
            return ra != rb ? ra < rb : a[0] < b[0]
        }
        return Result(tiles: sorted, background: bg, cutTiles: cut)
    }

    /// Inserisce le posizioni di griglia mancanti che contengono abbastanza contenuto non-sfondo.
    static func completeGrid(_ tiles: [[Int]], mask: [Bool], width: Int, height: Int, border: Int, params: Params) -> [[Int]] {
        let sizes = tiles.map { max($0[2], $0[3]) }.sorted()
        let size = sizes[sizes.count / 2]
        let rowTolerance = size / 2
        var rows: [[[Int]]] = []
        for t in tiles.sorted(by: { $0[1] < $1[1] }) {
            if !rows.isEmpty, abs(rows[rows.count - 1][0][1] - t[1]) <= rowTolerance {
                rows[rows.count - 1].append(t)
            } else {
                rows.append([t])
            }
        }
        var gaps: [Int] = []
        for row in rows {
            let xs = row.map { $0[0] }.sorted()
            if xs.count >= 2 { for i in 1..<xs.count where xs[i] - xs[i - 1] > size { gaps.append(xs[i] - xs[i - 1]) } }
        }
        guard let pitch = gaps.min() else { return tiles }
        var result = tiles
        for row in rows {
            let xs = row.map { $0[0] }.sorted()
            let top = roundInt(Double(row.map { $0[1] }.reduce(0, +)) / Double(row.count))
            var candidates: [Int] = []
            var x = xs[0] - pitch
            while x > border { candidates.append(x); x -= pitch }
            if xs.count >= 2 {
                for i in 1..<xs.count {
                    let a = xs[i - 1], b = xs[i]
                    let steps = roundInt(Float(b - a) / Float(pitch))
                    if steps >= 2 && Float(abs((b - a) - steps * pitch)) <= Float(pitch) * 0.15 {
                        for k in 1..<steps { candidates.append(a + k * pitch) }
                    }
                }
            }
            x = xs[xs.count - 1] + pitch
            while x + size < width - border { candidates.append(x); x += pitch }
            for cx in candidates {
                let rect = [cx, top, size, size]
                if rect[0] + size >= width - border || rect[1] + size >= height - border || rect[1] <= border { continue }
                if result.contains(where: { overlaps($0, rect) }) { continue }
                if contentFraction(mask, width: width, rect: rect) >= 0.12 { result.append(rect) }
            }
        }
        return result
    }

    private static func overlaps(_ a: [Int], _ b: [Int]) -> Bool {
        a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]
    }

    private static func contentFraction(_ mask: [Bool], width: Int, rect: [Int]) -> Float {
        let inset = roundInt(Float(rect[2]) * 0.05)
        var count = 0
        var total = 0
        let y0 = rect[1] + inset, y1 = rect[1] + rect[3] - inset
        let x0 = rect[0] + inset, x1 = rect[0] + rect[2] - inset
        if y1 <= y0 || x1 <= x0 { return 0 }
        for y in y0..<y1 {
            for x in x0..<x1 {
                let p = y * width + x
                if p < 0 || p >= mask.count { continue }
                total += 1
                if mask[p] { count += 1 }
            }
        }
        return total == 0 ? 0 : Float(count) / Float(total)
    }
}
