import UIKit
import ImageIO
import UniformTypeIdentifiers

/// Immagini sintetiche del pannello sticker di TikTok: servono ai test nel simulatore e alla
/// modalità dimostrativa usata per le schermate (`-WhatikDemo YES`).
enum DemoImages {
    static let tileCount = 8
    static let emojis = ["😂", "🔥", "😎", "🥲", "🤯", "👀", "💀", "🫠"]
    static let colors: [UIColor] = [
        UIColor(red: 0.99, green: 0.17, blue: 0.33, alpha: 1), UIColor(red: 0.15, green: 0.80, blue: 0.93, alpha: 1),
        UIColor(red: 1.00, green: 0.76, blue: 0.10, alpha: 1), UIColor(red: 0.42, green: 0.32, blue: 0.95, alpha: 1),
        UIColor(red: 0.20, green: 0.78, blue: 0.40, alpha: 1), UIColor(red: 1.00, green: 0.50, blue: 0.20, alpha: 1),
        UIColor(red: 0.30, green: 0.30, blue: 0.36, alpha: 1), UIColor(red: 0.95, green: 0.40, blue: 0.75, alpha: 1),
    ]
    /// Schermo di riferimento in punti (iPhone da 390x844).
    static let screen = CGSize(width: 390, height: 844)
    static let panelTop: CGFloat = 300

    /// Tessera `i` (4 per fila) nel sistema di riferimento di 390x844 punti.
    static func tileRect(_ i: Int) -> CGRect {
        CGRect(x: 22 + CGFloat(i % 4) * 93, y: 400 + CGFloat(i / 4) * 93, width: 80, height: 80)
    }

    /// Screenshot finto del pannello a 3x (1170x2532): video in alto, pannello bianco, 2 file di tessere.
    static func panelScreenshot() -> Data? {
        let scale: CGFloat = 3
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: screen.width * scale, height: screen.height * scale), format: format)
        let image = renderer.image { context in
            let c = context.cgContext
            c.scaleBy(x: scale, y: scale)
            drawChrome(c, videoPhase: 0)
            for i in 0..<tileCount {
                let rect = tileRect(i)
                let path = UIBezierPath(roundedRect: rect, cornerRadius: 10)
                colors[i].setFill()
                path.fill()
                let text = NSAttributedString(string: emojis[i], attributes: [.font: UIFont.systemFont(ofSize: 46)])
                let size = text.size()
                text.draw(at: CGPoint(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2))
            }
        }
        return image.pngData()
    }

    /// Fotogramma di una registrazione finta a 1x: tessere ferme con un cerchio bianco, e la tessera
    /// `animatedTile` con una barra che la attraversa in 4 passi da 250 ms (loop di 1 s).
    /// Il "video" in alto cambia a ogni fotogramma, come quando i commenti sono aperti.
    static func recordingFrame(timeMs: Int, animatedTile: Int) -> CGImage? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: screen, format: format)
        let image = renderer.image { context in
            let c = context.cgContext
            drawChrome(c, videoPhase: timeMs / 50)
            for i in 0..<tileCount {
                let rect = tileRect(i)
                colors[i].setFill()
                c.fill(rect)
                UIColor.white.setFill()
                if i == animatedTile {
                    let step = CGFloat((timeMs / 250) % 4)
                    c.fill(CGRect(x: rect.minX + step * 20, y: rect.minY, width: 20, height: rect.height))
                } else {
                    c.fillEllipse(in: rect.insetBy(dx: 22, dy: 22))
                }
            }
        }
        return image.cgImage
    }

    /// Parte alta dello schermo (video che scorre) e titolo del pannello.
    private static func drawChrome(_ c: CGContext, videoPhase: Int) {
        UIColor(white: 0.1, alpha: 1).setFill()
        c.fill(CGRect(x: 0, y: 0, width: screen.width, height: panelTop))
        var seed = UInt32(truncatingIfNeeded: videoPhase &* 2654435761)
        for by in 0..<10 {
            for bx in 0..<13 {
                seed = seed &* 1664525 &+ 1013904223
                let v = CGFloat((seed >> 24) & 0xFF) / 255
                UIColor(red: v, green: v * 0.6, blue: 1 - v, alpha: 1).setFill()
                c.fill(CGRect(x: 10 + CGFloat(bx) * 28, y: 20 + CGFloat(by) * 26, width: 28, height: 26))
            }
        }
        UIColor.white.setFill()
        c.fill(CGRect(x: 0, y: panelTop, width: screen.width, height: screen.height - panelTop))
        let title = NSAttributedString(string: "Salvati", attributes: [
            .font: UIFont.boldSystemFont(ofSize: 15), .foregroundColor: UIColor.darkGray,
        ])
        title.draw(at: CGPoint(x: 22, y: 360))
    }

    /// GIF animata con un'emoji che rimbalza, per provare la decodifica e per la demo.
    static func animatedGIF(size: Int, frames: Int, delay: Double) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.gif.identifier as CFString, frames, nil) else { return nil }
        CGImageDestinationSetProperties(destination, [
            kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0],
        ] as CFDictionary)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let side = CGFloat(size)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format)
        for f in 0..<frames {
            let image = renderer.image { context in
                UIColor(red: 0.15, green: 0.80, blue: 0.93, alpha: 1).setFill()
                context.fill(CGRect(x: 0, y: 0, width: side, height: side))
                let text = NSAttributedString(string: "🕺", attributes: [.font: UIFont.systemFont(ofSize: side * 0.55)])
                let s = text.size()
                let bounce = sin(Double(f) / Double(max(1, frames)) * 2 * Double.pi) * Double(side) * 0.12
                text.draw(at: CGPoint(x: (side - s.width) / 2, y: (side - s.height) / 2 + CGFloat(bounce)))
            }
            guard let cg = image.cgImage else { return nil }
            CGImageDestinationAddImage(destination, cg, [
                kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: delay, kCGImagePropertyGIFUnclampedDelayTime: delay],
            ] as CFDictionary)
        }
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}
