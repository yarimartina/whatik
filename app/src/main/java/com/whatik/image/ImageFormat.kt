package com.whatik.image

enum class ImageKind(val mimeType: String, val extension: String) {
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    UNKNOWN("application/octet-stream", "bin"),
}

/** Riconoscimento del formato dai "magic bytes", indipendente dall'estensione dichiarata. */
object ImageFormat {
    fun sniff(bytes: ByteArray): ImageKind = when {
        GifDecoder.isGif(bytes) -> ImageKind.GIF
        WebPContainer.isWebP(bytes) -> ImageKind.WEBP
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> ImageKind.PNG
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> ImageKind.JPEG
        else -> ImageKind.UNKNOWN
    }

    /** true se il file contiene più di un fotogramma. */
    fun isAnimated(bytes: ByteArray, kind: ImageKind = sniff(bytes)): Boolean = when (kind) {
        ImageKind.GIF -> runCatching { GifDecoder.readInfo(bytes).frameCount > 1 }.getOrDefault(false)
        ImageKind.WEBP -> runCatching { WebPContainer.isAnimated(bytes) && WebPContainer.parse(bytes).frames.size > 1 }.getOrDefault(false)
        else -> false
    }
}
