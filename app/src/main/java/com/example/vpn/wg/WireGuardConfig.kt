package com.example.vpn.wg

data class ParsedWireGuardConfig(
    val privateKey: ByteArray,
    val addressCidr: String,
    val dnsServers: List<String>,
    val mtu: Int,
    val peerPublicKey: ByteArray,
    val presharedKey: ByteArray?,
    val endpointHost: String,
    val endpointPort: Int,
    val persistentKeepaliveSec: Int
) {
    val addressIp: String get() = addressCidr.substringBefore("/").ifBlank { "10.0.0.2" }
    val addressPrefix: Int get() = addressCidr.substringAfter("/", "32").toIntOrNull() ?: 32

    companion object {
        fun parse(configText: String): Result<ParsedWireGuardConfig> = runCatching {
            var section = ""
            var privKeyB64 = ""
            var address = "10.0.0.2/32"
            val dnsList = mutableListOf<String>()
            var mtu = 1280

            var peerPubB64 = ""
            var pskB64: String? = null
            var endpoint = ""
            var keepalive = 25

            for (rawLine in configText.lineSequence()) {
                val line = rawLine.substringBefore("#").trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.lowercase()
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()

                when (section) {
                    "[interface]" -> when (key) {
                        "privatekey" -> privKeyB64 = value
                        "address" -> address = value.split(",").first().trim()
                        "dns" -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { if (!it.contains(":")) dnsList.add(it) }
                        "mtu" -> mtu = value.toIntOrNull()?.coerceIn(1280, 1500) ?: 1280
                    }
                    "[peer]" -> when (key) {
                        "publickey" -> peerPubB64 = value
                        "presharedkey" -> pskB64 = value
                        "endpoint" -> endpoint = value
                        "persistentkeepalive" -> keepalive = value.toIntOrNull()?.coerceIn(5, 120) ?: 25
                    }
                }
            }

            require(privKeyB64.isNotBlank()) { "Missing [Interface] PrivateKey" }
            require(peerPubB64.isNotBlank()) { "Missing [Peer] PublicKey" }
            require(endpoint.contains(":")) { "Missing or invalid [Peer] Endpoint" }

            val privKey = WireGuardCrypto.fromBase64(privKeyB64)
            val peerPub = WireGuardCrypto.fromBase64(peerPubB64)
            require(privKey.size == 32) { "Invalid PrivateKey length" }
            require(peerPub.size == 32) { "Invalid PublicKey length" }

            val psk = pskB64?.takeIf { it.isNotBlank() }?.let {
                val decoded = WireGuardCrypto.fromBase64(it)
                require(decoded.size == 32) { "Invalid PresharedKey length" }
                decoded
            }

            val lastColon = endpoint.lastIndexOf(':')
            val host = endpoint.substring(0, lastColon).trim('[', ']')
            val port = endpoint.substring(lastColon + 1).toIntOrNull() ?: 2408

            ParsedWireGuardConfig(
                privateKey = privKey,
                addressCidr = address,
                dnsServers = dnsList.ifEmpty { listOf("1.1.1.1", "1.0.0.1") },
                mtu = mtu,
                peerPublicKey = peerPub,
                presharedKey = psk,
                endpointHost = host,
                endpointPort = port,
                persistentKeepaliveSec = keepalive
            )
        }
    }
}
