import Foundation
import WhatikCore

/// Importazione da link: file immagine diretto oppure pagina in cui cercare le immagini.
enum LinkImporter {
    enum Fetched {
        case image(Data, String)
        case page([RemoteCandidate])
        case failure(String)
    }

    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    static func fetch(_ link: String) async -> Fetched {
        guard let url = URL(string: link) else { return .failure("Link non valido") }
        do {
            let data = try await download(url)
            if ImageKind.sniff(data) != .unknown { return .image(data, URLParsing.nameFromURL(link)) }
            let html = String(decoding: data, as: UTF8.self)
            return .page(URLParsing.findImages(html: html, pageURL: link))
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    static func download(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw WhatikError.conversion("Il sito ha risposto con l'errore \(http.statusCode)")
        }
        guard data.count <= StickerLibrary.maxImportBytes else { throw WhatikError.conversion("File troppo grande") }
        return data
    }
}
