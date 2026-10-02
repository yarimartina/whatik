import XCTest
@testable import WhatikCore

final class StickerCleanerTests: XCTestCase {
    private let w = 160
    private let h = 180
    /// Pannello di TikTok e sfondo del meme misurati sullo stesso screenshot reale.
    private let panel: ARGB = 0xF8F8F8
    private let memeBg: ARGB = 0xF1F6F9

    private func canvas(_ color: ARGB? = nil) -> [ARGB] {
        [ARGB](repeating: 0xFF00_0000 | (color ?? panel), count: w * h)
    }

    private func fill(_ img: inout [ARGB], _ l: Int, _ t: Int, _ bw: Int, _ bh: Int, _ color: ARGB) {
        for y in t..<(t + bh) { for x in l..<(l + bw) { img[y * w + x] = 0xFF00_0000 | color } }
    }

    /// Rumore tipo JPEG (±2 per canale), riproducibile.
    private func noise(_ img: inout [ARGB]) {
        var state: UInt64 = 7
        func next() -> Int {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return Int((state >> 33) % 5) - 2
        }
        for i in img.indices {
            let c = img[i]
            img[i] = argb(alpha(c), (red(c) + next()).clamped(0, 255), (green(c) + next()).clamped(0, 255), (blue(c) + next()).clamped(0, 255))
        }
    }

    private func meme(captionOnly: Bool = false) -> [ARGB] {
        var img = canvas()
        fill(&img, 20, 15, 120, 150, memeBg)
        for line in 0..<(captionOnly ? 1 : 2) { for letter in 0..<8 { fill(&img, 28 + letter * 13, 22 + line * 72, 8, 10, 0x202020) } }
        fill(&img, 30, 40, 100, 45, 0x806040)
        if !captionOnly { fill(&img, 30, 112, 100, 45, 0x406080) }
        noise(&img)
        return img
    }

    private func clean(_ img: inout [ARGB]) throws -> (StickerCleaner.Plan, [Int]) {
        let plan = try XCTUnwrap(StickerCleaner.plan(img, width: w, height: h, background: panel))
        return (plan, StickerCleaner.apply(&img, width: w, height: h, background: panel, plan: plan))
    }

    private func transparent(_ img: [ARGB], _ rect: [Int]) -> Int {
        var n = 0
        for y in rect[1]..<(rect[1] + rect[3]) { for x in rect[0]..<(rect[0] + rect[2]) where alpha(img[y * w + x]) < 128 { n += 1 } }
        return n
    }

    func testMemeKeepsItsOwnBackgroundAndLosesThePanelBands() throws {
        var img = meme()
        let (plan, box) = try clean(&img)
        XCTAssertFalse(plan.cutout)
        XCTAssertTrue((20...22).contains(box[0]), "\(box)")
        XCTAssertTrue((15...17).contains(box[1]), "\(box)")
        XCTAssertTrue((116...120).contains(box[2]) && (146...150).contains(box[3]), "\(box)")
        XCTAssertEqual(transparent(img, [24, 19, 112, 142]), 0)
    }

    func testMemeWithOnePhotoAndAShortCaptionKeepsItsBackground() throws {
        var img = meme(captionOnly: true)
        let (plan, _) = try clean(&img)
        XCTAssertFalse(plan.cutout)
        XCTAssertEqual(transparent(img, [24, 19, 112, 142]), 0)
    }

    func testMemeOnTheSameWhiteAsThePanelIsTrimmedButNotCutOut() throws {
        var img = canvas()
        for line in 0..<2 { for letter in 0..<8 { fill(&img, 28 + letter * 13, 22 + line * 72, 8, 10, 0x202020) } }
        fill(&img, 30, 40, 100, 45, 0x806040)
        fill(&img, 30, 112, 100, 45, 0x406080)
        noise(&img)
        let (plan, box) = try clean(&img)
        XCTAssertFalse(plan.cutout)
        XCTAssertEqual(transparent(img, box), 0)
    }

    func testSingleSubjectOnThePanelIsCutOut() throws {
        var img = canvas()
        let cx = 80, cy = 90, r = 50
        for y in 0..<h { for x in 0..<w where (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r { img[y * w + x] = 0xFFE0_5080 } }
        fill(&img, 70, 70, 12, 12, 0xFFFFFF)
        noise(&img)
        let (plan, box) = try clean(&img)
        XCTAssertTrue(plan.cutout)
        XCTAssertEqual(alpha(img[(box[1] + 2) * w + box[0] + 2]), 0)
        XCTAssertEqual(alpha(img[75 * w + 75]), 0xFF, "occhio opaco")
        XCTAssertEqual(alpha(img[cy * w + cx]), 0xFF)
        XCTAssertTrue((100...112).contains(box[2]) && (100...112).contains(box[3]), "\(box)")
    }

    func testPhotoWithRoundedTileCornersOnlyLosesTheCorners() throws {
        var img = canvas()
        fill(&img, 10, 10, 140, 160, 0x507090)
        for i in 0..<8 {
            for j in 0..<(8 - i) {
                img[(10 + i) * w + 10 + j] = 0xFF00_0000 | panel
                img[(10 + i) * w + 149 - j] = 0xFF00_0000 | panel
                img[(169 - i) * w + 10 + j] = 0xFF00_0000 | panel
                img[(169 - i) * w + 149 - j] = 0xFF00_0000 | panel
            }
        }
        let (plan, box) = try clean(&img)
        XCTAssertFalse(plan.cutout)
        XCTAssertTrue((10...12).contains(box[0]) && (136...140).contains(box[2]), "\(box)")
        XCTAssertEqual(alpha(img[11 * w + 11]), 0)
        XCTAssertEqual(alpha(img[90 * w + 80]), 0xFF)
    }

    func testAllPanelGivesNoPlan() {
        var img = canvas()
        noise(&img)
        XCTAssertNil(StickerCleaner.plan(img, width: w, height: h, background: panel))
    }

    func testCleaningSourceKeepsAMemeOpaque() throws {
        let img = meme()
        let source = StickerCleaningSource(StillSource(Raster(width: w, height: h, pixels: img)), backgroundHint: panel)
        let out = try source.firstFrame()
        XCTAssertTrue((116...120).contains(out.width) && (146...150).contains(out.height), "\(out.width)x\(out.height)")
        XCTAssertEqual(out.pixels.filter { alpha($0) < 128 }.count, 0)
    }
}
