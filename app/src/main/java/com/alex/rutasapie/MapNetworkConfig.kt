package com.alex.rutasapie

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File

/**
 * Identificación estable y caché HTTP para las teselas públicas OSM.
 * Solo añade nuestra identificación a las peticiones enviadas a tile.openstreetmap.org.
 * No descarga áreas ni cambia las políticas Cache-Control / ETag del servidor.
 */
object MapNetworkConfig {
    private const val OSM_HOST = "tile.openstreetmap.org"
    private const val CACHE_BYTES = 100L * 1024L * 1024L

    fun install(context: Context) {
        val app = context.applicationContext
        val agent = "RutasAPie/${BuildConfig.VERSION_NAME} (+https://pastranaalex-glitch.github.io/)"
        val client = OkHttpClient.Builder()
            .cache(Cache(File(app.cacheDir, "mapas_http_cache"), CACHE_BYTES))
            .addInterceptor { chain ->
                val request = chain.request()
                val identified = if (request.url.host.equals(OSM_HOST, ignoreCase = true)) {
                    request.newBuilder().header("User-Agent", agent).build()
                } else request
                chain.proceed(identified)
            }
            .build()
        // Es un método estático Java en MapLibre 13.6.1; Kotlin exige la clase.
        HttpRequestUtil.setOkHttpClient(client)
    }
}
