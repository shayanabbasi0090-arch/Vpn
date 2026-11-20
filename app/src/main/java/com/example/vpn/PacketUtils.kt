package com.example.vpn

import java.net.InetAddress
import java.nio.ByteBuffer

object PacketUtils {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    data class Ipv4Header(
        val version: Int,
        val headerLength: Int,
        val totalLength: Int,
        val identification: Int,
        val protocol: Int,
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray
    ) {
        val sourceIpString: String get() = InetAddress.getByAddress(sourceAddress).hostAddress ?: "0.0.0.0"
        val destIpString: String get() = InetAddress.getByAddress(destinationAddress).hostAddress ?: "0.0.0.0"
    }

    fun parseIpv4Header(packet: ByteArray, length: Int): Ipv4Header? {
        if (length < 20) return null
        val versionAndIhl = packet[0].toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != 4) return null
        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null

        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (totalLength < ihl || totalLength > length) return null

        val identification = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val protocol = packet[9].toInt() and 0xFF
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)

        return Ipv4Header(
            version = version,
            headerLength = ihl,
            totalLength = totalLength,
            identification = identification,
            protocol = protocol,
            sourceAddress = src,
            destinationAddress = dst
        )
    }

    fun readPort(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    fun readUInt32(packet: ByteArray, offset: Int): Long {
        return ((packet[offset].toLong() and 0xFF) shl 24) or
            ((packet[offset + 1].toLong() and 0xFF) shl 16) or
            ((packet[offset + 2].toLong() and 0xFF) shl 8) or
            (packet[offset + 3].toLong() and 0xFF)
    }

    fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val buffer = ByteBuffer.allocate(totalLen)

        // IPv4 Header (20 bytes)
        buffer.put(0x45.toByte()) // Version 4, IHL 5
        buffer.put(0x00.toByte()) // TOS
        buffer.putShort(totalLen.toShort())
        buffer.putShort(0) // ID
        buffer.putShort(0x4000.toShort()) // Don't fragment
        buffer.put(64.toByte()) // TTL
        buffer.put(PROTO_UDP.toByte())
        buffer.putShort(0) // Checksum placeholder
        buffer.put(srcIp)
        buffer.put(dstIp)

        // UDP Header (8 bytes)
        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort(udpLen.toShort())
        buffer.putShort(0) // UDP checksum placeholder
        buffer.put(payload)

        val bytes = buffer.array()
        writeIpChecksum(bytes, 20)
        writeTransportChecksum(bytes, 20, udpLen, PROTO_UDP, srcIp, dstIp)
        return bytes
    }

    fun buildIpv4TcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        windowSize: Int = 65535,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val tcpHeaderLen = 20
        val tcpTotalLen = tcpHeaderLen + payload.size
        val totalLen = 20 + tcpTotalLen
        val buffer = ByteBuffer.allocate(totalLen)

        // IPv4 Header
        buffer.put(0x45.toByte())
        buffer.put(0x00.toByte())
        buffer.putShort(totalLen.toShort())
        buffer.putShort(0)
        buffer.putShort(0x4000.toShort())
        buffer.put(64.toByte())
        buffer.put(PROTO_TCP.toByte())
        buffer.putShort(0)
        buffer.put(srcIp)
        buffer.put(dstIp)

        // TCP Header
        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putInt(seqNum.toInt())
        buffer.putInt(ackNum.toInt())
        buffer.put(((tcpHeaderLen / 4) shl 4).toByte()) // Data offset = 5
        buffer.put(flags.toByte())
        buffer.putShort(windowSize.toShort())
        buffer.putShort(0) // Checksum placeholder
        buffer.putShort(0) // Urgent pointer
        if (payload.isNotEmpty()) {
            buffer.put(payload)
        }

        val bytes = buffer.array()
        writeIpChecksum(bytes, 20)
        writeTransportChecksum(bytes, 20, tcpTotalLen, PROTO_TCP, srcIp, dstIp)
        return bytes
    }

    private fun writeIpChecksum(packet: ByteArray, ihl: Int) {
        packet[10] = 0
        packet[11] = 0
        var sum = 0L
        var i = 0
        while (i < ihl) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val checksum = sum.inv().toInt() and 0xFFFF
        packet[10] = (checksum ushr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }

    private fun writeTransportChecksum(
        packet: ByteArray,
        transportOffset: Int,
        transportLen: Int,
        protocol: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ) {
        val checksumOffset = if (protocol == PROTO_TCP) transportOffset + 16 else transportOffset + 6
        packet[checksumOffset] = 0
        packet[checksumOffset + 1] = 0

        var sum = 0L
        // Pseudo-header
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += protocol.toLong()
        sum += transportLen.toLong()

        var i = transportOffset
        val end = transportOffset + transportLen
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        var checksum = sum.inv().toInt() and 0xFFFF
        if (protocol == PROTO_UDP && checksum == 0) {
            checksum = 0xFFFF
        }
        packet[checksumOffset] = (checksum ushr 8).toByte()
        packet[checksumOffset + 1] = (checksum and 0xFF).toByte()
    }
}
