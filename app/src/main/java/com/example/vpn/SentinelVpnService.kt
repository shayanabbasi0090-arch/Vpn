package com.example.vpn

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.R
import com.example.vpn.wg.ParsedWireGuardConfig
import com.example.vpn.wg.WireGuardTunnelSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class SentinelVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.vpn.SENTINEL_START"
        const val ACTION_STOP = "com.example.vpn.SENTINEL_STOP"

        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_DNS_PRIMARY = "extra_dns_primary"
        const val EXTRA_DNS_SECONDARY = "extra_dns_secondary"
        const val EXTRA_EXCLUDE_LAN = "extra_exclude_lan"
        const val EXTRA_WG_CONFIG = "extra_wg_config"
        const val EXTRA_SOCKS_HOST = "extra_socks_host"
        const val EXTRA_SOCKS_PORT = "extra_socks_port"

        private const val TCP_MSS_CHUNK = 1400

        val rxBytes = AtomicLong(0L)
        val txBytes = AtomicLong(0L)

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _wgHandshakeActive = MutableStateFlow(false)
        val wgHandshakeActive: StateFlow<Boolean> = _wgHandshakeActive.asStateFlow()

        private val _revokedEvent = MutableStateFlow(0L)
        val revokedEvent: StateFlow<Long> = _revokedEvent.asStateFlow()
    }

    private data class SessionKey(
        val protocol: Int,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    private class TcpSession(
        val key: SessionKey,
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val channel: SocketChannel,
        var serverSeq: Long,
        var clientAck: Long,
        @Volatile var isClosed: Boolean = false
    )

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null
    private var tunJob: Job? = null
    private var wgSocketChannel: DatagramChannel? = null

    private var activeDnsPrimary: String = "1.1.1.1"
    private var activeSocksHost: String? = null
    private var activeSocksPort: Int = 0
    private val dohResolver = DohResolver()

    private val udpChannels = ConcurrentHashMap<SessionKey, DatagramChannel>()
    private val tcpSessions = ConcurrentHashMap<SessionKey, TcpSession>()
    private var tunWriteChannel = Channel<ByteArray>(capacity = 512)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: "Sentinel Relay"
                val dnsPrimary = intent?.getStringExtra(EXTRA_DNS_PRIMARY) ?: "1.1.1.1"
                val dnsSecondary = intent?.getStringExtra(EXTRA_DNS_SECONDARY) ?: "1.0.0.1"
                val excludeLan = intent?.getBooleanExtra(EXTRA_EXCLUDE_LAN, true) ?: true
                val wgConfigText = intent?.getStringExtra(EXTRA_WG_CONFIG)
                val socksHost = intent?.getStringExtra(EXTRA_SOCKS_HOST)
                val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, 0) ?: 0
                startTunnel(
                    serverName = serverName,
                    dnsPrimary = dnsPrimary,
                    dnsSecondary = dnsSecondary,
                    excludeLan = excludeLan,
                    wgConfigText = wgConfigText,
                    socksHost = socksHost,
                    socksPort = socksPort
                )
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startTunnel(
        serverName: String,
        dnsPrimary: String,
        dnsSecondary: String,
        excludeLan: Boolean,
        wgConfigText: String?,
        socksHost: String?,
        socksPort: Int
    ) {
        stopTunnelInternal(notifyRevoked = false)
        tunWriteChannel = Channel(capacity = 512)
        activeDnsPrimary = dnsPrimary
        activeSocksHost = socksHost?.takeIf { it.isNotBlank() && socksPort in 1..65535 }
        activeSocksPort = socksPort

        val parsedWg = wgConfigText?.let { ParsedWireGuardConfig.parse(it).getOrNull() }
        val engineSubtext = when {
            parsedWg != null -> "WireGuard Noise-IK"
            activeSocksHost != null -> "SOCKS5 Remote IP + DoH"
            else -> "DoH Shield Packet Tunnel"
        }

        val initialNotification = VpnNotificationHelper.buildNotification(
            context = this,
            title = getString(R.string.vpn_notif_title_connected),
            contentText = "$serverName • Establishing Tunnel…",
            subText = engineSubtext
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VpnNotificationHelper.NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(VpnNotificationHelper.NOTIFICATION_ID, initialNotification)
        }

        val tunAddress = parsedWg?.addressIp ?: "10.10.0.2"
        val tunPrefix = parsedWg?.addressPrefix ?: 32
        val tunMtu = parsedWg?.mtu ?: 1500

        val builder = Builder()
            .setSession("Sentinel VPN - $serverName")
            .setMtu(tunMtu)
            .addAddress(tunAddress, tunPrefix)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(dnsPrimary)
            .addDnsServer(dnsSecondary)

        if (excludeLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                builder.excludeRoute(IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
                builder.excludeRoute(IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
                builder.excludeRoute(IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
            }
        }

        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = runCatching { builder.establish() }.getOrNull()
        if (pfd == null) {
            stopSelf()
            return
        }

        vpnInterface = pfd
        rxBytes.set(0L)
        txBytes.set(0L)
        _wgHandshakeActive.value = false
        _isRunning.value = true

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceScope = scope

        // TUN writer coroutine
        scope.launch {
            val outStream = FileOutputStream(pfd.fileDescriptor)
            for (packet in tunWriteChannel) {
                if (!isActive) break
                runCatching {
                    outStream.write(packet)
                    rxBytes.addAndGet(packet.size.toLong())
                }
            }
        }

        if (parsedWg != null) {
            startWireGuardLoop(pfd, parsedWg, scope)
        } else {
            startRelayTunLoop(pfd, scope)
        }
    }

    private fun startWireGuardLoop(
        pfd: ParcelFileDescriptor,
        wgConfig: ParsedWireGuardConfig,
        scope: CoroutineScope
    ) {
        val session = WireGuardTunnelSession(wgConfig)
        val endpoint = InetSocketAddress(InetAddress.getByName(wgConfig.endpointHost), wgConfig.endpointPort)

        val channel = runCatching {
            DatagramChannel.open().apply {
                protect(socket())
                connect(endpoint)
            }
        }.getOrNull()

        if (channel == null) {
            startRelayTunLoop(pfd, scope)
            return
        }

        wgSocketChannel = channel

        // Inbound WireGuard UDP -> decrypt -> TUN
        scope.launch {
            val recvBuf = ByteBuffer.allocate(4096)
            try {
                while (isActive && channel.isOpen) {
                    recvBuf.clear()
                    val n = channel.read(recvBuf)
                    if (n <= 0) continue
                    val raw = ByteArray(n)
                    recvBuf.flip()
                    recvBuf.get(raw)

                    when (raw[0].toInt()) {
                        2 -> {
                            if (session.processHandshakeResponse(raw)) {
                                _wgHandshakeActive.value = true
                            }
                        }
                        3 -> {
                            if (session.processCookieReply(raw)) {
                                val retry = session.createHandshakeInitiation()
                                runCatching {
                                    channel.write(ByteBuffer.wrap(retry))
                                    txBytes.addAndGet(retry.size.toLong())
                                }
                            }
                        }
                        4 -> {
                            val decrypted = session.decryptTransportPacket(raw) ?: continue
                            if (decrypted.size >= 20) {
                                val version = (decrypted[0].toInt() ushr 4) and 0x0F
                                if (version == 4) {
                                    val totalLen = ((decrypted[2].toInt() and 0xFF) shl 8) or
                                        (decrypted[3].toInt() and 0xFF)
                                    val ipPacket = if (totalLen in 20..decrypted.size) {
                                        decrypted.copyOf(totalLen)
                                    } else {
                                        decrypted
                                    }
                                    tunWriteChannel.trySend(ipPacket)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Socket closed on disconnect
            }
        }

        // Initial WireGuard Handshake + Keepalive Loop
        scope.launch {
            var attempts = 0
            while (isActive && channel.isOpen && !session.isHandshakeComplete && attempts < 4) {
                val initPkt = session.createHandshakeInitiation()
                runCatching {
                    channel.write(ByteBuffer.wrap(initPkt))
                    txBytes.addAndGet(initPkt.size.toLong())
                }
                attempts++
                delay(1200)
            }

            while (isActive && channel.isOpen) {
                delay((wgConfig.persistentKeepaliveSec * 1000L).coerceAtLeast(10_000L))
                if (session.isHandshakeComplete) {
                    session.encryptTransportPacket(ByteArray(0))?.let { keepalive ->
                        runCatching {
                            channel.write(ByteBuffer.wrap(keepalive))
                            txBytes.addAndGet(keepalive.size.toLong())
                        }
                    }
                }
            }
        }

        // TUN reader -> WireGuard encrypt (or DoH+relay fallback if WG handshake blocked)
        tunJob = scope.launch {
            val inStream = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)
            while (isActive) {
                val length = runCatching { inStream.read(buffer) }.getOrElse { -1 }
                if (length < 0) break
                if (length == 0) {
                    delay(5)
                    continue
                }

                val packet = buffer.copyOf(length)
                if (session.isHandshakeComplete) {
                    val encrypted = session.encryptTransportPacket(packet)
                    if (encrypted != null) {
                        runCatching {
                            channel.write(ByteBuffer.wrap(encrypted))
                            txBytes.addAndGet(encrypted.size.toLong())
                        }
                        continue
                    }
                }
                txBytes.addAndGet(length.toLong())
                handleOutboundPacket(packet, length, scope)
            }
        }
    }

    private fun startRelayTunLoop(pfd: ParcelFileDescriptor, scope: CoroutineScope) {
        tunJob = scope.launch {
            val inStream = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)
            while (isActive) {
                val length = runCatching { inStream.read(buffer) }.getOrElse { -1 }
                if (length < 0) break
                if (length == 0) {
                    delay(5)
                    continue
                }
                txBytes.addAndGet(length.toLong())
                val packet = buffer.copyOf(length)
                handleOutboundPacket(packet, length, scope)
            }
        }
    }

    private fun handleOutboundPacket(packet: ByteArray, length: Int, scope: CoroutineScope) {
        val ipv4 = PacketUtils.parseIpv4Header(packet, length) ?: return
        val transportOffset = ipv4.headerLength

        when (ipv4.protocol) {
            PacketUtils.PROTO_UDP -> {
                if (length < transportOffset + 8) return
                val srcPort = PacketUtils.readPort(packet, transportOffset)
                val dstPort = PacketUtils.readPort(packet, transportOffset + 2)
                val udpLen = PacketUtils.readPort(packet, transportOffset + 4)
                val payloadOffset = transportOffset + 8
                val payloadLen = (udpLen - 8).coerceAtMost(length - payloadOffset)
                if (payloadLen < 0) return
                val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLen)

                // Intercept UDP port 53 DNS queries and resolve via encrypted DNS-over-HTTPS
                if (dstPort == 53 && payload.size >= 12) {
                    scope.launch {
                        val dohResp = dohResolver.resolveWireQuery(payload, activeDnsPrimary)
                        if (dohResp != null) {
                            val replyPacket = PacketUtils.buildIpv4UdpPacket(
                                srcIp = ipv4.destinationAddress,
                                dstIp = ipv4.sourceAddress,
                                srcPort = 53,
                                dstPort = srcPort,
                                payload = dohResp
                            )
                            tunWriteChannel.trySend(replyPacket)
                            return@launch
                        }
                        val key = SessionKey(
                            protocol = PacketUtils.PROTO_UDP,
                            srcIp = ipv4.sourceIpString,
                            srcPort = srcPort,
                            dstIp = ipv4.destIpString,
                            dstPort = dstPort
                        )
                        forwardUdp(key, ipv4.sourceAddress, ipv4.destinationAddress, payload, scope)
                    }
                    return
                }

                val key = SessionKey(
                    protocol = PacketUtils.PROTO_UDP,
                    srcIp = ipv4.sourceIpString,
                    srcPort = srcPort,
                    dstIp = ipv4.destIpString,
                    dstPort = dstPort
                )
                forwardUdp(key, ipv4.sourceAddress, ipv4.destinationAddress, payload, scope)
            }

            PacketUtils.PROTO_TCP -> {
                if (length < transportOffset + 20) return
                val srcPort = PacketUtils.readPort(packet, transportOffset)
                val dstPort = PacketUtils.readPort(packet, transportOffset + 2)
                val seqNum = PacketUtils.readUInt32(packet, transportOffset + 4)
                val tcpHeaderLen = ((packet[transportOffset + 12].toInt() and 0xF0) ushr 4) * 4
                val flags = packet[transportOffset + 13].toInt() and 0x3F
                val payloadOffset = transportOffset + tcpHeaderLen
                val payloadLen = (ipv4.totalLength - payloadOffset).coerceAtLeast(0)
                val payload = if (payloadLen > 0 && payloadOffset + payloadLen <= length) {
                    packet.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                } else {
                    ByteArray(0)
                }

                val key = SessionKey(
                    protocol = PacketUtils.PROTO_TCP,
                    srcIp = ipv4.sourceIpString,
                    srcPort = srcPort,
                    dstIp = ipv4.destIpString,
                    dstPort = dstPort
                )
                forwardTcp(
                    key = key,
                    srcIpBytes = ipv4.sourceAddress,
                    dstIpBytes = ipv4.destinationAddress,
                    clientSeq = seqNum,
                    flags = flags,
                    payload = payload,
                    scope = scope
                )
            }
        }
    }

    private fun forwardUdp(
        key: SessionKey,
        clientIp: ByteArray,
        remoteIp: ByteArray,
        payload: ByteArray,
        scope: CoroutineScope
    ) {
        val existing = udpChannels[key]
        if (existing != null && existing.isOpen) {
            scope.launch {
                runCatching { existing.write(ByteBuffer.wrap(payload)) }
            }
            return
        }

        scope.launch {
            val channel = runCatching {
                DatagramChannel.open().apply {
                    protect(socket())
                    connect(InetSocketAddress(InetAddress.getByAddress(remoteIp), key.dstPort))
                }
            }.getOrNull() ?: return@launch

            udpChannels[key] = channel
            runCatching { channel.write(ByteBuffer.wrap(payload)) }

            val readBuf = ByteBuffer.allocate(TCP_MSS_CHUNK)
            try {
                while (isActive && channel.isOpen) {
                    readBuf.clear()
                    val bytesRead = channel.read(readBuf)
                    if (bytesRead <= 0) break
                    val respData = ByteArray(bytesRead)
                    readBuf.flip()
                    readBuf.get(respData)
                    val replyPacket = PacketUtils.buildIpv4UdpPacket(
                        srcIp = remoteIp,
                        dstIp = clientIp,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        payload = respData
                    )
                    tunWriteChannel.trySend(replyPacket)
                }
            } catch (_: Exception) {
                // Socket closed
            } finally {
                udpChannels.remove(key)
                runCatching { channel.close() }
            }
        }
    }

    private fun forwardTcp(
        key: SessionKey,
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        clientSeq: Long,
        flags: Int,
        payload: ByteArray,
        scope: CoroutineScope
    ) {
        val isSyn = (flags and PacketUtils.TCP_SYN) != 0
        val isFin = (flags and PacketUtils.TCP_FIN) != 0
        val isRst = (flags and PacketUtils.TCP_RST) != 0

        if (isRst) {
            tcpSessions.remove(key)?.let {
                it.isClosed = true
                runCatching { it.channel.close() }
            }
            return
        }

        if (isSyn && !tcpSessions.containsKey(key)) {
            scope.launch {
                val socksHost = activeSocksHost
                val socksPort = activeSocksPort
                val channel = if (!socksHost.isNullOrBlank() && socksPort in 1..65535) {
                    connectViaSocks5(socksHost, socksPort, dstIpBytes, key.dstPort)
                } else {
                    runCatching {
                        SocketChannel.open().apply {
                            protect(socket())
                            socket().soTimeout = 15000
                            connect(InetSocketAddress(InetAddress.getByAddress(dstIpBytes), key.dstPort))
                        }
                    }.getOrNull()
                }

                if (channel == null) {
                    val rstPacket = PacketUtils.buildIpv4TcpPacket(
                        srcIp = dstIpBytes,
                        dstIp = srcIpBytes,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        seqNum = 0L,
                        ackNum = clientSeq + 1L,
                        flags = PacketUtils.TCP_RST or PacketUtils.TCP_ACK
                    )
                    tunWriteChannel.trySend(rstPacket)
                    return@launch
                }

                val initialServerSeq = Random.nextInt(10000, 9999999).toLong()
                val session = TcpSession(
                    key = key,
                    srcIpBytes = srcIpBytes,
                    dstIpBytes = dstIpBytes,
                    channel = channel,
                    serverSeq = initialServerSeq,
                    clientAck = clientSeq + 1L
                )
                tcpSessions[key] = session

                val synAck = PacketUtils.buildIpv4TcpPacket(
                    srcIp = dstIpBytes,
                    dstIp = srcIpBytes,
                    srcPort = key.dstPort,
                    dstPort = key.srcPort,
                    seqNum = session.serverSeq,
                    ackNum = session.clientAck,
                    flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK
                )
                session.serverSeq += 1L
                tunWriteChannel.trySend(synAck)

                val buf = ByteBuffer.allocate(TCP_MSS_CHUNK)
                try {
                    while (isActive && !session.isClosed && channel.isOpen) {
                        buf.clear()
                        val read = channel.read(buf)
                        if (read < 0) {
                            val finAck = PacketUtils.buildIpv4TcpPacket(
                                srcIp = dstIpBytes,
                                dstIp = srcIpBytes,
                                srcPort = key.dstPort,
                                dstPort = key.srcPort,
                                seqNum = session.serverSeq,
                                ackNum = session.clientAck,
                                flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK
                            )
                            session.serverSeq += 1L
                            tunWriteChannel.trySend(finAck)
                            break
                        }
                        if (read > 0) {
                            val chunk = ByteArray(read)
                            buf.flip()
                            buf.get(chunk)
                            val dataPkt = PacketUtils.buildIpv4TcpPacket(
                                srcIp = dstIpBytes,
                                dstIp = srcIpBytes,
                                srcPort = key.dstPort,
                                dstPort = key.srcPort,
                                seqNum = session.serverSeq,
                                ackNum = session.clientAck,
                                flags = PacketUtils.TCP_PSH or PacketUtils.TCP_ACK,
                                payload = chunk
                            )
                            session.serverSeq += read.toLong()
                            tunWriteChannel.trySend(dataPkt)
                        }
                    }
                } catch (_: Exception) {
                    // Remote connection closed
                } finally {
                    session.isClosed = true
                    tcpSessions.remove(key)
                    runCatching { channel.close() }
                }
            }
            return
        }

        val session = tcpSessions[key] ?: return

        if (payload.isNotEmpty()) {
            scope.launch {
                runCatching {
                    session.channel.write(ByteBuffer.wrap(payload))
                    session.clientAck = clientSeq + payload.size.toLong()
                    val ackPkt = PacketUtils.buildIpv4TcpPacket(
                        srcIp = session.dstIpBytes,
                        dstIp = session.srcIpBytes,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        seqNum = session.serverSeq,
                        ackNum = session.clientAck,
                        flags = PacketUtils.TCP_ACK
                    )
                    tunWriteChannel.trySend(ackPkt)
                }
            }
        }

        if (isFin) {
            session.clientAck = clientSeq + payload.size.toLong() + 1L
            val finAck = PacketUtils.buildIpv4TcpPacket(
                srcIp = session.dstIpBytes,
                dstIp = session.srcIpBytes,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seqNum = session.serverSeq,
                ackNum = session.clientAck,
                flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK
            )
            tunWriteChannel.trySend(finAck)
            session.isClosed = true
            tcpSessions.remove(key)
            runCatching { session.channel.close() }
        }
    }

    private fun connectViaSocks5(
        socksHost: String,
        socksPort: Int,
        dstIp4: ByteArray,
        dstPort: Int
    ): SocketChannel? {
        return runCatching {
            val channel = SocketChannel.open()
            protect(channel.socket())
            channel.socket().soTimeout = 15000
            channel.connect(InetSocketAddress(socksHost, socksPort))

            // 1. Greeting: VER=5, NMETHODS=1, METHOD=0 (No Auth)
            channel.write(ByteBuffer.wrap(byteArrayOf(0x05, 0x01, 0x00)))
            val greetResp = ByteBuffer.allocate(2)
            while (greetResp.hasRemaining()) {
                if (channel.read(greetResp) < 0) {
                    channel.close()
                    return null
                }
            }
            val gArr = greetResp.array()
            if (gArr[0] != 0x05.toByte() || gArr[1] != 0x00.toByte()) {
                channel.close()
                return null
            }

            // 2. CONNECT IPv4 (0x01)
            val req = byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                dstIp4[0], dstIp4[1], dstIp4[2], dstIp4[3],
                ((dstPort ushr 8) and 0xFF).toByte(),
                (dstPort and 0xFF).toByte()
            )
            channel.write(ByteBuffer.wrap(req))

            val head = ByteBuffer.allocate(4)
            while (head.hasRemaining()) {
                if (channel.read(head) < 0) {
                    channel.close()
                    return null
                }
            }
            val hArr = head.array()
            if (hArr[1] != 0x00.toByte()) {
                channel.close()
                return null
            }
            val extraBytes = when (hArr[3].toInt() and 0xFF) {
                1 -> 6 // IPv4 (4) + port (2)
                4 -> 18 // IPv6 (16) + port (2)
                3 -> {
                    val lenBuf = ByteBuffer.allocate(1)
                    channel.read(lenBuf)
                    (lenBuf.get(0).toInt() and 0xFF) + 2
                }
                else -> 6
            }
            val drain = ByteBuffer.allocate(extraBytes)
            while (drain.hasRemaining()) {
                if (channel.read(drain) < 0) break
            }
            channel
        }.getOrNull()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopTunnelInternal(notifyRevoked = true)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnelInternal(notifyRevoked = false)
        super.onDestroy()
    }

    private fun stopTunnel() {
        stopTunnelInternal(notifyRevoked = false)
        stopSelf()
    }

    private fun stopTunnelInternal(notifyRevoked: Boolean) {
        _isRunning.value = false
        _wgHandshakeActive.value = false
        activeSocksHost = null
        activeSocksPort = 0

        if (notifyRevoked) {
            _revokedEvent.value = System.currentTimeMillis()
        }

        tunJob?.cancel()
        tunJob = null

        runCatching { wgSocketChannel?.close() }
        wgSocketChannel = null

        udpChannels.values.forEach { runCatching { it.close() } }
        udpChannels.clear()

        tcpSessions.values.forEach {
            it.isClosed = true
            runCatching { it.channel.close() }
        }
        tcpSessions.clear()

        runCatching { tunWriteChannel.close() }

        serviceScope?.cancel()
        serviceScope = null

        runCatching { vpnInterface?.close() }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        VpnNotificationHelper.cancelNotification(this)
    }
}
