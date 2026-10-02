import SwiftUI

struct InfoView: View {
    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (build \(build))"
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("Versione", value: version)
                    LabeledContent("WhatsApp", value: WhatsAppSender.isInstalled ? "installato" : "non trovato")
                }
                Section("Come funziona") {
                    Text("Whatik ricava gli sticker di TikTok dallo schermo (registrazioni e screenshot del pannello), li converte nel formato di WhatsApp (WebP 512×512, massimo 100 KB fermi e 500 KB animati, 10 secondi) e li raccoglie in pack da 3 a 30 sticker.")
                    Text("Fermi e animati possono stare nello stesso pack: i fermi diventano animazioni di due fotogrammi identici, come fa Sticker Maker.")
                }
                Section("Differenze dalla versione Android") {
                    Text("Niente bolla sopra TikTok: iOS non permette alle app di disegnare sopra le altre né di registrare lo schermo da sole. Si usa il registratore di sistema e l'analisi trova le stesse cose: tessere del pannello, loop, sfondo da togliere.")
                    Text("I pack passano a WhatsApp tramite gli appunti, come prevede l'esempio ufficiale di WhatsApp per iOS. Whatik non può sapere se un pack è già stato aggiunto.")
                }
                Section {
                    Link("Codice sorgente su GitHub", destination: URL(string: "https://github.com/yarimartina/whatik")!)
                }
            }
            .navigationTitle("Info")
        }
    }
}
