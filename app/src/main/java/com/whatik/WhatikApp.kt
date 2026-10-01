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
import okhttp3.OkHttpClient

class WhatikApp : Application(), ImageLoaderFactory {

    val library: StickerLibrary by lazy { StickerLibrary(this) }
    val packStore: PackStore by lazy { PackStore(this) }
    val exporter: StickerExporter by lazy { StickerExporter(library, packStore) }

    /** ImageLoader di Coil con supporto alle anteprime animate (GIF e WebP animati). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", BROWSER_USER_AGENT).build())
                }
                .build()
        }
        .crossfade(true)
        .build()

    companion object {
        /** Alcuni CDN rifiutano i client non-browser: usiamo lo stesso UA dell'importazione da link. */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
