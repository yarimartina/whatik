import Foundation

/// Codificatore PNG minimo (RGBA 8 bit, blocchi deflate non compressi). Serve per l'icona del
/// pack: 96x96 RGBA occupa ~37 KB, sotto il limite di 50 KB di WhatsApp, senza dipendere da zlib.
public enum PNGEncoder {
    public static func encode(_ raster: Raster) -> Data {
        let w = raster.width, h = raster.height
        var raw = [UInt8]()
        raw.reserveCapacity((w * 4 + 1) * h)
        let rgba = raster.rgbaBytes
        for y in 0..<h {
            raw.append(0) // filtro "None"
            raw.append(contentsOf: rgba[(y * w * 4)..<((y + 1) * w * 4)])
        }
        var out = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        var ihdr = Data()
        ihdr.appendBE32(UInt32(w)); ihdr.appendBE32(UInt32(h))
        ihdr.append(contentsOf: [8, 6, 0, 0, 0]) // 8 bit, RGBA, deflate, filtro adattivo, no interlace
        out.appendChunk("IHDR", ihdr)
        out.appendChunk("IDAT", zlibStored(raw))
        out.appendChunk("IEND", Data())
        return out
    }

    /// Flusso zlib con soli blocchi "stored" (RFC 1950/1951).
    static func zlibStored(_ bytes: [UInt8]) -> Data {
        var out = Data([0x78, 0x01])
        var offset = 0
        repeat {
            let len = min(65535, bytes.count - offset)
            let final: UInt8 = offset + len >= bytes.count ? 1 : 0
            out.append(final)
            out.append(UInt8(len & 0xFF)); out.append(UInt8(len >> 8))
            let nlen = ~UInt16(len)
            out.append(UInt8(nlen & 0xFF)); out.append(UInt8(nlen >> 8))
            out.append(contentsOf: bytes[offset..<(offset + len)])
            offset += len
        } while offset < bytes.count
        out.appendBE32(adler32(bytes))
        return out
    }

    static func adler32(_ bytes: [UInt8]) -> UInt32 {
        var a: UInt32 = 1, b: UInt32 = 0
        for byte in bytes { a = (a + UInt32(byte)) % 65521; b = (b + a) % 65521 }
        return (b << 16) | a
    }

    private static let crcTable: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for byte in data { c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFFFFFF
    }
}

extension Data {
    mutating func appendBE32(_ v: UInt32) {
        append(UInt8(v >> 24)); append(UInt8((v >> 16) & 0xFF)); append(UInt8((v >> 8) & 0xFF)); append(UInt8(v & 0xFF))
    }

    mutating func appendChunk(_ type: String, _ payload: Data) {
        appendBE32(UInt32(payload.count))
        var body = Data(type.utf8)
        body.append(payload)
        append(body)
        appendBE32(PNGEncoder.crc32(body))
    }
}
