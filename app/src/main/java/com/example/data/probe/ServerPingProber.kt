package com.example.data.probe

import android.os.SystemClock
import com.example.domain.model.ServerProtocol
import com.example.domain.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class ServerPingProber {

    suspend fun pingAll(
        servers: List<VpnServer>,
        timeoutMs: Int = 2200,
        concurrency: Int = 14
    ): List<VpnServer> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        servers.map { server ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val rtt = measureServerRtt(server, timeoutMs)
                    server.copy(pingMs = rtt)
                }
            }
        }.awaitAll()
    }

    suspend fun pingSingle(server: VpnServer, timeoutMs: Int = 2200): Int =
        withContext(Dispatchers.IO) {
            measureServerRtt(server, timeoutMs)
        }

    private fun measureServerRtt(server: VpnServer, timeoutMs: Int): Int {
        return when (server.protocol) {
            ServerProtocol.WIREGUARD -> {
                val primary = tcpConnectMillis(server.host, 443, timeoutMs)
                if (primary > 0) primary else tcpConnectMillis(server.host, 80, timeoutMs)
            }
            ServerProtocol.SOCKS5_TUN -> {
                // Verify real SOCKS5 greeting handshake so only healthy open relays pass
                verifySocks5GreetingMillis(server.host, server.port, timeoutMs)
            }
            ServerProtocol.RELAY_TUN -> {
                val port = server.port.takeIf { it in 1..65535 } ?: 443
                val primary = tcpConnectMillis(server.host, port, timeoutMs)
                if (primary > 0) return primary
                if (port != 443) {
                    val ssl = tcpConnectMillis(server.host, 443, timeoutMs)
                    if (ssl > 0) return ssl
                }
                if (port != 80) {
                    val http = tcpConnectMillis(server.host, 80, timeoutMs)
                    if (http > 0) return http
                }
                -1
            }
        }
    }

    private fun verifySocks5GreetingMillis(host: String, port: Int, timeoutMs: Int): Int {
        return runCatching {
            val socket = Socket()
            val start = SystemClock.elapsedRealtime()
            socket.use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val resp = ByteArray(2)
                val read = inp.read(resp)
                if (read != 2 || resp[0] != 0x05.toByte() || resp[1] != 0x00.toByte()) {
                    return -1
                }
            }
            (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)
        }.getOrElse { -1 }
    }

    private fun tcpConnectMillis(host: String, port: Int, timeoutMs: Int): Int {
        return runCatching {
            val socket = Socket()
            val start = SystemClock.elapsedRealtime()
            socket.use {
                it.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)
        }.getOrElse { -1 }
    }
}
