package com.example.vpn.wg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Implements the WireGuard Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s 1-RTT handshake
 * and Type 4 transport packet encryption/decryption in pure Kotlin.
 */
class WireGuardTunnelSession(
    private val config: ParsedWireGuardConfig
) {
    companion object {
        private val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray(Charsets.UTF_8)
        private val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray(Charsets.UTF_8)
        private val LABEL_MAC1 = "mac1----".toByteArray(Charsets.UTF_8)
        private val LABEL_COOKIE = "cookie--".toByteArray(Charsets.UTF_8)

        private val INITIAL_C = WireGuardCrypto.blake2s(CONSTRUCTION)
        private val INITIAL_H = WireGuardCrypto.blake2s(INITIAL_C + IDENTIFIER)
    }

    private val random = SecureRandom()
    private val staticPriv = config.privateKey
    private val staticPub = WireGuardCrypto.x25519PublicFromPrivate(staticPriv)
    private val peerStaticPub = config.peerPublicKey

    // Handshake ephemeral state
    private var localSenderIndex: Int = 0
    private var remoteSenderIndex: Int = 0
    private var ephemeralPriv: ByteArray = ByteArray(0)
    private var chainKeyC: ByteArray = ByteArray(0)
    private var hashH: ByteArray = ByteArray(0)
    private var lastCookie: ByteArray? = null
    private var lastMac1: ByteArray = ByteArray(16)

    // Transport keys
    @Volatile var isHandshakeComplete: Boolean = false
        private set
    private var sendKey: ByteArray = ByteArray(0)
    private var recvKey: ByteArray = ByteArray(0)
    private val sendCounter = AtomicLong(0L)

    fun createHandshakeInitiation(): ByteArray {
        isHandshakeComplete = false
        sendCounter.set(0L)

        val eph = WireGuardCrypto.generateKeyPair()
        ephemeralPriv = eph.privateKey
        localSenderIndex = random.nextInt()

        var h = WireGuardCrypto.blake2s(INITIAL_H + peerStaticPub)
        var c = INITIAL_C

        // Ephemeral
        h = WireGuardCrypto.blake2s(h + eph.publicKey)
        c = WireGuardCrypto.kdf1(c, eph.publicKey)

        // DH(e_i, s_r)
        val dhEs = WireGuardCrypto.x25519(eph.privateKey, peerStaticPub)
        val (c2, k1) = WireGuardCrypto.kdf2(c, dhEs)
        c = c2
        val encStatic = WireGuardCrypto.aeadEncrypt(k1, ByteArray(12), staticPub, h)
        h = WireGuardCrypto.blake2s(h + encStatic)

        // DH(s_i, s_r)
        val dhSs = WireGuardCrypto.x25519(staticPriv, peerStaticPub)
        val (c3, k2) = WireGuardCrypto.kdf2(c, dhSs)
        c = c3
        val encTimestamp = WireGuardCrypto.aeadEncrypt(k2, ByteArray(12), WireGuardCrypto.tai64nNow(), h)
        h = WireGuardCrypto.blake2s(h + encTimestamp)

        chainKeyC = c
        hashH = h

        val msgWithoutMacs = ByteBuffer.allocate(116).order(ByteOrder.LITTLE_ENDIAN)
            .put(1.toByte()) // Type 1: Handshake Initiation
            .put(0.toByte()).put(0.toByte()).put(0.toByte())
            .putInt(localSenderIndex)
            .put(eph.publicKey)
            .put(encStatic)
            .put(encTimestamp)
            .array()

        val mac1Key = WireGuardCrypto.blake2s(LABEL_MAC1 + peerStaticPub)
        val mac1 = WireGuardCrypto.blake2s(msgWithoutMacs, key = mac1Key, outLen = 16)
        lastMac1 = mac1

        val mac2 = lastCookie?.let { cookie ->
            WireGuardCrypto.blake2s(msgWithoutMacs + mac1, key = cookie, outLen = 16)
        } ?: ByteArray(16)

        return msgWithoutMacs + mac1 + mac2
    }

    fun processHandshakeResponse(packet: ByteArray): Boolean {
        if (packet.size < 92 || packet[0].toInt() != 2) return false
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        bb.get() // type 2
        bb.get(); bb.get(); bb.get() // reserved
        val peerIndex = bb.int
        val receiverIndex = bb.int
        if (receiverIndex != localSenderIndex) return false

        val peerEphPub = ByteArray(32)
        bb.get(peerEphPub)
        val encNothing = ByteArray(16)
        bb.get(encNothing)

        var h = WireGuardCrypto.blake2s(hashH + peerEphPub)
        var c = WireGuardCrypto.kdf1(chainKeyC, peerEphPub)

        // DH(e_i, e_r)
        c = WireGuardCrypto.kdf1(c, WireGuardCrypto.x25519(ephemeralPriv, peerEphPub))
        // DH(s_i, e_r)
        c = WireGuardCrypto.kdf1(c, WireGuardCrypto.x25519(staticPriv, peerEphPub))

        // PSK mix
        val psk = config.presharedKey ?: ByteArray(32)
        val (cNext, tau, k) = WireGuardCrypto.kdf3(c, psk)
        c = cNext
        h = WireGuardCrypto.blake2s(h + tau)

        val decrypted = WireGuardCrypto.aeadDecrypt(k, ByteArray(12), encNothing, h) ?: return false
        if (decrypted.isNotEmpty()) return false

        val (tSend, tRecv) = WireGuardCrypto.kdf2(c, ByteArray(0))
        remoteSenderIndex = peerIndex
        sendKey = tSend
        recvKey = tRecv
        isHandshakeComplete = true
        return true
    }

    fun processCookieReply(packet: ByteArray): Boolean {
        if (packet.size < 64 || packet[0].toInt() != 3) return false
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        bb.get() // type 3
        bb.get(); bb.get(); bb.get()
        val receiverIndex = bb.int
        if (receiverIndex != localSenderIndex) return false

        val nonce24 = ByteArray(24)
        bb.get(nonce24)
        val encCookie = ByteArray(32)
        bb.get(encCookie)

        // XChaCha20-Poly1305 cookie decryption uses 24-byte nonce; store raw only if needed
        val cookieKey = WireGuardCrypto.blake2s(LABEL_COOKIE + peerStaticPub)
        // HChaCha20 subkey + 12-byte nonce
        val subNonce = ByteArray(12).apply { nonce24.copyInto(this, 4, 16, 24) }
        val decrypted = WireGuardCrypto.aeadDecrypt(cookieKey, subNonce, encCookie, lastMac1)
        if (decrypted != null && decrypted.size == 16) {
            lastCookie = decrypted
            return true
        }
        return false
    }

    fun encryptTransportPacket(ipPacket: ByteArray): ByteArray? {
        if (!isHandshakeComplete) return null
        val counter = sendCounter.getAndIncrement()
        // WireGuard pads IP payloads to a multiple of 16 bytes
        val paddedLen = if (ipPacket.isEmpty()) 0 else ((ipPacket.size + 15) / 16) * 16
        val padded = if (paddedLen == ipPacket.size) ipPacket else ipPacket.copyOf(paddedLen)

        val encrypted = WireGuardCrypto.aeadEncrypt(
            key = sendKey,
            nonce12 = WireGuardCrypto.nonceFromCounter(counter),
            plaintext = padded
        )

        return ByteBuffer.allocate(16 + encrypted.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(4.toByte()) // Type 4: Transport Data
            .put(0.toByte()).put(0.toByte()).put(0.toByte())
            .putInt(remoteSenderIndex)
            .putLong(counter)
            .put(encrypted)
            .array()
    }

    fun decryptTransportPacket(wgPacket: ByteArray): ByteArray? {
        if (!isHandshakeComplete || wgPacket.size < 32 || wgPacket[0].toInt() != 4) return null
        val bb = ByteBuffer.wrap(wgPacket).order(ByteOrder.LITTLE_ENDIAN)
        bb.get() // type 4
        bb.get(); bb.get(); bb.get()
        val receiverIndex = bb.int
        if (receiverIndex != localSenderIndex) return null
        val counter = bb.long
        val encrypted = ByteArray(wgPacket.size - 16)
        bb.get(encrypted)

        return WireGuardCrypto.aeadDecrypt(
            key = recvKey,
            nonce12 = WireGuardCrypto.nonceFromCounter(counter),
            ciphertextAndTag = encrypted
        )
    }
}
