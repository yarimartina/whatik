import SwiftUI
import WhatikCore
import WhatikMedia

/// Avvio con argomenti, usato dalla CI per le schermate nel simulatore:
///   -WhatikTab capture|library|packs|info   scheda iniziale
///   -WhatikDemo YES                         riempie libreria e pack con sticker dimostrativi
///   -WhatikDemo analysis                    mostra l'analisi di uno screenshot dimostrativo
@MainActor
enum DemoMode {
    static func start(_ model: AppModel) {
        let defaults = UserDefaults.standard
        let tab = defaults.string(forKey: "WhatikTab")
        apply(tab, model)
        guard let mode = defaults.string(forKey: "WhatikDemo") else { return }
        Task {
            await run(mode: mode, model: model)
            apply(tab, model)
        }
    }

    private static func run(mode: String, model: AppModel) async {
        guard model.items.isEmpty || mode == "analysis", let screenshot = DemoImages.panelScreenshot() else { return }
        let result = await model.work("Preparo la demo", { progress in try Analyzer.analyzeScreenshot(data: screenshot, progress: progress) })
        guard let result = result else { return }
        if mode == "analysis" {
            model.analysis = result
            return
        }
        await model.createStickers(from: result, selected: Set(result.candidates.map { $0.id }))
        if let gif = DemoImages.animatedGIF(size: 320, frames: 8, delay: 0.12) {
            await model.importData([AppModel.Incoming(data: gif, name: "Demo animato")], source: "demo")
        }
        let config = PackOperations.ExportConfig(baseName: "Demo TikTok", publisher: "Whatik", mixMode: .allAnimated, emojis: ["😂"])
        _ = await model.export(ids: model.items.map { $0.id }, config: config, target: nil)
        model.highlighted.removeAll()
        model.toast = nil
    }

    private static func apply(_ tab: String?, _ model: AppModel) {
        switch tab {
        case "capture": model.tab = .capture
        case "library": model.tab = .library
        case "packs": model.tab = .packs
        case "info": model.tab = .info
        default: break
        }
    }
}
