package com.whatik

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.whatik.data.PackStore
import com.whatik.data.StickerExporter
import com.whatik.data.StickerLibrary

class WhatikApp : Application(), ImageLoaderFactory {

    val library: StickerLibrary by lazy { StickerLibrary(this) }
    val packStore: PackStore by lazy { PackStore(this) }
    val exporter: StickerExporter by lazy { StickerExporter(library, packStore) }

    /** ImageLoader di Coil con supporto alle anteprime animate (GIF e WebP animati). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
