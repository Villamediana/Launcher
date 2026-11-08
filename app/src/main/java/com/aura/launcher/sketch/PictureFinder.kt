package com.aura.launcher.sketch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A picture from the web for the board: Bing's image results, the first one
 * that downloads and is big enough, saved small next to the boards. The same
 * search Luna uses for "send me a picture of…".
 */
class PictureFinder(private val dir: File) {

    /** The saved file's name inside [dir], and its width over height; null when nothing came. */
    suspend fun find(query: String): Pair<String, Float>? = withContext(Dispatchers.IO) {
        val candidates = runCatching { bing(query) }.getOrDefault(emptyList())
        for (url in candidates.take(8)) {
            val saved = runCatching { fetch(url) }.getOrNull()
            if (saved != null) return@withContext saved
        }
        null
    }

    private fun bing(query: String): List<String> {
        val url = "https://www.bing.com/images/search?q=" + URLEncoder.encode(query, "UTF-8") + "&form=HDRSC2&first=1&safeSearch=Moderate"
        val html = client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        return Regex("""m="(\{[^"]*\})"""").findAll(html).mapNotNull { m ->
            val json = m.groupValues[1].replace("&quot;", "\"").replace("&amp;", "&")
            Regex(""""murl":"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
        }.filter { it.startsWith("https://") }.distinct().toList()
    }

    private fun fetch(url: String): Pair<String, Float>? {
        val bytes = client.newCall(
            Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android)").header("Accept", "image/*").build()
        ).execute().use { r ->
            if (!r.isSuccessful || r.header("Content-Type")?.startsWith("image/") != true) return null
            r.body?.bytes()
        } ?: return null
        if (bytes.size < 4_000 || bytes.size > 15_000_000) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 200 || bounds.outHeight < 150) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        dir.mkdirs()
        val name = "${UUID.randomUUID()}.jpg"
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val ratio = bitmap.width.toFloat() / bitmap.height
        bitmap.recycle()
        return name to ratio
    }

    private companion object {
        const val MAX_SIDE = 1024
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
