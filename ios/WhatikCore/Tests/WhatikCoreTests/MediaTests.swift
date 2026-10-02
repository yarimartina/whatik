import XCTest
@testable import WhatikCore
@testable import WhatikMedia

final class MediaTests: XCTestCase {

    /// Fotogramma "fotografico" (gradiente + rumore) per avere dimensioni realistiche.
    func photo(_ w: Int, _ h: Int, seed: Int = 1) -> Raster {
        var s = UInt32(truncatingIfNeeded: seed &* 2654435761)
        var px = [ARGB](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w {
            s = s &* 1664525 &+ 1013904223
            let n = Int((s >> 24) & 0x3F)
            px[y * w + x] = argb(255, (x * 255 / w + n) & 0xFF, (y * 255 / h + seed * 40) & 0xFF, (n * 3) & 0xFF)
        } }
        return Raster(width: w, height: h, pixels: px)
    }

    func testResizeKeepsColorsAndTransparency() {
        var r = Raster(width: 40, height: 20)
        for y in 0..<20 { for x in 0..<20 { r[x, y] = 0xFFFF0000 } }
        let small = r.resized(width: 20, height: 10)
        XCTAssertEqual(small[2, 5], 0xFFFF0000)
        XCTAssertEqual(alpha(small[18, 5]), 0)
        let big = r.resized(width: 80, height: 40)
        XCTAssertEqual(big[10, 10], 0xFFFF0000)
        let fitted = r.fitted(toSquare: 64)
        XCTAssertEqual(fitted.width, 64)
        XCTAssertEqual(alpha(fitted[32, 2]), 0) // banda trasparente sopra
    }

    func testStaticStickerIsA512WebPUnder100KB() throws {
        let result = try StickerConverter.convert(StillSource(photo(300, 200)), forceStatic: true)
        XCTAssertFalse(result.animated)
        XCTAssertLessThanOrEqual(result.bytes.count, StickerConverter.maxStaticBytes)
        let f = try XCTUnwrap(WebPCodec.features(result.bytes))
        XCTAssertEqual(f.width, 512); XCTAssertEqual(f.height, 512); XCTAssertFalse(f.animated)
        let decoded = try WebPCodec.decodeFirstFrame(result.bytes)
        XCTAssertEqual(alpha(decoded[256, 10]), 0) // proporzioni mantenute: bande trasparenti
        XCTAssertEqual(alpha(decoded[256, 256]), 255)
    }

    func testAnimatedStickerKeepsFramesAndDurations() throws {
        let frames = (0..<12).map { photo(200, 200, seed: $0) }
        let source = ArraySource(frames: frames, durationsMs: [Int](repeating: 100, count: 12))
        let result = try StickerConverter.convert(source, forceStatic: false)
        XCTAssertTrue(result.animated)
        XCTAssertLessThanOrEqual(result.bytes.count, StickerConverter.maxAnimatedBytes)
        let decoded = try WebPFrameSource(result.bytes)
        XCTAssertEqual(decoded.width, 512)
        XCTAssertGreaterThanOrEqual(decoded.frameCount, 2)
        XCTAssertEqual(decoded.durationsMs.reduce(0, +), 1200)
    }

    func testHeavyAnimationIsReducedUnder500KB() throws {
        let frames = (0..<60).map { photo(512, 512, seed: $0) }
        let source = ArraySource(frames: frames, durationsMs: [Int](repeating: 50, count: 60))
        let result = try StickerConverter.convert(source, forceStatic: false)
        XCTAssertTrue(result.animated)
        XCTAssertLessThanOrEqual(result.bytes.count, StickerConverter.maxAnimatedBytes)
        XCTAssertEqual(try WebPFrameSource(result.bytes).durationsMs.reduce(0, +), 3000)
    }

    func testIdenticalFramesStillGiveAValidAnimatedSticker() throws {
        let still = photo(100, 100)
        let source = ArraySource(frames: [still, still, still], durationsMs: [100, 100, 100])
        let result = try StickerConverter.convert(source, forceStatic: false)
        XCTAssertTrue(result.animated)
        XCTAssertEqual(WebPCodec.features(result.bytes)?.frameCount, 2)
    }

    func testAnimateStillMakesTwoIdenticalFrames() throws {
        let still = try StickerConverter.convert(StillSource(photo(200, 200)), forceStatic: true)
        let animated = try StickerConverter.animateStill(still.bytes)
        XCTAssertTrue(animated.animated)
        let decoded = try WebPFrameSource(animated.bytes)
        XCTAssertEqual(decoded.frameCount, 2)
        XCTAssertEqual(decoded.durationsMs, [500, 500])
        var frames: [Raster] = []
        try decoded.produce { _, f in frames.append(f); return true }
        XCTAssertEqual(frames[0], frames[1])
    }

    func testConvertTypeRoundTrip() throws {
        let still = try StickerConverter.convert(StillSource(photo(200, 200)), forceStatic: true).bytes
        XCTAssertEqual(try StickerConverter.convertType(still, animated: false), still)
        let animated = try StickerConverter.convertType(still, animated: true)
        XCTAssertEqual(WebPCodec.features(animated)?.animated, true)
        XCTAssertEqual(try StickerConverter.convertType(animated, animated: true), animated)
        let back = try StickerConverter.convertType(animated, animated: false)
        XCTAssertEqual(WebPCodec.features(back)?.animated, false)
    }

    func testTrayIconIsA96PNGUnder50KB() throws {
        let sticker = try StickerConverter.convert(StillSource(photo(300, 300)), forceStatic: true).bytes
        let png = try StickerConverter.makeTrayIcon(sticker)
        XCTAssertLessThanOrEqual(png.count, StickerConverter.maxTrayBytes)
        XCTAssertEqual([UInt8](png.prefix(8)), [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        let header = [UInt8](png[16..<24])
        XCTAssertEqual(header, [0, 0, 0, 96, 0, 0, 0, 96])
    }

    // MARK: archivio e pack

    func tempDir() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("whatik-tests-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func probe(_ data: Data) throws -> StickerLibrary.Probe {
        guard let f = WebPCodec.features(data) else { throw WhatikError.unsupportedImage }
        return StickerLibrary.Probe(width: f.width, height: f.height, animated: f.animated)
    }

    func makeLibrary(_ count: Int, animated: Bool = false) throws -> (StickerLibrary, [StickerItem]) {
        let lib = StickerLibrary(root: tempDir())
        var items: [StickerItem] = []
        for i in 0..<count {
            let data: Data
            if animated {
                let frames = (0..<3).map { photo(64, 64, seed: i * 10 + $0) }
                data = try WebPCodec.encodeAnimation(width: 64, height: 64, quality: 60) { add in for f in frames { try add(f, 100) } }
            } else {
                data = try WebPCodec.encode(photo(64, 64, seed: i), quality: 80, lossless: false)
            }
            let r = lib.importData(data, name: "sticker \(i).webp", source: "test", probe: probe)
            items.append(try XCTUnwrap(r.item))
        }
        return (lib, items)
    }

    func testLibraryDeduplicatesAndPersists() throws {
        let (lib, items) = try makeLibrary(2)
        XCTAssertEqual(items[0].displayName, "sticker 0")
        let again = lib.importData(try lib.data(items[0]), name: "copia", source: "test", probe: probe)
        if case .duplicate(let item) = again { XCTAssertEqual(item.id, items[0].id) } else { XCTFail("doppione non riconosciuto") }
        let reopened = StickerLibrary(root: lib.root)
        XCTAssertEqual(reopened.items.map { $0.id }, lib.items.map { $0.id })
        lib.delete([items[0].id])
        XCTAssertEqual(StickerLibrary(root: lib.root).items.count, 1)
        if case .failed = lib.importData(Data("ciao".utf8), name: "x", source: "test", probe: probe) {} else { XCTFail("dati non validi accettati") }
    }

    func testExportMergeAndWhatsAppPayload() throws {
        let (lib, items) = try makeLibrary(4)
        let allItems = items
        let store = PackStore(root: tempDir())
        let ops = PackOperations(library: lib, store: store, open: { try WebPFrameSource($0) })
        let plan = PackPlanner.plan(items: allItems.map { PackPlanner.Item(id: $0.id, animated: $0.animated) }, mixMode: .separate)
        let report = try ops.export(plan: plan, config: .init(baseName: "Prova", publisher: "Io", mixMode: .separate, emojis: ["😂"]), progress: { _, _, _ in })
        XCTAssertTrue(report.failures.isEmpty, "\(report.failures)")
        XCTAssertEqual(report.packs.count, 1)
        let pack = report.packs[0]
        XCTAssertEqual(pack.stickers.count, 4)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.trayURL(pack).path))

        // rimozione e rinomina
        let removed = try XCTUnwrap(try store.removeStickers(pack.identifier, fileNames: [pack.stickers[0].fileName]))
        XCTAssertEqual(removed.stickers.count, 3)
        XCTAssertGreaterThan(removed.imageDataVersion, pack.imageDataVersion)
        let renamed = try XCTUnwrap(try store.rename(pack.identifier, name: "Nuovo <nome>", publisher: ""))
        XCTAssertEqual(renamed.name, "Nuovo nome")
        XCTAssertEqual(renamed.publisher, "Io")

        // unione con un secondo pack, risultato animato
        let second = try ops.export(plan: PackPlanner.plan(items: items.prefix(3).map { PackPlanner.Item(id: $0.id, animated: false) }, mixMode: .separate),
                                    config: .init(baseName: "Altro", publisher: "Io", mixMode: .separate, emojis: ["🔥"]), progress: { _, _, _ in }).packs[0]
        let merged = try ops.merge(targetId: pack.identifier, sourceIds: [second.identifier], resultAnimated: true, deleteSources: true, progress: { _, _ in })
        XCTAssertTrue(merged.failures.isEmpty, "\(merged.failures)")
        let result = try XCTUnwrap(store.load(pack.identifier))
        XCTAssertTrue(result.animated)
        XCTAssertEqual(result.stickers.count, 6)
        XCTAssertNil(store.load(second.identifier))
        for s in result.stickers { XCTAssertEqual(WebPCodec.features(try store.readSticker(result, s))?.animated, true) }

        // dati per WhatsApp
        let payload = try ops.whatsAppPayload(result)
        XCTAssertTrue(payload.problems.isEmpty, "\(payload.problems)")
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: payload.json) as? [String: Any])
        XCTAssertEqual(json["identifier"] as? String, result.identifier)
        XCTAssertEqual(json["animated_sticker_pack"] as? Bool, true)
        XCTAssertEqual((json["stickers"] as? [[String: Any]])?.count, 6)
        XCTAssertNotNil(Data(base64Encoded: json["tray_image"] as? String ?? ""))
    }

    func testAddLibraryItemsStopsAtThirty() throws {
        let (lib, items) = try makeLibrary(5)
        let store = PackStore(root: tempDir())
        let ops = PackOperations(library: lib, store: store, open: { try WebPFrameSource($0) })
        let pack = try store.createPack(name: "Pieno", publisher: "Io", animated: false)
        // riempio fino a 28
        let filler = try (0..<28).map { _ in ConvertedSticker(bytes: try lib.data(items[0]), animated: false, emojis: ["😀"], accessibilityText: nil, sourceId: nil) }
        try store.addStickers(pack.identifier, filler, trayIcon: StickerConverter.makeTrayIcon)
        let report = try ops.addLibraryItems(packId: pack.identifier, itemIds: items.map { $0.id }, emojis: ["😀"], progress: { _, _ in })
        XCTAssertEqual(report.added, 2)
        XCTAssertEqual(report.skipped, 3)
        XCTAssertEqual(store.load(pack.identifier)?.stickers.count, 30)
    }

    func testAnimatedLibraryItemsGoIntoAnAnimatedPack() throws {
        let (lib, items) = try makeLibrary(3, animated: true)
        XCTAssertTrue(items.allSatisfy { $0.animated })
        let store = PackStore(root: tempDir())
        let ops = PackOperations(library: lib, store: store, open: { try WebPFrameSource($0) })
        let plan = PackPlanner.plan(items: items.map { PackPlanner.Item(id: $0.id, animated: $0.animated) }, mixMode: .separate)
        let report = try ops.export(plan: plan, config: .init(baseName: "Anim", publisher: "Io", mixMode: .separate, emojis: ["😀"]), progress: { _, _, _ in })
        XCTAssertTrue(report.failures.isEmpty, "\(report.failures)")
        XCTAssertEqual(report.packs.first?.animated, true)
        XCTAssertEqual(report.packs.first?.stickers.count, 3)
    }
}
