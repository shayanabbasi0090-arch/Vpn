package com.example.vpn.wg

import android.os.Build
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class WgKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    val privateKeyBase64: String get() = WireGuardCrypto.toBase64(privateKey)
    val publicKeyBase64: String get() = WireGuardCrypto.toBase64(publicKey)
}

object WireGuardCrypto {

    private val secureRandom = SecureRandom()

    fun toBase64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(b64: String): ByteArray =
        java.util.Base64.getDecoder().decode(b64.trim())

    fun generateKeyPair(): WgKeyPair {
        val priv = ByteArray(32)
        secureRandom.nextBytes(priv)
        clampX25519PrivateKey(priv)
        val pub = x25519PublicFromPrivate(priv)
        return WgKeyPair(priv, pub)
    }

    fun clampX25519PrivateKey(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
    }

    // --- RFC 7748 X25519 Scalar Multiplication ---
    private val P_25519 = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24_25519 = BigInteger.valueOf(121665)
    private val BASEPOINT_U = ByteArray(32).apply { this[0] = 9 }

    fun x25519PublicFromPrivate(privateKey: ByteArray): ByteArray =
        x25519(privateKey, BASEPOINT_U)

    fun x25519(scalarInput: ByteArray, uInput: ByteArray): ByteArray {
        val kBytes = scalarInput.copyOf(32)
        clampX25519PrivateKey(kBytes)
        val k = decodeLittleEndian255(kBytes, maskTopBit = false)

        val uMasked = uInput.copyOf(32)
        uMasked[31] = (uMasked[31].toInt() and 0x7F).toByte()
        val x1 = decodeLittleEndian255(uMasked, maskTopBit = false).mod(P_25519)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P_25519)
            val aa = a.multiply(a).mod(P_25519)
            val b = x2.subtract(z2).mod(P_25519)
            val bb = b.multiply(b).mod(P_25519)
            val e = aa.subtract(bb).mod(P_25519)
            val c = x3.add(z3).mod(P_25519)
            val d = x3.subtract(z3).mod(P_25519)
            val da = d.multiply(a).mod(P_25519)
            val cb = c.multiply(b).mod(P_25519)

            val sum = da.add(cb).mod(P_25519)
            x3 = sum.multiply(sum).mod(P_25519)
            val diff = da.subtract(cb).mod(P_25519)
            z3 = x1.multiply(diff.multiply(diff).mod(P_25519)).mod(P_25519)

            x2 = aa.multiply(bb).mod(P_25519)
            z2 = e.multiply(aa.add(A24_25519.multiply(e)).mod(P_25519)).mod(P_25519)
        }

        if (swap != 0) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        val z2Inv = z2.modPow(P_25519.subtract(BigInteger.TWO), P_25519)
        val result = x2.multiply(z2Inv).mod(P_25519)
        return encodeLittleEndian32(result)
    }

    private fun decodeLittleEndian255(bytes: ByteArray, maskTopBit: Boolean): BigInteger {
        val be = ByteArray(32)
        for (i in 0 until 32) {
            be[i] = bytes[31 - i]
        }
        if (maskTopBit) {
            be[0] = (be[0].toInt() and 0x7F).toByte()
        }
        return BigInteger(1, be)
    }

    private fun encodeLittleEndian32(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val beIdx = be.size - 1 - i
            out[i] = if (beIdx >= 0) be[beIdx] else 0
        }
        return out
    }

    // --- RFC 7693 BLAKE2s ---
    private val BLAKE2S_IV = intArrayOf(
        0x6A09E667,
        0xBB67AE85.toInt(),
        0x3C6EF372,
        0xA54FF53A.toInt(),
        0x510E527F,
        0x9B05688C.toInt(),
        0x1F83D9AB,
        0x5BE0CD19
    )

    private val BLAKE2S_SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
    )

    fun blake2s(
        input: ByteArray,
        key: ByteArray = ByteArray(0),
        outLen: Int = 32
    ): ByteArray {
        val h = BLAKE2S_IV.copyOf()
        h[0] = h[0] xor (0x01010000 or (key.size shl 8) or outLen)

        val paddedInput = if (key.isNotEmpty()) {
            val block = ByteArray(64)
            key.copyInto(block, 0, 0, key.size.coerceAtMost(64))
            block + input
        } else {
            input
        }

        var bytesCompressed = 0L
        var offset = 0
        val totalLen = paddedInput.size

        if (totalLen == 0) {
            val emptyBlock = ByteArray(64)
            blake2sCompress(h, emptyBlock, 0L, isLast = true)
        } else {
            while (offset < totalLen) {
                val remaining = totalLen - offset
                val chunkLen = remaining.coerceAtMost(64)
                val block = ByteArray(64)
                paddedInput.copyInto(block, 0, offset, offset + chunkLen)
                bytesCompressed += chunkLen
                offset += chunkLen
                val isLast = offset >= totalLen
                blake2sCompress(h, block, bytesCompressed, isLast)
            }
        }

        val out = ByteArray(outLen)
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        for (word in h) buf.putInt(word)
        buf.array().copyInto(out, 0, 0, outLen)
        return out
    }

    private fun blake2sCompress(h: IntArray, block: ByteArray, t: Long, isLast: Boolean) {
        val v = IntArray(16)
        val m = IntArray(16)
        for (i in 0 until 8) {
            v[i] = h[i]
            v[i + 8] = BLAKE2S_IV[i]
        }
        v[12] = v[12] xor (t and 0xFFFFFFFFL).toInt()
        v[13] = v[13] xor (t ushr 32).toInt()
        if (isLast) {
            v[14] = v[14] xor -1
        }

        val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 16) {
            m[i] = bb.int
        }

        for (r in 0 until 10) {
            val s = BLAKE2S_SIGMA[r]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        for (i in 0 until 8) {
            h[i] = h[i] xor v[i] xor v[i + 8]
        }
    }

    private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
        v[a] = v[a] + v[b] + x
        v[d] = Integer.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 12)
        v[a] = v[a] + v[b] + y
        v[d] = Integer.rotateRight(v[d] xor v[a], 8)
        v[c] = v[c] + v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 7)
    }

    // --- WireGuard HKDF (HMAC-BLAKE2s) ---
    fun hmacBlake2s(key: ByteArray, input: ByteArray): ByteArray {
        val k = if (key.size > 64) blake2s(key) else key
        val iPad = ByteArray(64) { idx -> ((if (idx < k.size) k[idx].toInt() else 0) xor 0x36).toByte() }
        val oPad = ByteArray(64) { idx -> ((if (idx < k.size) k[idx].toInt() else 0) xor 0x5C).toByte() }
        val inner = blake2s(iPad + input)
        return blake2s(oPad + inner)
    }

    fun kdf1(key: ByteArray, input: ByteArray): ByteArray {
        val prk = hmacBlake2s(key, input)
        return hmacBlake2s(prk, byteArrayOf(0x01))
    }

    fun kdf2(key: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hmacBlake2s(key, input)
        val t1 = hmacBlake2s(prk, byteArrayOf(0x01))
        val t2 = hmacBlake2s(prk, t1 + byteArrayOf(0x02))
        return t1 to t2
    }

    fun kdf3(key: ByteArray, input: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val prk = hmacBlake2s(key, input)
        val t1 = hmacBlake2s(prk, byteArrayOf(0x01))
        val t2 = hmacBlake2s(prk, t1 + byteArrayOf(0x02))
        val t3 = hmacBlake2s(prk, t2 + byteArrayOf(0x03))
        return Triple(t1, t2, t3)
    }

    // --- TAI64N Timestamp ---
    fun tai64nNow(): ByteArray {
        val nowMillis = System.currentTimeMillis()
        val seconds = (nowMillis / 1000L) + 0x400000000000000aL
        val nanos = ((nowMillis % 1000L) * 1_000_000L).toInt()
        return ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putLong(seconds)
            .putInt(nanos)
            .array()
    }

    // --- RFC 8439 ChaCha20-Poly1305 AEAD ---
    fun aeadEncrypt(
        key: ByteArray,
        nonce12: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val nativeRes = runCatching {
                val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce12))
                if (aad.isNotEmpty()) cipher.updateAAD(aad)
                cipher.doFinal(plaintext)
            }.getOrNull()
            if (nativeRes != null) return nativeRes
        }
        return pureKotlinChaCha20Poly1305Encrypt(key, nonce12, plaintext, aad)
    }

    fun aeadDecrypt(
        key: ByteArray,
        nonce12: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray? {
        if (ciphertextAndTag.size < 16) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val nativeRes = runCatching {
                val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce12))
                if (aad.isNotEmpty()) cipher.updateAAD(aad)
                cipher.doFinal(ciphertextAndTag)
            }.getOrNull()
            if (nativeRes != null) return nativeRes
        }
        return pureKotlinChaCha20Poly1305Decrypt(key, nonce12, ciphertextAndTag, aad)
    }

    fun nonceFromCounter(counter: Long): ByteArray {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)
            .putLong(counter)
            .array()
    }

    // Pure-Kotlin RFC 8439 ChaCha20-Poly1305 (for API 24-27 and JVM tests)
    fun pureKotlinChaCha20Poly1305Encrypt(
        key: ByteArray,
        nonce12: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val otk = chacha20Block(key, 0, nonce12).copyOfRange(0, 32)
        val ciphertext = chacha20Xor(key, 1, nonce12, plaintext)
        val tag = poly1305Mac(otk, buildPoly1305AuthData(aad, ciphertext))
        return ciphertext + tag
    }

    fun pureKotlinChaCha20Poly1305Decrypt(
        key: ByteArray,
        nonce12: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray
    ): ByteArray? {
        if (ciphertextAndTag.size < 16) return null
        val ctLen = ciphertextAndTag.size - 16
        val ciphertext = ciphertextAndTag.copyOfRange(0, ctLen)
        val tag = ciphertextAndTag.copyOfRange(ctLen, ciphertextAndTag.size)
        val otk = chacha20Block(key, 0, nonce12).copyOfRange(0, 32)
        val expectedTag = poly1305Mac(otk, buildPoly1305AuthData(aad, ciphertext))
        if (!constantTimeEquals(tag, expectedTag)) return null
        return chacha20Xor(key, 1, nonce12, ciphertext)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun buildPoly1305AuthData(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val aadPad = (16 - (aad.size % 16)) % 16
        val ctPad = (16 - (ciphertext.size % 16)) % 16
        val buf = ByteBuffer.allocate(aad.size + aadPad + ciphertext.size + ctPad + 16)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(aad)
        repeat(aadPad) { buf.put(0) }
        buf.put(ciphertext)
        repeat(ctPad) { buf.put(0) }
        buf.putLong(aad.size.toLong())
        buf.putLong(ciphertext.size.toLong())
        return buf.array()
    }

    private fun chacha20Xor(
        key: ByteArray,
        initialCounter: Int,
        nonce12: ByteArray,
        input: ByteArray
    ): ByteArray {
        val out = ByteArray(input.size)
        var counter = initialCounter
        var offset = 0
        while (offset < input.size) {
            val keyStream = chacha20Block(key, counter++, nonce12)
            val blockLen = (input.size - offset).coerceAtMost(64)
            for (i in 0 until blockLen) {
                out[offset + i] = (input[offset + i].toInt() xor keyStream[i].toInt()).toByte()
            }
            offset += blockLen
        }
        return out
    }

    private fun chacha20Block(key: ByteArray, counter: Int, nonce12: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        val kb = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 8) state[4 + i] = kb.int
        state[12] = counter
        val nb = ByteBuffer.wrap(nonce12).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 3) state[13 + i] = nb.int

        val working = state.copyOf()
        repeat(10) {
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }
        val out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 16) out.putInt(working[i] + state[i])
        return out.array()
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private val POLY_P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))

    private fun poly1305Mac(otk32: ByteArray, msg: ByteArray): ByteArray {
        val rBytes = otk32.copyOfRange(0, 16)
        rBytes[3] = (rBytes[3].toInt() and 15).toByte()
        rBytes[7] = (rBytes[7].toInt() and 15).toByte()
        rBytes[11] = (rBytes[11].toInt() and 15).toByte()
        rBytes[15] = (rBytes[15].toInt() and 15).toByte()
        rBytes[4] = (rBytes[4].toInt() and 252).toByte()
        rBytes[8] = (rBytes[8].toInt() and 252).toByte()
        rBytes[12] = (rBytes[12].toInt() and 252).toByte()

        val r = decodeLittleEndianVar(rBytes)
        val s = decodeLittleEndianVar(otk32.copyOfRange(16, 32))

        var acc = BigInteger.ZERO
        var offset = 0
        while (offset < msg.size) {
            val len = (msg.size - offset).coerceAtMost(16)
            val block = ByteArray(len + 1)
            msg.copyInto(block, 0, offset, offset + len)
            block[len] = 1
            val n = decodeLittleEndianVar(block)
            acc = acc.add(n).multiply(r).mod(POLY_P)
            offset += len
        }
        acc = acc.add(s)
        val out = ByteArray(16)
        val be = acc.toByteArray()
        for (i in 0 until 16) {
            val beIdx = be.size - 1 - i
            out[i] = if (beIdx >= 0) be[beIdx] else 0
        }
        return out
    }

    private fun decodeLittleEndianVar(le: ByteArray): BigInteger {
        val be = ByteArray(le.size)
        for (i in le.indices) {
            be[i] = le[le.size - 1 - i]
        }
        return BigInteger(1, be)
    }
}
