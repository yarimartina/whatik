import XCTest
import UIKit
import AVFoundation
import WhatikCore
import WhatikMedia

/// Prove nel simulatore delle parti che esistono solo su iOS: conversioni con CoreGraphics,
/// decodifica con ImageIO, lettura dei video con AVFoundation e l'analisi completa di uno
/// screenshot e di una registrazione del pannello (sintetici).
final class MediaPipelineTests: XCTestCase {
    private let progress = ProgressReporter { _, _ in }

    private func tempDir() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("whatik-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func testRasterRoundTripKeepsOrientationAndAlpha() throws {
        var raster = Raster(width: 4, height: 2)
        raster[0, 0] = 0xFFFF0000
        raster[1, 0] = 0x8000FF00
        raster[3, 1] = 0xFF0000FF
        let image = try XCTUnwrap(raster.cgImage())
        let back = try Raster(cgImage: image)
        XCTAssertEqual(back[0, 0], 0xFFFF0000)
        XCTAssertEqual(alpha(back[1, 0]), 0x80)
        XCTAssertLessThanOrEqual(abs(green(back[1, 0]) - 255), 3)
        XCTAssertEqual(alpha(back[2, 0]), 0)
        XCTAssertEqual(back[3, 1], 0xFF0000FF)
    }

    func testAnimatedGIFIsDecodedFrameByFrame() throws {
        let data = try XCTUnwrap(DemoImages.animatedGIF(size: 120, frames: 3, delay: 0.1))
        XCTAssertTrue(try MediaDecoder.probe(data).animated)
        let source = try MediaDecoder.open(data)
        XCTAssertEqual(source.frameCount, 3)
        XCTAssertEqual(source.durationsMs, [100, 100, 100])
        let result = try StickerConverter.convert(source, forceStatic: false)
        XCTAssertTrue(result.animated)
        XCTAssertEqual(WebPCodec.features(result.bytes)?.width, 512)
        XCTAssertGreaterThanOrEqual(WebPCodec.features(result.bytes)?.frameCount ?? 0, 2)
    }

    func testScreenshotOfThePanelGivesOneStickerPerTile() throws {
        let data = try XCTUnwrap(DemoImages.panelScreenshot())
        let result = try Analyzer.analyzeScreenshot(data: data, progress: progress)
        XCTAssertEqual(result.candidates.count, DemoImages.tileCount, result.message ?? "")
        let library = StickerLibrary(root: tempDir())
        let report = try Analyzer.createStickers(result, result.candidates, library: library, progress: progress)
        XCTAssertEqual(report.items.count, DemoImages.tileCount, "\(report.failures)")
        XCTAssertTrue(report.items.allSatisfy { !$0.animated && $0.width == 512 && $0.height == 512 })
    }

    func testVideoReaderKeepsTheFrameUpright() throws {
        let url = try VideoSynth.panelRecording(seconds: 1, fps: 10, animatedTile: 2)
        let reader = try VideoReader(url: url)
        XCTAssertEqual(reader.width, Int(DemoImages.screen.width))
        XCTAssertEqual(reader.height, Int(DemoImages.screen.height))
        let frame = try XCTUnwrap(try reader.frame(atMs: 0, crop: nil, maxWidth: nil))
        // in alto il "video" scuro e colorato, in basso il pannello bianco
        XCTAssertGreaterThan(red(frame[200, 800]), 230)
        XCTAssertGreaterThan(green(frame[200, 800]), 230)
        let tile = DemoImages.tileRect(0)
        let inside = frame[Int(tile.minX) + 5, Int(tile.minY) + 5]
        XCTAssertGreaterThan(red(inside), 200)
        XCTAssertLessThan(green(inside), 100)
        // ritaglio e riduzione: la tessera 0 occupa il riquadro
        let crop = CropSpec.fromPixels(left: Int(tile.minX), top: Int(tile.minY), width: Int(tile.width), height: Int(tile.height),
                                       imageWidth: reader.width, imageHeight: reader.height)
        let small = try XCTUnwrap(try reader.frame(atMs: 0, crop: crop, maxWidth: 40))
        XCTAssertEqual(small.width, 40)
        XCTAssertGreaterThan(red(small[3, 3]), 200)
    }

    func testRecordingOfThePanelFindsTheAnimatedTileAndItsLoop() throws {
        let url = try VideoSynth.panelRecording(seconds: 5, fps: 20, animatedTile: 2)
        let result = try Analyzer.analyzeVideo(url: url, progress: progress)
        XCTAssertEqual(result.candidates.count, DemoImages.tileCount, result.message ?? "")
        let animated = result.candidates.enumerated().filter { $0.element.animated }
        XCTAssertEqual(animated.map { $0.offset }, [2])
        if let loop = animated.first?.element {
            XCTAssertEqual(Double(loop.endMs - loop.startMs), 1000, accuracy: 200)
        }
        let library = StickerLibrary(root: tempDir())
        let report = try Analyzer.createStickers(result, result.candidates, library: library, progress: progress)
        XCTAssertEqual(report.items.count, DemoImages.tileCount, "\(report.failures)")
        let animatedItems = report.items.filter { $0.animated }
        XCTAssertEqual(animatedItems.count, 1)
        if let item = animatedItems.first {
            let features = WebPCodec.features(try library.data(item))
            XCTAssertEqual(features?.width, 512)
            XCTAssertGreaterThanOrEqual(features?.frameCount ?? 0, 2)
        }
    }

    func testWhatsAppPayloadForAMixedPack() throws {
        let library = StickerLibrary(root: tempDir())
        let store = PackStore(root: tempDir())
        let screenshot = try XCTUnwrap(DemoImages.panelScreenshot())
        let analysis = try Analyzer.analyzeScreenshot(data: screenshot, progress: progress)
        _ = try Analyzer.createStickers(analysis, Array(analysis.candidates.prefix(3)), library: library, progress: progress)
        let gif = try XCTUnwrap(DemoImages.animatedGIF(size: 200, frames: 4, delay: 0.1))
        _ = library.importData(gif, name: "gif", source: "test", probe: MediaDecoder.probe)
        let operations = PackOperations(library: library, store: store, open: MediaDecoder.open)
        let items = library.items.map { PackPlanner.Item(id: $0.id, animated: $0.animated) }
        XCTAssertEqual(items.count, 4)
        let plan = PackPlanner.plan(items: items, mixMode: .allAnimated)
        let report = try operations.export(plan: plan, config: .init(baseName: "Prova", publisher: "Test", mixMode: .allAnimated, emojis: ["😂"]), progress: { _, _, _ in })
        XCTAssertTrue(report.failures.isEmpty, "\(report.failures)")
        let pack = try XCTUnwrap(report.packs.first)
        XCTAssertTrue(pack.animated)
        XCTAssertEqual(pack.stickers.count, 4)
        let payload = try operations.whatsAppPayload(pack)
        XCTAssertTrue(payload.problems.isEmpty, "\(payload.problems)")
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: payload.json) as? [String: Any])
        XCTAssertEqual((json["stickers"] as? [[String: Any]])?.count, 4)
    }
}

/// Registrazione sintetica del pannello, codificata in Motion JPEG (disponibile ovunque, anche
/// nei simulatori senza codificatore hardware).
enum VideoSynth {
    static func panelRecording(seconds: Double, fps: Int32, animatedTile: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("synth-\(UUID().uuidString).mov")
        let width = Int(DemoImages.screen.width), height = Int(DemoImages.screen.height)
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.jpeg,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [AVVideoQualityKey: 0.9],
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
        ])
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? WhatikError.conversion("Scrittura video non avviata") }
        writer.startSession(atSourceTime: .zero)
        let total = Int(seconds * Double(fps))
        for i in 0..<total {
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.005) }
            let ms = i * 1000 / Int(fps)
            guard let image = DemoImages.recordingFrame(timeMs: ms, animatedTile: animatedTile),
                  let pool = adaptor.pixelBufferPool else { throw WhatikError.conversion("Fotogramma non creato") }
            var created: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &created)
            guard let buffer = created else { throw WhatikError.conversion("Buffer non creato") }
            CVPixelBufferLockBaseAddress(buffer, [])
            let context = CGContext(
                data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
            )
            context?.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            CVPixelBufferUnlockBaseAddress(buffer, [])
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps)) else {
                throw writer.error ?? WhatikError.conversion("Fotogramma \(i) non scritto")
            }
        }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        guard writer.status == .completed else { throw writer.error ?? WhatikError.conversion("Video non completato") }
        return url
    }
}
