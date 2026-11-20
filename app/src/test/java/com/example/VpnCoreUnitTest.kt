package com.example

import com.example.domain.model.PingTier
import com.example.domain.model.ServerProtocol
import com.example.domain.model.TunnelStats
import com.example.domain.model.VpnServer
import com.example.vpn.PacketUtils
import com.example.vpn.wg.ParsedWireGuardConfig
import com.example.vpn.wg.WireGuardCrypto
import com.example.vpn.wg.WireGuardTunnelSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnCoreUnitTest {

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `country code converts to valid regional indicator flag emoji`() {
        assertEquals("🇯🇵", VpnServer.countryCodeToFlag("JP"))
        assertEquals("🇩🇪", VpnServer.countryCodeToFlag("de"))
        assertEquals("🇺🇸", VpnServer.countryCodeToFlag("US"))
        assertEquals("🌐", VpnServer.countryCodeToFlag("AUTO"))
    }

    @Test
    fun `ping tier categorizes latency into fast medium slow and unreachable`() {
        val base = VpnServer(
            id = "test",
            name = "Test",
            countryCode = "US",
            countryName = "United States",
            city = "NYC",
            host = "1.1.1.1",
            port = 443,
            protocol = ServerProtocol.WIREGUARD
        )
        assertEquals(PingTier.UNTESTED, base.copy(pingMs = null).pingTier)
        assertEquals(PingTier.UNREACHABLE, base.copy(pingMs = -1).pingTier)
        assertEquals(PingTier.FAST, base.copy(pingMs = 38).pingTier)
        assertEquals(PingTier.MEDIUM, base.copy(pingMs = 110).pingTier)
        assertEquals(PingTier.SLOW, base.copy(pingMs = 240).pingTier)
    }

    @Test
    fun `tunnel stats formats duration and throughput cleanly`() {
        val stats = TunnelStats(durationSeconds = 3665)
        assertEquals("01:01:05", stats.formattedDuration())
        assertEquals("512 B/s", TunnelStats.formatSpeed(512))
        assertEquals("2.0 KB/s", TunnelStats.formatSpeed(2048))
        assertEquals("1.50 MB/s", TunnelStats.formatSpeed((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `packet utils synthesizes and parses ipv4 udp packet with valid checksum`() {
        val srcIp = byteArrayOf(10, 10, 0, 2)
        val dstIp = byteArrayOf(1, 1, 1, 1)
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00)

        val packet = PacketUtils.buildIpv4UdpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 54321,
            dstPort = 53,
            payload = payload
        )

        val parsed = PacketUtils.parseIpv4Header(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(4, parsed!!.version)
        assertEquals(20, parsed.headerLength)
        assertEquals(PacketUtils.PROTO_UDP, parsed.protocol)
        assertEquals("10.10.0.2", parsed.sourceIpString)
        assertEquals("1.1.1.1", parsed.destIpString)
        assertEquals(54321, PacketUtils.readPort(packet, 20))
        assertEquals(53, PacketUtils.readPort(packet, 22))
    }

    @Test
    fun `packet utils synthesizes tcp syn-ack packet cleanly`() {
        val srcIp = byteArrayOf(1, 1, 1, 1)
        val dstIp = byteArrayOf(10, 10, 0, 2)
        val packet = PacketUtils.buildIpv4TcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 443,
            dstPort = 55100,
            seqNum = 1000L,
            ackNum = 5001L,
            flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK
        )

        val parsed = PacketUtils.parseIpv4Header(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(PacketUtils.PROTO_TCP, parsed!!.protocol)
        assertEquals(443, PacketUtils.readPort(packet, 20))
        assertEquals(55100, PacketUtils.readPort(packet, 22))
        assertEquals(1000L, PacketUtils.readUInt32(packet, 24))
        assertEquals(5001L, PacketUtils.readUInt32(packet, 28))
    }

    @Test
    fun `x25519 matches RFC 7748 test vector`() {
        val alicePriv = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val expectedAlicePub = hexToBytes("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val expectedBobPub = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expectedShared = hexToBytes("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        val alicePub = WireGuardCrypto.x25519PublicFromPrivate(alicePriv)
        val bobPub = WireGuardCrypto.x25519PublicFromPrivate(bobPriv)
        assertArrayEquals(expectedAlicePub, alicePub)
        assertArrayEquals(expectedBobPub, bobPub)

        val shared1 = WireGuardCrypto.x25519(alicePriv, bobPub)
        val shared2 = WireGuardCrypto.x25519(bobPriv, alicePub)
        assertArrayEquals(expectedShared, shared1)
        assertArrayEquals(expectedShared, shared2)
    }

    @Test
    fun `blake2s matches RFC 7693 test vector`() {
        val digest = WireGuardCrypto.blake2s("abc".toByteArray(Charsets.UTF_8))
        assertEquals(
            "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
            bytesToHex(digest)
        )
    }

    @Test
    fun `chacha20 poly1305 encrypts and decrypts cleanly`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = "wireguard-aad".toByteArray()
        val plaintext = "Sentinel VPN encrypted IPv4 payload".toByteArray()

        val sealed = WireGuardCrypto.pureKotlinChaCha20Poly1305Encrypt(key, nonce, plaintext, aad)
        val opened = WireGuardCrypto.pureKotlinChaCha20Poly1305Decrypt(key, nonce, sealed, aad)
        assertNotNull(opened)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `wireguard config parser and handshake initiation produce valid 148-byte message`() {
        val clientKeys = WireGuardCrypto.generateKeyPair()
        val peerKeys = WireGuardCrypto.generateKeyPair()
        val conf = """
            [Interface]
            PrivateKey = ${clientKeys.privateKeyBase64}
            Address = 172.16.0.2/32
            DNS = 1.1.1.1
            MTU = 1280

            [Peer]
            PublicKey = ${peerKeys.publicKeyBase64}
            AllowedIPs = 0.0.0.0/0
            Endpoint = 162.159.192.1:2408
            PersistentKeepalive = 25
        """.trimIndent()

        val parsed = ParsedWireGuardConfig.parse(conf).getOrThrow()
        assertEquals("172.16.0.2", parsed.addressIp)
        assertEquals(32, parsed.addressPrefix)
        assertEquals("162.159.192.1", parsed.endpointHost)
        assertEquals(2408, parsed.endpointPort)

        val session = WireGuardTunnelSession(parsed)
        val initiation = session.createHandshakeInitiation()
        assertEquals(148, initiation.size)
        assertEquals(1, initiation[0].toInt())
        assertTrue(initiation.sliceArray(100 until 116).any { it.toInt() != 0 })
    }
}
