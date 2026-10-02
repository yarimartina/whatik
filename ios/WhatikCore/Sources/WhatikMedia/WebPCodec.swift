import Foundation
import WhatikCore
import libwebp

/// Codifica e decodifica WebP con libwebp (la stessa libreria usata da Android e da WhatsApp).
public enum WebPCodec {

    public struct Features: Sendable {
        public var width: Int
        public var height: Int
        public var hasAlpha: Bool
        public var animated: Bool
        public var frameCount: Int
    }

    /// Dimensioni, alfa, animazione e numero di fotogrammi.
    public static func features(_ data: Data) -> Features? {
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Features? in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return nil }
            var f = WebPBitstreamFeatures()
            guard WebPGetFeatures(base, raw.count, &f) == VP8_STATUS_OK else { return nil }
            var frames = 1
            if f.has_animation != 0 {
                var wd = WebPData(bytes: base, size: raw.count)
                if let demux = WebPDemux(&wd) {
                    frames = Int(WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT))
                    WebPDemuxDelete(demux)
                }
            }
            return Features(width: Int(f.width), height: Int(f.height), hasAlpha: f.has_alpha != 0, animated: f.has_animation != 0 && frames > 1, frameCount: frames)
        }
    }

    /// Decodifica un WebP statico (o il primo fotogramma di uno animato).
    public static func decodeFirstFrame(_ data: Data) throws -> Raster {
        if let f = features(data), f.animated {
            return try WebPFrameSource(data).firstFrame()
        }
        return try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Raster in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { throw WhatikError.unsupportedImage }
            var w: Int32 = 0, h: Int32 = 0
            guard let out = WebPDecodeRGBA(base, raw.count, &w, &h) else { throw WhatikError.unsupportedImage }
            defer { WebPFree(out) }
            return Raster(width: Int(w), height: Int(h), rgba: UnsafeBufferPointer(start: out, count: Int(w * h * 4)))
        }
    }

    /// WebP statico: lossless (`quality` ignorata) o lossy con la qualità indicata (0..100).
    public static func encode(_ raster: Raster, quality: Int, lossless: Bool) throws -> Data {
        var rgba = raster.rgbaBytes
        var out: UnsafeMutablePointer<UInt8>? = nil
        let size: Int = rgba.withUnsafeMutableBufferPointer { buf in
            if lossless {
                return WebPEncodeLosslessRGBA(buf.baseAddress, Int32(raster.width), Int32(raster.height), Int32(raster.width * 4), &out)
            }
            return WebPEncodeRGBA(buf.baseAddress, Int32(raster.width), Int32(raster.height), Int32(raster.width * 4), Float(quality.clamped(0, 100)), &out)
        }
        guard size > 0, let bytes = out else { throw WhatikError.conversion("Codifica WebP fallita") }
        defer { WebPFree(bytes) }
        return Data(bytes: bytes, count: size)
    }

    /// Animazione con l'encoder di libwebp (fotogrammi parziali, fotogrammi identici fusi).
    /// - Parameter frames: fotogrammi tutti della stessa dimensione, con la loro durata in ms.
    public static func encodeAnimation(width: Int, height: Int, quality: Int, frames: (( (Raster, Int) throws -> Void) throws -> Void)) throws -> Data {
        var options = WebPAnimEncoderOptions()
        guard WebPAnimEncoderOptionsInit(&options) != 0 else { throw WhatikError.conversion("libwebp non inizializzata") }
        options.anim_params.loop_count = 0
        options.anim_params.bgcolor = 0
        options.allow_mixed = 0
        guard let encoder = WebPAnimEncoderNew(Int32(width), Int32(height), &options) else { throw WhatikError.conversion("Encoder WebP non disponibile") }
        defer { WebPAnimEncoderDelete(encoder) }
        var config = WebPConfig()
        guard WebPConfigInit(&config) != 0 else { throw WhatikError.conversion("libwebp non inizializzata") }
        config.lossless = 0
        config.quality = Float(quality.clamped(0, 100))
        config.method = 4
        config.alpha_quality = 90
        var timestamp: Int32 = 0
        var count = 0
        try frames { raster, durationMs in
            guard raster.width == width && raster.height == height else { throw WhatikError.conversion("Fotogramma di dimensione diversa") }
            var picture = WebPPicture()
            guard WebPPictureInit(&picture) != 0 else { throw WhatikError.conversion("libwebp non inizializzata") }
            picture.width = Int32(width)
            picture.height = Int32(height)
            picture.use_argb = 1
            var rgba = raster.rgbaBytes
            let imported = rgba.withUnsafeMutableBufferPointer { WebPPictureImportRGBA(&picture, $0.baseAddress, Int32(width * 4)) }
            defer { WebPPictureFree(&picture) }
            guard imported != 0 else { throw WhatikError.conversion("Fotogramma non importabile") }
            guard WebPAnimEncoderAdd(encoder, &picture, timestamp, &config) != 0 else { throw WhatikError.conversion("Codifica del fotogramma fallita") }
            timestamp += Int32(max(AnimationTiming.minFrameMs, durationMs))
            count += 1
        }
        guard count > 0, WebPAnimEncoderAdd(encoder, nil, timestamp, nil) != 0 else { throw WhatikError.conversion("Animazione vuota") }
        var webp = WebPData()
        WebPDataInit(&webp)
        defer { WebPDataClear(&webp) }
        guard WebPAnimEncoderAssemble(encoder, &webp) != 0, let bytes = webp.bytes else { throw WhatikError.conversion("Assemblaggio WebP fallito") }
        return Data(bytes: bytes, count: webp.size)
    }

    /// Animazione composta da WebP statici già codificati (a pieno canvas), senza ricodificarli:
    /// serve a rendere animato uno sticker fermo (due fotogrammi identici).
    public static func muxStills(_ stills: [(Data, Int)], width: Int, height: Int) throws -> Data {
        guard let mux = WebPMuxNew() else { throw WhatikError.conversion("Mux WebP non disponibile") }
        defer { WebPMuxDelete(mux) }
        for (still, duration) in stills {
            let status: WebPMuxError = still.withUnsafeBytes { raw in
                var info = WebPMuxFrameInfo()
                info.bitstream = WebPData(bytes: raw.bindMemory(to: UInt8.self).baseAddress, size: raw.count)
                info.x_offset = 0
                info.y_offset = 0
                info.duration = Int32(max(AnimationTiming.minFrameMs, duration))
                info.id = WEBP_CHUNK_ANMF
                info.dispose_method = WEBP_MUX_DISPOSE_NONE
                info.blend_method = WEBP_MUX_NO_BLEND
                return WebPMuxPushFrame(mux, &info, 1)
            }
            guard status == WEBP_MUX_OK else { throw WhatikError.conversion("Fotogramma non accettato dal mux (\(status.rawValue))") }
        }
        var params = WebPMuxAnimParams(bgcolor: 0, loop_count: 0)
        guard WebPMuxSetAnimationParams(mux, &params) == WEBP_MUX_OK else { throw WhatikError.conversion("Parametri di animazione non validi") }
        guard WebPMuxSetCanvasSize(mux, Int32(width), Int32(height)) == WEBP_MUX_OK else { throw WhatikError.conversion("Dimensione del canvas non valida") }
        var out = WebPData()
        WebPDataInit(&out)
        defer { WebPDataClear(&out) }
        guard WebPMuxAssemble(mux, &out) == WEBP_MUX_OK, let bytes = out.bytes else { throw WhatikError.conversion("Assemblaggio WebP fallito") }
        return Data(bytes: bytes, count: out.size)
    }
}

/// Fotogrammi composti di un WebP (animato o no), decodificati con WebPAnimDecoder.
public final class WebPFrameSource: FrameSource {
    private let data: Data
    public let width: Int
    public let height: Int
    public let durationsMs: [Int]

    public init(_ data: Data) throws {
        self.data = data
        guard let f = WebPCodec.features(data) else { throw WhatikError.unsupportedImage }
        width = f.width
        height = f.height
        if !f.animated {
            durationsMs = [0]
            return
        }
        // durate dai timestamp di fine fotogramma
        var durations: [Int] = []
        try WebPFrameSource.decode(data) { _, timestamp, previous in
            durations.append(max(0, timestamp - previous))
            return true
        }
        durationsMs = durations.isEmpty ? [0] : durations
    }

    public func produce(_ consume: (Int, Raster) throws -> Bool) throws {
        if durationsMs.count == 1 {
            _ = try consume(0, try WebPCodec.decodeFirstFrame(data))
            return
        }
        var index = 0
        try WebPFrameSource.decode(data) { raster, _, _ in
            defer { index += 1 }
            return try consume(index, raster)
        }
    }

    /// Decodifica sequenziale: (fotogramma, timestamp di fine, timestamp precedente).
    private static func decode(_ data: Data, _ each: (Raster, Int, Int) throws -> Bool) throws {
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var wd = WebPData(bytes: raw.bindMemory(to: UInt8.self).baseAddress, size: raw.count)
            var options = WebPAnimDecoderOptions()
            guard WebPAnimDecoderOptionsInit(&options) != 0 else { throw WhatikError.unsupportedImage }
            options.color_mode = MODE_RGBA
            options.use_threads = 0
            guard let decoder = WebPAnimDecoderNew(&wd, &options) else { throw WhatikError.unsupportedImage }
            defer { WebPAnimDecoderDelete(decoder) }
            var info = WebPAnimInfo()
            guard WebPAnimDecoderGetInfo(decoder, &info) != 0 else { throw WhatikError.unsupportedImage }
            let w = Int(info.canvas_width), h = Int(info.canvas_height)
            var previous = 0
            while WebPAnimDecoderHasMoreFrames(decoder) != 0 {
                var buffer: UnsafeMutablePointer<UInt8>? = nil
                var timestamp: Int32 = 0
                guard WebPAnimDecoderGetNext(decoder, &buffer, &timestamp) != 0, let frame = buffer else { throw WhatikError.unsupportedImage }
                let raster = Raster(width: w, height: h, rgba: UnsafeBufferPointer(start: frame, count: w * h * 4))
                let keepGoing = try each(raster, Int(timestamp), previous)
                previous = Int(timestamp)
                if !keepGoing { break }
            }
        }
    }
}
