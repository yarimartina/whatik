import UIKit
import WhatikCore

/// Passa un pack a WhatsApp come fa l'app di esempio ufficiale per iOS: JSON negli appunti
/// (solo su questo dispositivo, scade dopo 60 s) e apertura di whatsapp://stickerPack.
enum WhatsAppSender {
    @MainActor
    static var isInstalled: Bool {
        guard let url = URL(string: "whatsapp://") else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    @MainActor
    static func send(_ json: Data) -> Bool {
        UIPasteboard.general.setItems(
            [[WhatsAppPayload.pasteboardType: json]],
            options: [.localOnly: true, .expirationDate: Date(timeIntervalSinceNow: 60)]
        )
        guard isInstalled, let url = URL(string: WhatsAppPayload.openURL) else { return false }
        UIApplication.shared.open(url)
        return true
    }
}
