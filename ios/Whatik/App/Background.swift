import Foundation

/// Esegue lavoro pesante (decodifica, analisi, codifica WebP) fuori dal thread principale.
enum Background {
    static func run<T>(_ job: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    continuation.resume(returning: try job())
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}

/// Avanzamento di un lavoro in background, inoltrato all'interfaccia al massimo ogni 80 ms.
final class ProgressReporter: @unchecked Sendable {
    private let update: @MainActor (Double?, String) -> Void
    private let lock = NSLock()
    private var lastReport = Date.distantPast

    init(_ update: @escaping @MainActor (Double?, String) -> Void) {
        self.update = update
    }

    func report(_ done: Int, _ total: Int, _ detail: String = "") {
        lock.lock()
        let now = Date()
        let due = now.timeIntervalSince(lastReport) > 0.08 || done >= total
        if due { lastReport = now }
        lock.unlock()
        guard due else { return }
        let fraction = total > 0 ? min(1, Double(done) / Double(total)) : nil
        let update = self.update
        Task { @MainActor in update(fraction, detail) }
    }
}
