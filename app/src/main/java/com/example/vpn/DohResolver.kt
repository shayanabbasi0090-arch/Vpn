package com.example.vpn

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Resolves raw DNS wire-format queries over encrypted HTTPS (DNS-over-HTTPS, RFC 8484)
 * so ISP DNS hijacking and DNS-based site blocking are bypassed.
 */
class DohResolver(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    fun resolveWireQuery(dnsQueryBytes: ByteArray, primaryDnsIp: String): ByteArray? {
        val dohUrl = when (primaryDnsIp) {
            "9.9.9.9" -> "https://dns.quad9.net/dns-query"
            "8.8.8.8" -> "https://dns.google/dns-query"
            else -> "https://1.1.1.1/dns-query"
        }

        val request = Request.Builder()
            .url(dohUrl)
            .addHeader("Accept", "application/dns-message")
            .addHeader("Content-Type", "application/dns-message")
            .post(dnsQueryBytes.toRequestBody("application/dns-message".toMediaType()))
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()?.takeIf { it.size >= 12 }
            }
        }.getOrNull()
    }
}
