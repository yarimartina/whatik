import Foundation

/// Arrotondamento come `Math.round` di Kotlin (`roundToInt`): al più vicino, i .5 verso l'alto.
@inlinable public func roundInt(_ x: Float) -> Int { Int((x + 0.5).rounded(.down)) }
@inlinable public func roundInt(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }

extension Comparable {
    /// Come `coerceIn` di Kotlin.
    @inlinable public func clamped(_ lo: Self, _ hi: Self) -> Self { self < lo ? lo : (self > hi ? hi : self) }
}

/// Un pixel come intero ARGB (0xAARRGGBB), lo stesso formato usato dall'app Android:
/// così gli algoritmi sono una trasposizione diretta.
public typealias ARGB = UInt32

@inlinable public func red(_ c: ARGB) -> Int { Int((c >> 16) & 0xFF) }
@inlinable public func green(_ c: ARGB) -> Int { Int((c >> 8) & 0xFF) }
@inlinable public func blue(_ c: ARGB) -> Int { Int(c & 0xFF) }
@inlinable public func alpha(_ c: ARGB) -> Int { Int((c >> 24) & 0xFF) }
@inlinable public func argb(_ a: Int, _ r: Int, _ g: Int, _ b: Int) -> ARGB {
    (ARGB(a & 0xFF) << 24) | (ARGB(r & 0xFF) << 16) | (ARGB(g & 0xFF) << 8) | ARGB(b & 0xFF)
}
@inlinable public func rgb(_ r: Int, _ g: Int, _ b: Int) -> ARGB { argb(0, r, g, b) }

/// Differenza massima per canale fra due colori (alfa ignorato).
@inlinable public func colorDistance(_ c: ARGB, _ d: ARGB) -> Int {
    max(abs(red(c) - red(d)), max(abs(green(c) - green(d)), abs(blue(c) - blue(d))))
}

public enum PixelMath {
    /// Grigio 0..255 da pixel ARGB (pesi 299/587/114 come su Android).
    public static func toGray(_ pixels: [ARGB]) -> [Int] {
        var gray = [Int](repeating: 0, count: pixels.count)
        for p in 0..<pixels.count {
            let c = pixels[p]
            gray[p] = (red(c) * 299 + green(c) * 587 + blue(c) * 114) / 1000
        }
        return gray
    }

    /// Dilatazione quadrata di raggio `radius` su una maschera booleana.
    public static func dilate(_ mask: [Bool], width: Int, height: Int, radius: Int) -> [Bool] {
        var out = [Bool](repeating: false, count: mask.count)
        for y in 0..<height {
            for x in 0..<width {
                if !mask[y * width + x] { continue }
                for dy in -radius...radius {
                    let yy = y + dy
                    if yy < 0 || yy >= height { continue }
                    for dx in -radius...radius {
                        let xx = x + dx
                        if xx < 0 || xx >= width { continue }
                        out[yy * width + xx] = true
                    }
                }
            }
        }
        return out
    }

    /// Rettangolo (left, top, right, bottom esclusivi) della componente connessa (4-vicini) che contiene (sx, sy).
    public static func componentBox(_ mask: [Bool], width: Int, height: Int, sx: Int, sy: Int) -> [Int]? {
        if !mask[sy * width + sx] { return nil }
        var visited = [Bool](repeating: false, count: mask.count)
        var stack = [Int](repeating: 0, count: mask.count)
        var top = 0
        stack[top] = sy * width + sx; top += 1
        visited[sy * width + sx] = true
        var l = width, t = height, r = -1, b = -1
        while top > 0 {
            top -= 1
            let p = stack[top]
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
        return [l, t, r + 1, b + 1]
    }

    /// Colore quantizzato a 5 bit per canale (per stimare lo sfondo come moda).
    @inlinable public static func quantize(_ c: ARGB) -> Int {
        Int(((c >> 19) & 0x1F) << 10) | Int(((c >> 11) & 0x1F) << 5) | Int((c >> 3) & 0x1F)
    }

    @inlinable public static func dequantize(_ q: Int) -> ARGB {
        let r = ((q >> 10) & 0x1F) << 3
        let g = ((q >> 5) & 0x1F) << 3
        let b = (q & 0x1F) << 3
        return rgb(r, g, b)
    }

    @inlinable public static func isBackground(_ c: ARGB, _ bg: ARGB, tolerance: Int) -> Bool {
        colorDistance(c, bg) <= tolerance
    }
}
