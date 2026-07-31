package dev.tplay.data.youtube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.IOException

class OkHttpDownloader(
    private val client: okhttp3.OkHttpClient,
) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")

        if (request.httpMethod() == "POST") {
            val data = request.dataToSend() ?: ByteArray(0)
            val contentType = request.headers()
                ?.entries
                ?.firstOrNull { it.key.equals("Content-Type", true) }
                ?.value
                ?.firstOrNull()
                ?: "application/json"
            builder.post(data.toRequestBody(contentType.toMediaType()))
        } else {
            builder.get()
        }

        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val headers = mutableMapOf<String, List<String>>()
            resp.headers.forEach { (k, v) ->
                headers[k] = (headers[k] ?: emptyList()) + v
            }
            return Response(
                resp.code,
                resp.message,
                headers,
                body,
                resp.request.url.toString(),
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
