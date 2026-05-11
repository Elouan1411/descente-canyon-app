package fr.descentecanyon.app.data.network

import fr.descentecanyon.app.BuildConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Singleton
open class DescenteCanyonWebClient @Inject constructor() {

    open fun getDocument(
        url: String,
        cookies: Map<String, String> = emptyMap(),
        timeoutMs: Int = TIMEOUT_MS,
    ): Document {
        return getDocumentResponse(url, cookies, timeoutMs).document
    }

    open fun getDocumentResponse(
        url: String,
        cookies: Map<String, String> = emptyMap(),
        timeoutMs: Int = TIMEOUT_MS,
    ): WebDocumentResponse {
        val response = buildConnection(url, cookies, timeoutMs)
            .followRedirects(true)
            .execute()

        return WebDocumentResponse(
            document = response.parse(),
            cookies = response.cookies(),
            finalUrl = response.url().toString(),
        )
    }

    open fun postDocument(
        url: String,
        data: Map<String, String>,
        cookies: Map<String, String> = emptyMap(),
        referer: String? = null,
        origin: String? = null,
    ): WebDocumentResponse {
        val response = buildConnection(url, cookies, TIMEOUT_MS)
            .apply {
                if (referer != null) referrer(referer)
                if (origin != null) header("Origin", origin)
            }
            .data(data)
            .method(Connection.Method.POST)
            .followRedirects(true)
            .execute()

        return WebDocumentResponse(
            document = response.parse(),
            cookies = response.cookies(),
            finalUrl = response.url().toString(),
        )
    }

    open fun downloadToFile(
        url: String,
        targetFile: File,
        cookies: Map<String, String> = emptyMap(),
    ) {
        targetFile.parentFile?.mkdirs()
        buildConnection(url, cookies, TIMEOUT_MS)
            .ignoreContentType(true)
            .execute()
            .bodyStream()
            .use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
    }

    private fun buildConnection(
        url: String,
        cookies: Map<String, String>,
        timeoutMs: Int,
    ): Connection {
        return Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(timeoutMs)
            .apply {
                if (cookies.isNotEmpty()) {
                    cookies(cookies)
                }
            }
    }

    companion object {
        private val USER_AGENT = "DescenteCanyonApp/${BuildConfig.VERSION_NAME} (Android)"
        private const val TIMEOUT_MS = 30_000
    }
}

data class WebDocumentResponse(
    val document: Document,
    val cookies: Map<String, String>,
    val finalUrl: String,
)
