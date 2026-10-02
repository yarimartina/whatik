import Foundation

/// Regole di durata degli sticker animati di WhatsApp e scelta dei fotogrammi da tenere.
public enum AnimationTiming {
    public static let maxDurationMs = 10_000
    public static let minFrameMs = 8
    /// Sotto questa soglia il ritardo viene normalizzato a `defaultFrameMs`, come fanno i browser.
    public static let shortFrameMs = 20
    public static let defaultFrameMs = 100

    /// Durate normalizzate e tagliate ai 10 secondi consentiti (almeno 2 fotogrammi).
    public static func trimmedDurations(_ raw: [Int]) -> [Int] {
        let normalized = raw.map { $0 < shortFrameMs ? defaultFrameMs : $0 }
        var total = 0
        var count = 0
        for d in normalized {
            if total + d > maxDurationMs && count >= 2 { break }
            total += d
            count += 1
        }
        var durations = Array(normalized.prefix(count))
        let sum = durations.reduce(0, +)
        if sum > maxDurationMs {
            // caso limite: pochissimi fotogrammi lunghissimi
            let scale = Double(maxDurationMs) / Double(sum)
            durations = durations.map { max(minFrameMs, Int(Double($0) * scale)) }
        }
        return durations
    }

    /// Indici dei fotogrammi da tenere, distribuiti uniformemente, sempre a partire dal primo.
    public static func selectFrames(count: Int, maxFrames: Int) -> [Int] {
        if count <= maxFrames { return Array(0..<count) }
        var seen = Set<Int>()
        return (0..<maxFrames).map { $0 * count / maxFrames }.filter { seen.insert($0).inserted }
    }

    /// La durata di un fotogramma tenuto assorbe quella dei fotogrammi scartati che lo seguono.
    public static func mergeDurations(_ durations: [Int], kept: [Int]) -> [Int] {
        kept.enumerated().map { i, start in
            let end = i + 1 < kept.count ? kept[i + 1] : durations.count
            return max(minFrameMs, durations[start..<end].reduce(0, +))
        }
    }
}
