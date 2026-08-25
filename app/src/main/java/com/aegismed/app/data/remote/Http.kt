package com.aegismed.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {

    data class Response(val code: Int, val body: String)

    suspend fun get(url: String, timeoutMs: Int = 12_000): Response = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "AegisMed/1.1 (personal health record)")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Response(code, body)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
