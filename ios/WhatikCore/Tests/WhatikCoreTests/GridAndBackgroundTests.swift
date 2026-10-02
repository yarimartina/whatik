import XCTest
@testable import WhatikCore

final class TileGridFinderTests: XCTestCase {
    let w = 480, h = 1040
    let white: ARGB = 0xFFFFFFFF
    let dark: ARGB = 0xFF202020

    func screen(_ paint: (inout [ARGB]) -> Void) -> [ARGB] {
        var img = (0..<(w * h)).map { $0 / w < 400 ? dark : white }
        paint(&img)
        return img
    }
    func fill(_ img: inout [ARGB], _ l: Int, _ t: Int, _ r: Int, _ b: Int, _ color: ARGB) {
        for y in max(0, t)..<min(h, b) { for x in max(0, l)..<min(w, r) { img[y * w + x] = color } }
    }
    func tile(_ img: inout [ARGB], _ l: Int, _ t: Int, _ color: ARGB = 0xFFE05080) { fill(&img, l, t, l + 92, t + 92, color) }
    func lightTile(_ img: inout [ARGB], _ l: Int, _ t: Int) {
        fill(&img, l + 10, t + 40, l + 82, t + 52, dark)
        fill(&img, l + 40, t + 10, l + 52, t + 82, dark)
    }
    func assertTile(_ tiles: [[Int]], _ i: Int, _ l: Int, _ t: Int, file: StaticString = #filePath, line: UInt = #line) {
        let tile = tiles[i]
        XCTAssertTrue((l - 4)...(l + 4) ~= tile[0], "tile \(i) left \(tile[0])", file: file, line: line)
        XCTAssertTrue((t - 4)...(t + 4) ~= tile[1], "tile \(i) top \(tile[1])", file: file, line: line)
        XCTAssertTrue((86...100).contains(tile[2]) && (86...100).contains(tile[3]), "tile \(i) size", file: file, line: line)
    }

    func testFindsFullRowOfTilesInReadingOrder() {
        let img = screen {
            tile(&$0, 18, 740); tile(&$0, 136, 740, 0xFF4080FF); tile(&$0, 254, 740, 0xFF40C040); tile(&$0, 372, 740, 0xFF808080)
            tile(&$0, 136, 888); tile(&$0, 254, 888)
        }
        let tiles = TileGridFinder.find(img, width: w, height: h)
        XCTAssertEqual(tiles.count, 6)
        guard tiles.count == 6 else { return }
        assertTile(tiles, 0, 18, 740); assertTile(tiles, 1, 136, 740); assertTile(tiles, 2, 254, 740)
        assertTile(tiles, 3, 372, 740); assertTile(tiles, 4, 136, 888); assertTile(tiles, 5, 254, 888)
    }

    func testIgnoresVideoIconsTextAndCutTiles() {
        let img = screen {
            tile(&$0, 18, 740); tile(&$0, 136, 740); tile(&$0, 254, 740)
            fill(&$0, 20, 60, 460, 380, 0xFF3060A0)
            fill(&$0, 20, 700, 140, 712, dark)
            fill(&$0, 440, 700, 464, 724, dark)
            fill(&$0, 18, 980, 110, 1040, 0xFFE05080)
            fill(&$0, 372, 740, 464, 832, 0xFFF4F4F4)
            fill(&$0, 414, 782, 422, 790, dark)
        }
        let tiles = TileGridFinder.find(img, width: w, height: h)
        XCTAssertEqual(tiles.count, 3)
    }

    func testRecoversLightTilesFromTheGridPitch() {
        let img = screen {
            tile(&$0, 18, 740); lightTile(&$0, 136, 740); tile(&$0, 254, 740); tile(&$0, 372, 740)
            lightTile(&$0, 18, 888); tile(&$0, 136, 888)
        }
        let tiles = TileGridFinder.find(img, width: w, height: h)
        XCTAssertEqual(tiles.count, 6)
        guard tiles.count == 6 else { return }
        assertTile(tiles, 1, 136, 740)
        assertTile(tiles, 4, 18, 888)
    }

    func testDropsTilesCutByTheNavigationBar() {
        let img = screen {
            tile(&$0, 18, 740); tile(&$0, 136, 740); tile(&$0, 254, 740); tile(&$0, 372, 740)
            fill(&$0, 18, 888, 110, 963, 0xFFE05080); fill(&$0, 254, 888, 346, 963, 0xFF4080FF)
            fill(&$0, 200, 975, 280, 981, 0xFF808080)
        }
        let result = TileGridFinder.analyze(img, width: w, height: h)
        XCTAssertEqual(result.tiles.count, 4)
        XCTAssertEqual(result.cutTiles, 2)
        XCTAssertTrue(result.tiles.allSatisfy { (736...744).contains($0[1]) })
    }

    func testEmptyTilePositionsAreNotInvented() {
        XCTAssertEqual(TileGridFinder.find(screen { tile(&$0, 18, 740); tile(&$0, 254, 740) }, width: w, height: h).count, 2)
    }

    func testNoTilesWithoutAPanel() {
        XCTAssertTrue(TileGridFinder.find([ARGB](repeating: dark, count: w * h), width: w, height: h).isEmpty)
    }
}

final class BackgroundRemoverTests: XCTestCase {
    let w = 100, h = 100
    let white: ARGB = 0xFFFFFFFF
    let redColor: ARGB = 0xFFE02020

    func frame(_ fillColor: ARGB = 0xFFFFFFFF, _ paint: (inout [ARGB]) -> Void = { _ in }) -> [ARGB] {
        var img = [ARGB](repeating: fillColor, count: w * h); paint(&img); return img
    }
    func rect(_ img: inout [ARGB], _ l: Int, _ t: Int, _ r: Int, _ b: Int, _ color: ARGB) {
        for y in t..<b { for x in l..<r { img[y * w + x] = color } }
    }
    func a(_ c: ARGB) -> Int { alpha(c) }

    func testWhiteBorderBecomesTransparentButInnerWhiteStays() {
        var img = frame { rect(&$0, 30, 30, 70, 70, redColor); rect(&$0, 40, 40, 60, 60, white) }
        let bg = BackgroundRemover.resolveBackground(img, width: w, height: h, hint: nil)
        XCTAssertEqual(bg, 0xFFFFFF)
        let result = BackgroundRemover.removeConnected(&img, width: w, height: h, background: bg!)
        XCTAssertEqual(a(img[5 * w + 5]), 0)
        XCTAssertEqual(a(img[29 * w + 50]), 0)
        XCTAssertEqual(a(img[50 * w + 50]), 255)
        XCTAssertEqual(a(img[35 * w + 35]), 255)
        XCTAssertEqual(result.box, [30, 30, 40, 40])
    }

    func testNoisyJpegWhiteIsStillBackground() {
        var seed: UInt32 = 7
        var img = (0..<(w * h)).map { _ -> ARGB in
            seed = seed &* 1103515245 &+ 12345
            let v = 255 - Int((seed >> 16) & 7)
            return argb(255, v, v, v)
        }
        rect(&img, 20, 20, 80, 80, redColor)
        let bg = BackgroundRemover.resolveBackground(img, width: w, height: h, hint: nil)!
        BackgroundRemover.removeConnected(&img, width: w, height: h, background: bg)
        XCTAssertEqual(a(img[2 * w + 2]), 0)
        XCTAssertEqual(a(img[50 * w + 50]), 255)
    }

    func testHintIsRefinedOnTheEdge() {
        let img = frame(0xFFF8F8F8) { rect(&$0, 30, 30, 70, 70, redColor) }
        XCTAssertEqual(BackgroundRemover.resolveBackground(img, width: w, height: h, hint: 0xF0F0F0), 0xF8F8F8)
    }

    func testLandscapeBandsAreRemovedAndContentTouchingEdgesKept() {
        var img = frame { rect(&$0, 0, 30, 100, 70, redColor) }
        let bg = BackgroundRemover.resolveBackground(img, width: w, height: h, hint: nil)!
        let box = BackgroundRemover.removeConnected(&img, width: w, height: h, background: bg).box!
        XCTAssertEqual(a(img[10 * w + 50]), 0)
        XCTAssertEqual(a(img[50 * w]), 255)
        XCTAssertEqual(box[1], 30); XCTAssertEqual(box[3], 40); XCTAssertEqual(box[2], 100)
    }

    func testPhotoWithUnevenEdgesKeepsItsPixelsExceptWhiteCorners() {
        var img = (0..<(w * h)).map { p -> ARGB in argb(255, (p % w) * 2, (p / w) * 2, 0x40) }
        for y in 0..<6 { for x in 0..<(6 - y) {
            img[y * w + x] = white; img[y * w + (w - 1 - x)] = white
            img[(h - 1 - y) * w + x] = white; img[(h - 1 - y) * w + (w - 1 - x)] = white
        } }
        let bg = BackgroundRemover.resolveBackground(img, width: w, height: h, hint: nil)
        XCTAssertEqual(bg, 0xFFFFFF)
        let result = BackgroundRemover.removeConnected(&img, width: w, height: h, background: bg!)
        XCTAssertTrue((60...100).contains(result.removed), "removed \(result.removed)")
        XCTAssertEqual(a(img[0]), 0)
        XCTAssertEqual(a(img[50 * w + 50]), 255)
        XCTAssertEqual(a(img[10 * w]), 255)
    }

    func testCornerFillThatSpreadsTooFarIsReverted() {
        var img = (0..<(w * h)).map { p -> ARGB in argb(255, (p % w) * 2, 0x80, 0x40) }
        rect(&img, 0, 0, 40, 40, white)
        let result = BackgroundRemover.removeConnected(&img, width: w, height: h, background: 0xFFFFFF)
        XCTAssertEqual(result.removed, 0)
        XCTAssertEqual(a(img[5 * w + 5]), 255)
    }

    func testBrightSpotTouchingThePhotoEdgeIsNotPunched() {
        var img = frame {
            for y in 20..<80 { for x in 0..<self.w { $0[y * self.w + x] = argb(255, x * 2, y, 0x60) } }
            for d in 0..<5 { for k in 0..<(5 - d) {
                $0[(20 + d) * self.w + k] = self.white; $0[(20 + d) * self.w + (self.w - 1 - k)] = self.white
                $0[(79 - d) * self.w + k] = self.white; $0[(79 - d) * self.w + (self.w - 1 - k)] = self.white
            } }
            rect(&$0, 45, 65, 55, 80, white)
        }
        let bg = BackgroundRemover.resolveBackground(img, width: w, height: h, hint: nil)!
        let result = BackgroundRemover.removeConnected(&img, width: w, height: h, background: bg)
        XCTAssertEqual(a(img[5 * w + 50]), 0)
        XCTAssertEqual(a(img[20 * w]), 0)
        XCTAssertEqual(a(img[70 * w + 50]), 255)
        XCTAssertEqual(result.box?[1], 20); XCTAssertEqual(result.box?[3], 60)
    }

    func testStickerOnWhiteStillLosesTheWhiteInsideItsBox() {
        var img = frame { rect(&$0, 20, 20, 35, 80, redColor); rect(&$0, 20, 65, 80, 80, redColor) }
        let bg = BackgroundRemover.resolveBackground(img, width: w, height: h, hint: nil)!
        BackgroundRemover.removeConnected(&img, width: w, height: h, background: bg)
        XCTAssertEqual(a(img[30 * w + 60]), 0)
        XCTAssertEqual(a(img[70 * w + 70]), 255)
    }

    func testNoBackgroundWhenEdgesAreNotUniformAndCornersAreNotWhite() {
        let img = (0..<(w * h)).map { p -> ARGB in argb(255, (p % w) * 2, (p / w) * 2, 0) }
        XCTAssertNil(BackgroundRemover.resolveBackground(img, width: w, height: h, hint: nil))
    }

    func testFullyBackgroundFrameHasNoBox() {
        var img = frame()
        let result = BackgroundRemover.removeConnected(&img, width: w, height: h, background: 0xFFFFFF)
        XCTAssertNil(result.box)
        XCTAssertEqual(result.removed, w * h)
    }

    func testExpandAndUnionStayInsideTheFrame() {
        var p = BackgroundRemover.Params(); p.margin = 0.05
        let box = BackgroundRemover.expand([2, 2, 10, 10], width: w, height: h, params: p)
        XCTAssertEqual(box[0], 0); XCTAssertEqual(box[2], 17)
        XCTAssertEqual(BackgroundRemover.union([10, 10, 10, 10], [15, 5, 10, 10]), [10, 5, 15, 15])
    }

    func testBackgroundRemovingSourceTrimsToTheContent() throws {
        var r = Raster(width: w, height: h, fill: white)
        for y in 30..<70 { for x in 20..<60 { r[x, y] = redColor } }
        let source = BackgroundRemovingSource(StillSource(r))
        let out = try source.firstFrame()
        // contenuto 40x40 più il margine del 3% (3 px) per lato
        XCTAssertEqual(out.width, 46)
        XCTAssertEqual(out.height, 46)
        XCTAssertEqual(alpha(out[0, 0]), 0)
        XCTAssertEqual(out[23, 23], redColor)
    }
}
