package com.example.vpn

import android.os.SystemClock
import com.example.domain.model.ServerProtocol
import com.example.domain.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class TunnelDiagnosticResult(
    val exitIp: String,
    val exitCountry: String,
    val downloadMbps: Double,
    val latencyMs: Int,
    val errorMessage: String? = null
)

class TunnelDiagnosticsTester(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    suspend fun runDiagnostics(activeServer: VpnServer): TunnelDiagnosticResult =
        withContext(Dispatchers.IO) {
            if (activeServer.protocol == ServerProtocol.SOCKS5_TUN) {
                testViaSocks5(activeServer.host, activeServer.port)
            } else {
                testDirectHttp()
            }
        }

    private fun testViaSocks5(proxyHost: String, proxyPort: Int): TunnelDiagnosticResult {
        return runCatching {
            val startPing = SystemClock.elapsedRealtime()
            val ipSocket = openSocks5Socket(proxyHost, proxyPort, "ip-api.com", 80)
            val pingMs = (SystemClock.elapsedRealtime() - startPing).toInt().coerceAtLeast(1)

            var exitIp = "Unknown"
            var exitCountry = "Remote"
            ipSocket.use { sock ->
                val out = sock.getOutputStream()
                val req = "GET /json HTTP/1.1\r\nHost: ip-api.com\r\nConnection: close\r\n\r\n"
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()
                val respText = sock.getInputStream().bufferedReader().readText()
                val jsonBody = respText.substringAfter("\r\n\r\n", "")
                if (jsonBody.contains("{")) {
                    val obj = JSONObject(jsonBody.substring(jsonBody.indexOf('{')))
                    exitIp = obj.optString("query", exitIp)
                    exitCountry = obj.optString("country", exitCountry)
                }
            }

            // Measure download throughput through the SOCKS5 tunnel (256 KB payload)
            val dlSocket = openSocks5Socket(proxyHost, proxyPort, "speed.cloudflare.com", 80)
            val mbps = dlSocket.use { sock ->
                val out = sock.getOutputStream()
                val req = "GET /__down?bytes=262144 HTTP/1.1\r\nHost: speed.cloudflare.com\r\nConnection: close\r\n\r\n"
                val dlStart = SystemClock.elapsedRealtime()
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()
                val input = sock.getInputStream()
                val buf = ByteArray(8192)
                var totalBytes = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    totalBytes += r
                }
                val elapsedSec = ((SystemClock.elapsedRealtime() - dlStart).coerceAtLeast(10)) / 1000.0
                (totalBytes * 8.0) / (elapsedSec * 1_000_000.0)
            }

            TunnelDiagnosticResult(
                exitIp = exitIp,
                exitCountry = exitCountry,
                downloadMbps = mbps,
                latencyMs = pingMs
            )
        }.getOrElse { err ->
            TunnelDiagnosticResult(
                exitIp = "Unavailable",
                exitCountry = "-",
                downloadMbps = 0.0,
                latencyMs = -1,
                errorMessage = err.message ?: "Proxy diagnostic failed"
            )
        }
    }

    private fun testDirectHttp(): TunnelDiagnosticResult {
        return runCatching {
            val start = SystemClock.elapsedRealtime()
            val ipReq = Request.Builder().url("https://ipwho.is/").get().build()
            var exitIp = "Unknown"
            var exitCountry = "Edge"
            okHttpClient.newCall(ipReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val obj = JSONObject(body)
                    exitIp = obj.optString("ip", exitIp)
                    exitCountry = obj.optString("country", exitCountry)
                }
            }
            val pingMs = (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)

            val dlReq = Request.Builder()
                .url("https://speed.cloudflare.com/__down?bytes=524288")
                .get()
                .build()
            val dlStart = SystemClock.elapsedRealtime()
            val totalBytes = okHttpClient.newCall(dlReq).execute().use { resp ->
                val stream = resp.body?.byteStream() ?: return@use 0L
                val buf = ByteArray(8192)
                var sum = 0L
                while (true) {
                    val r = stream.read(buf)
                    if (r <= 0) break
                    sum += r
                }
                sum
            }
            val elapsedSec = ((SystemClock.elapsedRealtime() - dlStart).coerceAtLeast(10)) / 1000.0
            val mbps = (totalBytes * 8.0) / (elapsedSec * 1_000_000.0)

            TunnelDiagnosticResult(
                exitIp = exitIp,
                exitCountry = exitCountry,
                downloadMbps = mbps,
                latencyMs = pingMs
            )
        }.getOrElse { err ->
            TunnelDiagnosticResult(
                exitIp = "Unavailable",
                exitCountry = "-",
                downloadMbps = 0.0,
                latencyMs = -1,
                errorMessage = err.message ?: "Speed test failed"
            )
        }
    }

    private fun openSocks5Socket(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int
    ): Socket {
        val sock = Socket()
        sock.soTimeout = 7000
        sock.connect(InetSocketAddress(proxyHost, proxyPort), 5000)
        val out: OutputStream = sock.getOutputStream()
        val inp: InputStream = sock.getInputStream()

        // SOCKS5 greeting: version 5, 1 auth method, 0x00 NO AUTH
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greet = ByteArray(2)
        if (inp.read(greet) != 2 || greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
            sock.close()
            error("SOCKS5 auth rejected")
        }

        // SOCKS5 CONNECT with domain name (0x03)
        val domainBytes = targetHost.toByteArray(Charsets.UTF_8)
        val req = ByteArray(7 + domainBytes.size)
        req[0] = 0x05
        req[1] = 0x01 // CONNECT
        req[2] = 0x00
        req[3] = 0x03 // DOMAINNAME
        req[4] = domainBytes.size.toByte()
        domainBytes.copyInto(req, 5)
        req[5 + domainBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
        req[6 + domainBytes.size] = (targetPort and 0xFF).toByte()
        out.write(req)
        out.flush()

        val respHead = ByteArray(4)
        if (inp.read(respHead) != 4 || respHead[1] != 0x00.toByte()) {
            sock.close()
            error("SOCKS5 connect failed (code=${respHead.getOrNull(1)})")
        }
        when (respHead[3].toInt() and 0xFF) {
            1 -> inp.read(ByteArray(6)) // IPv4 + port
            3 -> {
                val len = inp.read().coerceAtLeast(0)
                inp.read(ByteArray(len + 2))
            }
            4 -> inp.read(ByteArray(18)) // IPv6 + port
        }
        return sock
    }
}
