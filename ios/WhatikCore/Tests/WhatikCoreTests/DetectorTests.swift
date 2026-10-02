import XCTest
@testable import WhatikCore

final class StickerDetectorTests: XCTestCase {
    let w = 100, h = 160
    let intervalMs: Int64 = 250

    func background() -> [Int] { (0..<(w * h)).map { 40 + ($0 % w) / 2 } }

    func fill(_ frame: inout [Int], _ left: Int, _ top: Int, _ size: Int, _ value: Int) {
        for y in top..<(top + size) { for x in left..<(left + size) { frame[y * w + x] = value } }
    }

    func sequence(_ count: Int, _ paint: (inout [Int], Int) -> Void) -> ([[Int]], [Int64]) {
        let frames: [[Int]] = (0..<count).map { i in var f = background(); paint(&f, i); return f }
        return (frames, (0..<count).map { Int64($0) * intervalMs })
    }

    func testStaticScreenYieldsNothing() {
        let (frames, times) = sequence(12) { _, _ in }
        XCTAssertTrue(StickerDetector.detect(frames: frames, width: w, height: h, timesMs: times).isEmpty)
    }

    func testFindsOneBlinkingRegionWithSquareCropAroundIt() {
        let (frames, times) = sequence(16) { f, i in fill(&f, 20, 50, 24, i % 2 == 0 ? 200 : 60) }
        let found = StickerDetector.detect(frames: frames, width: w, height: h, timesMs: times)
        XCTAssertEqual(found.count, 1)
        let p = found[0]
        XCTAssertTrue((17...21).contains(p.box[0]), "left \(p.box[0])")
        XCTAssertTrue((47...51).contains(p.box[1]), "top \(p.box[1])")
        XCTAssertTrue((23...30).contains(p.box[2]), "width \(p.box[2])")
        XCTAssertTrue((23...30).contains(p.box[3]), "height \(p.box[3])")
        XCTAssertEqual(p.crop.cx, 0.32, accuracy: 0.03)
        XCTAssertEqual(p.crop.cy, 62 / Float(h), accuracy: 0.03)
        XCTAssertTrue((20...40).contains(p.crop.width * Float(w)))
        XCTAssertEqual(p.startMs, 0)
        XCTAssertEqual(p.endMs - p.startMs, 2 * intervalMs)
    }

    func testFindsTwoRegionsInReadingOrder() {
        let (frames, times) = sequence(16) { f, i in
            fill(&f, 60, 100, 20, i % 2 == 0 ? 220 : 60)
            fill(&f, 10, 20, 20, i % 3 == 0 ? 220 : 60)
        }
        let found = StickerDetector.detect(frames: frames, width: w, height: h, timesMs: times)
        XCTAssertEqual(found.count, 2)
        XCTAssertTrue(found[0].box[1] < found[1].box[1])
        XCTAssertEqual(found[0].endMs - found[0].startMs, 3 * intervalMs)
        XCTAssertEqual(found[1].endMs - found[1].startMs, 2 * intervalMs)
    }

    func testSkipsScrollingPhaseAndStartsAtStableWindow() {
        let (frames, times) = sequence(20) { f, i in
            if i < 6 {
                for p in f.indices { f[p] = (f[p] + i * 37) % 256 }
            } else {
                fill(&f, 30, 30, 20, i % 2 == 0 ? 230 : 120)
            }
        }
        let found = StickerDetector.detect(frames: frames, width: w, height: h, timesMs: times)
        XCTAssertEqual(found.count, 1)
        XCTAssertEqual(found.first?.startMs, 6 * intervalMs)
    }

    func testIgnoresFullScreenVideoPlayback() {
        let (frames, times) = sequence(12) { f, i in
            for p in f.indices where p % 7 != 0 { f[p] = (p * 13 + i * 50) % 256 }
        }
        XCTAssertTrue(StickerDetector.detect(frames: frames, width: w, height: h, timesMs: times).isEmpty)
    }

    func testMergeBoxesJoinsOverlappingAndNearbyRectangles() {
        let merged = StickerDetector.mergeBoxes([[0, 0, 10, 10], [11, 2, 20, 12], [50, 50, 60, 60]], gap: 2)
        XCTAssertEqual(merged.count, 2)
        XCTAssertTrue(merged.contains([0, 0, 20, 12]))
    }

    func testSplitsARowOfTouchingStickers() {
        let (frames, times) = sequence(16) { f, i in
            fill(&f, 10, 60, 20, i % 2 == 0 ? 230 : 20)
            fill(&f, 32, 60, 20, i % 2 == 0 ? 230 : 20)
        }
        let found = StickerDetector.detect(frames: frames, width: w, height: h, timesMs: times)
        XCTAssertEqual(found.count, 2)
        XCTAssertTrue(found[0].box[0] + found[0].box[2] <= 33)
        XCTAssertTrue(found[1].box[0] >= 29)
    }

    func testRingActivityMeasuresMotionAroundTheBox() {
        let mask = (0..<(w * h)).map { p -> Bool in let x = p % w, y = p / w; return !((10..<30).contains(x) && (10..<30).contains(y)) }
        XCTAssertGreaterThan(StickerDetector.ringActivity(mask, width: w, height: h, l: 10, t: 10, r: 30, b: 30), 0.9)
        let still = (0..<(w * h)).map { p -> Bool in let x = p % w, y = p / w; return (10..<30).contains(x) && (10..<30).contains(y) }
        XCTAssertEqual(StickerDetector.ringActivity(still, width: w, height: h, l: 10, t: 10, r: 30, b: 30), 0, accuracy: 1e-6)
    }

    func testLoopStartsAfterTheBlackRestartFrame() {
        let levels = [5, 220, 140, 80]
        let (frames, times) = sequence(24) { f, i in fill(&f, 30, 50, 24, levels[i % 4]) }
        let found = StickerDetector.detect(frames: frames, width: w, height: h, timesMs: times)
        XCTAssertEqual(found.count, 1)
        XCTAssertEqual(found[0].endMs - found[0].startMs, 1000)
        XCTAssertEqual(found[0].startMs, 1 * intervalMs)
    }

    func testSlowContinuousMotionHasNoLoop() {
        let (frames, times) = sequence(24) { f, i in
            for y in 50..<74 { for x in 30..<54 { f[y * w + x] = min(250, 40 + (x - 30) * 5 + i * 25) } }
        }
        let found = StickerDetector.detect(frames: frames, width: w, height: h, timesMs: times)
        XCTAssertEqual(found.count, 1)
        XCTAssertGreaterThanOrEqual(found[0].endMs - found[0].startMs, 3000)
    }
}

final class StaticStickerFinderTests: XCTestCase {
    let w = 400, h = 300
    let bg: ARGB = 0xFF1C1C1C

    func image(_ paint: (inout [ARGB]) -> Void) -> [ARGB] { var img = [ARGB](repeating: bg, count: w * h); paint(&img); return img }
    func fill(_ img: inout [ARGB], _ l: Int, _ t: Int, _ r: Int, _ b: Int, _ color: ARGB) {
        for y in t..<b { for x in l..<r { img[y * w + x] = color } }
    }

    func testFindsStickerBoundsAroundTappedPoint() {
        let img = image { fill(&$0, 120, 80, 220, 200, 0xFFE05080) }
        let r = StaticStickerFinder.find(img, width: w, height: h, px: 170, py: 140)
        XCTAssertTrue(r.found)
        XCTAssertTrue((117...123).contains(r.box[0]))
        XCTAssertTrue((77...83).contains(r.box[1]))
        XCTAssertTrue((95...106).contains(r.box[2]))
        XCTAssertTrue((115...126).contains(r.box[3]))
    }

    func testSnapsToNearbyStickerWhenTappingJustOutside() {
        let img = image { fill(&$0, 120, 80, 220, 200, 0xFF40C0FF) }
        let r = StaticStickerFinder.find(img, width: w, height: h, px: 112, py: 140)
        XCTAssertTrue(r.found)
        XCTAssertTrue((117...123).contains(r.box[0]))
    }

    func testSeparatesNeighbouringStickers() {
        let img = image { fill(&$0, 40, 100, 140, 200, 0xFFFF9900); fill(&$0, 170, 100, 270, 200, 0xFF00AA55) }
        let r = StaticStickerFinder.find(img, width: w, height: h, px: 220, py: 150)
        XCTAssertTrue(r.found)
        XCTAssertTrue((167...173).contains(r.box[0]))
        XCTAssertTrue((95...106).contains(r.box[2]))
    }

    func testFallsBackToSquareOnEmptyBackground() {
        let r = StaticStickerFinder.find(image { _ in }, width: w, height: h, px: 200, py: 150)
        XCTAssertFalse(r.found)
        XCTAssertEqual(r.box, [140, 90, 120, 120])
    }

    func testToCropMakesASquareWithMargin() {
        let crop = StaticStickerFinder.toCrop([100, 50, 60, 100], width: 400, height: 300, margin: 0.1)
        XCTAssertEqual(crop.cx, 130 / 400, accuracy: 1e-4)
        XCTAssertEqual(crop.cy, 100 / 300, accuracy: 1e-4)
        XCTAssertEqual(crop.width * 400, 110, accuracy: 1e-3)
        XCTAssertEqual(crop.height * 300, 110, accuracy: 1e-3)
    }
}

final class StickerRefinerTests: XCTestCase {
    let aw = 160, ah = 120, fw = 480, fh = 360
    let bg: ARGB = 0xFF202020

    func frame(_ paint: (inout [ARGB]) -> Void) -> [ARGB] { var img = [ARGB](repeating: bg, count: fw * fh); paint(&img); return img }
    func fill(_ img: inout [ARGB], _ l: Int, _ t: Int, _ r: Int, _ b: Int, _ color: ARGB) {
        for y in t..<b { for x in l..<r { img[y * fw + x] = color } }
    }

    func testExpandsMotionBoxToTheWholeStillTile() {
        let rgb = frame { fill(&$0, 150, 90, 270, 210, 0xFFE0A040) }
        let crop = StickerRefiner.refine(box: [60, 40, 20, 20], aw: aw, ah: ah, rgb: rgb, fw: fw, fh: fh)
        XCTAssertNotNil(crop)
        guard let c = crop else { return }
        let p = c.toPixels(width: fw, height: fh)
        XCTAssertEqual(c.cx, 210 / Float(fw), accuracy: 0.02)
        XCTAssertEqual(c.cy, 150 / Float(fh), accuracy: 0.02)
        XCTAssertTrue((121...133).contains(p.width), "side \(p.width)")
        XCTAssertTrue(p.left <= 150 && p.left + p.width >= 270 && p.top <= 90 && p.top + p.height >= 210)
    }

    func testRejectsRegionsTouchingTheFrameBorder() {
        let rgb = frame { _ in }
        XCTAssertNil(StickerRefiner.refine(box: [0, 40, 30, 30], aw: aw, ah: ah, rgb: rgb, fw: fw, fh: fh))
        XCTAssertNil(StickerRefiner.refine(box: [130, 40, 30, 30], aw: aw, ah: ah, rgb: rgb, fw: fw, fh: fh))
    }

    func testRejectsStripsAndHugeAreas() {
        let rgb = frame { _ in }
        XCTAssertNil(StickerRefiner.refine(box: [20, 50, 120, 20], aw: aw, ah: ah, rgb: rgb, fw: fw, fh: fh))
        XCTAssertNil(StickerRefiner.refine(box: [10, 10, 140, 100], aw: aw, ah: ah, rgb: rgb, fw: fw, fh: fh))
    }

    func testKeepsMotionBoxWhenNoStillStickerIsFound() {
        let crop = StickerRefiner.refine(box: [60, 40, 20, 20], aw: aw, ah: ah, rgb: frame { _ in }, fw: fw, fh: fh)
        XCTAssertNotNil(crop)
        XCTAssertEqual(crop?.cx ?? 0, 210 / Float(fw), accuracy: 0.02)
        XCTAssertEqual(crop?.cy ?? 0, 150 / Float(fh), accuracy: 0.02)
    }
}

final class CropSpecTests: XCTestCase {
    func px(_ c: CropSpec, _ w: Int, _ h: Int) -> [Int] { let p = c.toPixels(width: w, height: h); return [p.left, p.top, p.width, p.height] }

    func testFullCropCoversTheWholeImage() { XCTAssertEqual(px(.full, 1080, 1920), [0, 0, 1080, 1920]) }

    func testSquareIsCenteredAndClampedInsideTheFrame() {
        XCTAssertEqual(px(.square(cx: 0.5, cy: 0.5, sizeFraction: 0.5, width: 1000, height: 2000), 1000, 2000), [250, 750, 500, 500])
        XCTAssertEqual(px(.square(cx: 0, cy: 0, sizeFraction: 0.5, width: 1000, height: 2000), 1000, 2000), [0, 0, 500, 500])
        XCTAssertEqual(px(.square(cx: 1, cy: 1, sizeFraction: 0.5, width: 1000, height: 2000), 1000, 2000), [500, 1500, 500, 500])
    }

    func testFreeRectangleKeepsItsAspect() {
        XCTAssertEqual(px(CropSpec(left: 0.1, top: 0.2, right: 0.6, bottom: 0.4), 1000, 2000), [100, 400, 500, 400])
    }

    func testNormalizedEnforcesMinimumSizeAndBounds() {
        let n = CropSpec(left: 0.5, top: 0.5, right: 0.5, bottom: 0.5).normalized()
        XCTAssertEqual(n.width, CropSpec.minSize, accuracy: 1e-6)
        XCTAssertEqual(n.height, CropSpec.minSize, accuracy: 1e-6)
        let out = CropSpec(left: -0.5, top: 0.9, right: 0.2, bottom: 1.5).normalized()
        XCTAssertTrue(out.left >= 0 && out.top >= 0 && out.right <= 1 && out.bottom <= 1)
        XCTAssertEqual(out.width, 0.7, accuracy: 1e-6)
        XCTAssertEqual(out.height, 0.6, accuracy: 1e-6)
    }

    func testEffectiveIsStable() {
        let spec = CropSpec.square(cx: 0.02, cy: 0.5, sizeFraction: 0.5, width: 1000, height: 2000).effective(width: 1000, height: 2000)
        XCTAssertEqual(spec, spec.effective(width: 1000, height: 2000))
        XCTAssertEqual(spec.cx, 0.25, accuracy: 1e-5)
    }
}
