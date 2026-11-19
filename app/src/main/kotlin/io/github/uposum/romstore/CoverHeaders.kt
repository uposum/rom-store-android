package io.github.uposum.romstore

import okhttp3.Interceptor
import okhttp3.Response

/** Cover requests carry the site as Referer and a desktop browser's Accept,
 * like the plugin's image cache did (some image hosts refuse bare requests). */
object CoverHeaders : Interceptor {
    private fun referer(url: String): String {
        val host = Regex("https?://([^/]+)/").find(url)?.groupValues?.get(1) ?: return ""
        return when {
            "myabandonware" in host -> "https://www.myabandonware.com/"
            "romspedia" in host -> "https://www.romspedia.com/"
            else -> "https://$host/"
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        if (request.header("Referer") == null) referer(request.url.toString()).takeIf { it.isNotEmpty() }?.let { builder.header("Referer", it) }
        builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        return chain.proceed(builder.build())
    }
}
