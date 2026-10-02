import SwiftUI
import WhatikCore

/// Riquadro di ritaglio come in un editor di foto: si trascinano bordi e angoli per
/// ridimensionarlo e l'interno per spostarlo. Il riquadro è libero (anche rettangolare).
struct CropEditor: View {
    let image: UIImage
    @Binding var crop: CropSpec
    @State private var zone: Zone?
    @State private var startCrop = CropSpec.full

    enum Zone { case move, left, right, top, bottom, topLeft, topRight, bottomLeft, bottomRight }

    struct Handle: Identifiable {
        let id: Int
        let point: CGPoint
        let size: CGSize
    }

    var body: some View {
        GeometryReader { geo in
            let frame = CropEditor.fitRect(image.size, in: geo.size)
            let rect = CropEditor.rect(crop, in: frame)
            ZStack(alignment: .topLeading) {
                Image(uiImage: image)
                    .resizable()
                    .frame(width: frame.width, height: frame.height)
                    .offset(x: frame.minX, y: frame.minY)
                Path { path in
                    path.addRect(frame)
                    path.addRect(rect)
                }
                .fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))
                Path { path in
                    for i in 1...2 {
                        let x = rect.minX + rect.width * CGFloat(i) / 3
                        path.move(to: CGPoint(x: x, y: rect.minY))
                        path.addLine(to: CGPoint(x: x, y: rect.maxY))
                        let y = rect.minY + rect.height * CGFloat(i) / 3
                        path.move(to: CGPoint(x: rect.minX, y: y))
                        path.addLine(to: CGPoint(x: rect.maxX, y: y))
                    }
                }
                .stroke(Color.white.opacity(0.35), lineWidth: 1)
                Path { path in path.addRect(rect) }
                    .stroke(Color.white, lineWidth: 2)
                ForEach(CropEditor.handles(rect)) { handle in
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Color.white)
                        .frame(width: handle.size.width, height: handle.size.height)
                        .shadow(color: .black.opacity(0.4), radius: 1)
                        .position(handle.point)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 1)
                    .onChanged { value in
                        if zone == nil {
                            zone = CropEditor.zone(at: value.startLocation, rect: rect)
                            startCrop = crop
                        }
                        guard let current = zone, frame.width > 0, frame.height > 0 else { return }
                        let dx = Float(value.translation.width / frame.width)
                        let dy = Float(value.translation.height / frame.height)
                        crop = CropEditor.adjust(startCrop, zone: current, dx: dx, dy: dy)
                    }
                    .onEnded { _ in zone = nil }
            )
        }
    }

    static func fitRect(_ size: CGSize, in container: CGSize) -> CGRect {
        guard size.width > 0, size.height > 0, container.width > 0, container.height > 0 else { return .zero }
        let scale = min(container.width / size.width, container.height / size.height)
        let w = size.width * scale
        let h = size.height * scale
        return CGRect(x: (container.width - w) / 2, y: (container.height - h) / 2, width: w, height: h)
    }

    static func rect(_ c: CropSpec, in f: CGRect) -> CGRect {
        CGRect(x: f.minX + CGFloat(c.left) * f.width, y: f.minY + CGFloat(c.top) * f.height,
               width: CGFloat(c.width) * f.width, height: CGFloat(c.height) * f.height)
    }

    static func handles(_ r: CGRect) -> [Handle] {
        let corner = CGSize(width: 18, height: 18)
        let horizontal = CGSize(width: 30, height: 8)
        let vertical = CGSize(width: 8, height: 30)
        return [
            Handle(id: 0, point: CGPoint(x: r.minX, y: r.minY), size: corner),
            Handle(id: 1, point: CGPoint(x: r.maxX, y: r.minY), size: corner),
            Handle(id: 2, point: CGPoint(x: r.minX, y: r.maxY), size: corner),
            Handle(id: 3, point: CGPoint(x: r.maxX, y: r.maxY), size: corner),
            Handle(id: 4, point: CGPoint(x: r.midX, y: r.minY), size: horizontal),
            Handle(id: 5, point: CGPoint(x: r.midX, y: r.maxY), size: horizontal),
            Handle(id: 6, point: CGPoint(x: r.minX, y: r.midY), size: vertical),
            Handle(id: 7, point: CGPoint(x: r.maxX, y: r.midY), size: vertical),
        ]
    }

    static func zone(at p: CGPoint, rect r: CGRect) -> Zone? {
        let reach: CGFloat = 32
        let nearLeft = abs(p.x - r.minX) < reach
        let nearRight = abs(p.x - r.maxX) < reach
        let nearTop = abs(p.y - r.minY) < reach
        let nearBottom = abs(p.y - r.maxY) < reach
        let withinX = p.x > r.minX - reach && p.x < r.maxX + reach
        let withinY = p.y > r.minY - reach && p.y < r.maxY + reach
        if nearTop && nearLeft { return .topLeft }
        if nearTop && nearRight { return .topRight }
        if nearBottom && nearLeft { return .bottomLeft }
        if nearBottom && nearRight { return .bottomRight }
        if nearLeft && withinY { return .left }
        if nearRight && withinY { return .right }
        if nearTop && withinX { return .top }
        if nearBottom && withinX { return .bottom }
        if r.contains(p) { return .move }
        return nil
    }

    static func adjust(_ c: CropSpec, zone: Zone, dx: Float, dy: Float) -> CropSpec {
        let minSide = CropSpec.minSize * 2
        var l = c.left, t = c.top, r = c.right, b = c.bottom
        switch zone {
        case .move:
            l = (c.left + dx).clamped(0, 1 - c.width)
            t = (c.top + dy).clamped(0, 1 - c.height)
            r = l + c.width
            b = t + c.height
        case .left:
            l = (c.left + dx).clamped(0, r - minSide)
        case .right:
            r = (c.right + dx).clamped(l + minSide, 1)
        case .top:
            t = (c.top + dy).clamped(0, b - minSide)
        case .bottom:
            b = (c.bottom + dy).clamped(t + minSide, 1)
        case .topLeft:
            l = (c.left + dx).clamped(0, r - minSide)
            t = (c.top + dy).clamped(0, b - minSide)
        case .topRight:
            r = (c.right + dx).clamped(l + minSide, 1)
            t = (c.top + dy).clamped(0, b - minSide)
        case .bottomLeft:
            l = (c.left + dx).clamped(0, r - minSide)
            b = (c.bottom + dy).clamped(t + minSide, 1)
        case .bottomRight:
            r = (c.right + dx).clamped(l + minSide, 1)
            b = (c.bottom + dy).clamped(t + minSide, 1)
        }
        return CropSpec(left: l, top: t, right: r, bottom: b)
    }
}
