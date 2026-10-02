import XCTest
@testable import WhatikCore

final class PackPlannerTests: XCTestCase {
    func items(_ n: Int, _ animated: Bool, _ prefix: String? = nil) -> [PackPlanner.Item] {
        (0..<n).map { PackPlanner.Item(id: "\(prefix ?? (animated ? "a" : "s"))\($0)", animated: animated) }
    }

    func testSingleStickerMakesOneNewPackFlaggedTooSmall() {
        let plan = PackPlanner.plan(items: items(1, false), mixMode: .separate)
        XCTAssertEqual(plan.packs.count, 1)
        XCTAssertTrue(plan.packs[0].isNew)
        XCTAssertEqual(plan.tooSmall.count, 1)
        XCTAssertFalse(plan.packs[0].isAddable)
    }

    func testSplitsIntoChunksOfThirty() {
        let plan = PackPlanner.plan(items: items(65, false), mixMode: .separate)
        XCTAssertEqual(plan.packs.map { $0.items.count }, [30, 30, 5])
        XCTAssertTrue(plan.tooSmall.isEmpty)
    }

    func testRebalancesLastPackAboveMinimum() {
        let plan = PackPlanner.plan(items: items(31, false), mixMode: .separate)
        XCTAssertEqual(plan.packs.map { $0.items.count }, [28, 3])
        XCTAssertEqual(Set(plan.packs.flatMap { $0.items }.map { $0.id }).count, 31)
    }

    func testSeparatesStaticAndAnimated() {
        let plan = PackPlanner.plan(items: items(5, false) + items(4, true), mixMode: .separate)
        XCTAssertEqual(plan.packs.count, 2)
        XCTAssertEqual(plan.packs.first { !$0.animated }?.items.count, 5)
        XCTAssertEqual(plan.packs.first { $0.animated }?.items.count, 4)
    }

    func testConvertAnimatedToStaticMergesGroups() {
        let plan = PackPlanner.plan(items: items(5, false) + items(4, true), mixMode: .allStatic)
        XCTAssertEqual(plan.packs.count, 1)
        XCTAssertFalse(plan.packs[0].animated)
        XCTAssertEqual(plan.packs[0].items.count, 9)
    }

    func testAllAnimatedPutsMixedSelectionInOneAnimatedPack() {
        let plan = PackPlanner.plan(items: items(5, false) + items(4, true), mixMode: .allAnimated)
        XCTAssertEqual(plan.packs.count, 1)
        XCTAssertTrue(plan.packs[0].animated)
        XCTAssertTrue(plan.packs[0].items.allSatisfy { $0.animated })
    }

    func testFillsExistingPackThenOverflowsIntoNewOnes() {
        let target = PackPlanner.Target(identifier: "p1", name: "Pack", animated: false, stickerCount: 28)
        let plan = PackPlanner.plan(items: items(12, false), mixMode: .separate, staticTarget: target)
        XCTAssertEqual(plan.packs.count, 2)
        XCTAssertEqual(plan.packs[0].existingIdentifier, "p1")
        XCTAssertEqual(plan.packs[0].items.count, 2)
        XCTAssertEqual(plan.packs[0].resultingCount, 30)
        XCTAssertNil(plan.packs[1].existingIdentifier)
        XCTAssertEqual(plan.packs[1].items.count, 10)
    }

    func testExistingTargetOfWrongKindIsIgnored() {
        let target = PackPlanner.Target(identifier: "p1", name: "Pack", animated: true, stickerCount: 2)
        let plan = PackPlanner.plan(items: items(4, false), mixMode: .separate, staticTarget: target)
        XCTAssertEqual(plan.packs.count, 1)
        XCTAssertTrue(plan.packs[0].isNew)
    }

    func testPackNamesAreDistinctAndDescriptive() {
        let plan = PackPlanner.plan(items: items(35, false) + items(3, true), mixMode: .separate)
        XCTAssertEqual(plan.packs.map { PackPlanner.packName(baseName: "TikTok", plan: plan, pack: $0) }, ["TikTok statici 1", "TikTok statici 2", "TikTok animati"])
        let single = PackPlanner.plan(items: items(3, false), mixMode: .separate)
        XCTAssertEqual(PackPlanner.packName(baseName: "TikTok", plan: single, pack: single.packs[0]), "TikTok")
        XCTAssertEqual(PackPlanner.packName(baseName: "   ", plan: single, pack: single.packs[0]), "Sticker")
    }
}

final class TimingAndParsingTests: XCTestCase {
    func testSelectFrames() {
        XCTAssertEqual(AnimationTiming.selectFrames(count: 3, maxFrames: 10), [0, 1, 2])
        XCTAssertEqual(AnimationTiming.selectFrames(count: 10, maxFrames: 4), [0, 2, 5, 7])
    }

    func testMergeDurations() {
        let durations = [10, 20, 30, 40, 50]
        let merged = AnimationTiming.mergeDurations(durations, kept: [0, 2, 4])
        XCTAssertEqual(merged, [30, 70, 50])
        XCTAssertEqual(AnimationTiming.mergeDurations([1, 1], kept: [0, 1]), [8, 8])
    }

    func testTrimmedDurationsCapAtTenSeconds() {
        XCTAssertEqual(AnimationTiming.trimmedDurations([10, 100, 200]), [100, 100, 200])
        let long = AnimationTiming.trimmedDurations([Int](repeating: 1000, count: 15))
        XCTAssertEqual(long.count, 10)
        XCTAssertEqual(AnimationTiming.trimmedDurations([8000, 8000]).reduce(0, +), 10_000)
    }

    func testStartTimesAreCumulative() {
        XCTAssertEqual(TrimmedSource.startTimes([100, 150, 50, 200]), [0, 100, 250, 300])
        XCTAssertEqual(TrimmedSource.startTimes([]), [])
    }

    func testTrimmedSourceKeepsFramesInsideTheRange() throws {
        let frames = (0..<5).map { Raster(width: 2, height: 2, fill: ARGB(0xFF000000 | UInt32($0))) }
        let trimmed = TrimmedSource(ArraySource(frames: frames, durationsMs: [100, 100, 100, 100, 100]), startMs: 100, endMs: 300)
        XCTAssertEqual(trimmed.durationsMs, [100, 100, 100])
        var seen: [ARGB] = []
        try trimmed.produce { _, f in seen.append(f[0, 0]); return true }
        XCTAssertEqual(seen, [0xFF000001, 0xFF000002, 0xFF000003])
    }

    func testExtractURL() {
        XCTAssertEqual(URLParsing.extractURL("Guarda questo! https://vm.tiktok.com/ZMabc123/ 🔥"), "https://vm.tiktok.com/ZMabc123/")
        XCTAssertEqual(URLParsing.extractURL("  https://x.y/z. "), "https://x.y/z")
        XCTAssertNil(URLParsing.extractURL("nessun link qui"))
    }

    func testNameFromURL() {
        let url = "https://p16-tiktok-dm-sticker-sign-sg.ibyteimg.com/tos-alisg/abc123~tplv-video2sticker-mid.awebp?x-expires=1"
        XCTAssertEqual(URLParsing.nameFromURL(url), "abc123 tplv-video2sticker-mid")
        XCTAssertEqual(URLParsing.nameFromURL("https://example.com/"), "sticker")
    }

    func testFindImagesUnescapesJSONAndRanksStickersFirst() {
        let html = """
        <html><head><meta property="og:image" content="https://cdn.example.com/cover.jpg"></head>
        <body><script>{"sticker":{"url":"https://p16-sign.ibyteimg.com/tos/abc~tplv-video2sticker-mid.awebp?x=1&y=2"}}</script>
        <img src="https://cdn.example.com/photo.png?v=3"> <a href="https://cdn.example.com/page.html">x</a>
        <img src="https://cdn.example.com/photo.png?v=4">
        </body></html>
        """
        let found = URLParsing.findImages(html: html, pageURL: "https://example.com/p")
        XCTAssertEqual(found.count, 3)
        XCTAssertEqual(found.first?.url, "https://p16-sign.ibyteimg.com/tos/abc~tplv-video2sticker-mid.awebp?x=1&y=2")
        XCTAssertEqual(found.first?.looksSticker, true)
        XCTAssertEqual(found.dropFirst().map { $0.url }, ["https://cdn.example.com/cover.jpg", "https://cdn.example.com/photo.png?v=3"])
    }

    func testSniffAndNames() {
        XCTAssertEqual(ImageKind.sniff(Data("GIF89a....".utf8)), .gif)
        XCTAssertEqual(ImageKind.sniff(Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])), .png)
        XCTAssertEqual(ImageKind.sniff(Data([0xFF, 0xD8, 0xFF, 0xE0])), .jpeg)
        XCTAssertEqual(StickerNames.stripExtension("gatto.webp"), "gatto")
        XCTAssertEqual(StickerNames.stripExtension("nome.lunghissimo"), "nome.lunghissimo")
        XCTAssertTrue(StickerNames.hasImageExtension("A.JPG"))
    }

    func testSHA256() {
        XCTAssertEqual(SHA256.hex(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(SHA256.hex(Data()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }
}

final class CachedSourceTests: XCTestCase {
    final class CountingSource: FrameSource {
        var reads = 0
        let width = 2, height = 2
        let durationsMs = [100, 100, 100]
        func produce(_ consume: (Int, Raster) throws -> Bool) throws {
            reads += 1
            for i in 0..<3 where try !consume(i, Raster(width: 2, height: 2, fill: ARGB(0xFF000000 | UInt32(i)))) { return }
        }
    }

    func testFramesAreReadOnce() throws {
        let inner = CountingSource()
        let cached = CachedSource(inner)
        var first: [ARGB] = []
        try cached.produce { _, f in first.append(f[0, 0]); return true }
        try cached.produce { _, _ in true }
        XCTAssertEqual(inner.reads, 1)
        XCTAssertEqual(first, [0xFF000000, 0xFF000001, 0xFF000002])
    }
}
