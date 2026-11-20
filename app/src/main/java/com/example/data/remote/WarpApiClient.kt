package com.example.data.remote

import com.example.vpn.wg.WireGuardCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class ProvisionedWireGuardConfig(
    val configText: String,
    val assignedIpv4: String,
    val endpointHostPort: String
)

class WarpApiClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val warpRegUrls = listOf(
        "https://api.cloudflareclient.com/v0a2158/reg",
        "https://api.cloudflareclient.com/v0a2483/reg"
    )

    suspend fun registerWarpTunnel(
        dnsPrimaryIp: String = "1.1.1.1",
        preferredEndpointHostPort: String? = null
    ): Result<ProvisionedWireGuardConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val keyPair = WireGuardCrypto.generateKeyPair()
            val publicKeyBase64 = keyPair.publicKeyBase64
            val privateKeyBase64 = keyPair.privateKeyBase64

            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val payload = JSONObject().apply {
                put("key", publicKeyBase64)
                put("install_id", "")
                put("fcm_token", "")
                put("tos", isoFormat.format(Date()))
                put("model", "Android")
                put("serial_number", "")
                put("type", "Android")
                put("locale", "en_US")
            }

            var lastError: String = "WARP registration failed"
            for (url in warpRegUrls) {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json; charset=UTF-8")
                    .addHeader("CF-Client-Version", "a-6.30-3596")
                    .addHeader("User-Agent", "okhttp/3.12.1")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val parsed = runCatching {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError = "WARP API returned HTTP ${response.code}"
                            return@use null
                        }
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) {
                            lastError = "Empty response from WARP registration API"
                            return@use null
                        }

                        val root = JSONObject(body)
                        val configObj = root.getJSONObject("config")
                        val ifaceObj = configObj.getJSONObject("interface")
                        val addressesObj = ifaceObj.getJSONObject("addresses")
                        val clientV4 = addressesObj.optString("v4", "172.16.0.2").ifBlank { "172.16.0.2" }

                        val peersArray = configObj.getJSONArray("peers")
                        if (peersArray.length() == 0) {
                            lastError = "No WireGuard peers returned"
                            return@use null
                        }
                        val peerObj = peersArray.getJSONObject(0)
                        val peerPublicKey = peerObj.getString("public_key")
                        val endpointObj = peerObj.getJSONObject("endpoint")
                        val apiEndpoint = endpointObj.optString("v4", "162.159.192.1:2408")
                            .ifBlank { endpointObj.optString("host", "engage.cloudflareclient.com:2408") }

                        val finalEndpoint = preferredEndpointHostPort?.takeIf { it.isNotBlank() } ?: apiEndpoint

                        val wgConfig = buildString {
                            appendLine("[Interface]")
                            appendLine("PrivateKey = $privateKeyBase64")
                            appendLine("Address = $clientV4/32")
                            appendLine("DNS = $dnsPrimaryIp")
                            appendLine("MTU = 1280")
                            appendLine()
                            appendLine("[Peer]")
                            appendLine("PublicKey = $peerPublicKey")
                            appendLine("AllowedIPs = 0.0.0.0/0")
                            appendLine("Endpoint = $finalEndpoint")
                            appendLine("PersistentKeepalive = 25")
                        }

                        ProvisionedWireGuardConfig(
                            configText = wgConfig,
                            assignedIpv4 = "$clientV4/32",
                            endpointHostPort = finalEndpoint
                        )
                    }
                }.getOrNull()

                if (parsed != null) return@runCatching parsed
            }
            error(lastError)
        }
    }
}
