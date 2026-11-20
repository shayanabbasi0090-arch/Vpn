package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.DnsProvider
import com.example.domain.model.VpnServer
import com.example.domain.model.VpnSettings
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sentinelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sentinel_vpn_prefs"
)

class SettingsRepository(private val context: Context) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val serverListType = Types.newParameterizedType(List::class.java, VpnServer::class.java)
    private val serverListAdapter = moshi.adapter<List<VpnServer>>(serverListType)

    private object Keys {
        val AUTO_FAST_SWITCH = booleanPreferencesKey("auto_fast_switch")
        val LATENCY_THRESHOLD = intPreferencesKey("latency_threshold_ms")
        val PREFER_WIREGUARD = booleanPreferencesKey("prefer_wireguard")
        val DNS_PROVIDER = stringPreferencesKey("dns_provider")
        val EXCLUDE_LAN = booleanPreferencesKey("exclude_lan")
        val CUSTOM_WG_CONFIG = stringPreferencesKey("custom_wg_config")
        val SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
        val FAVORITE_IDS = stringSetPreferencesKey("favorite_server_ids")
        val CACHED_SERVERS_JSON = stringPreferencesKey("cached_servers_json")
    }

    val settingsFlow: Flow<VpnSettings> = context.sentinelDataStore.data.map { prefs ->
        val dnsName = prefs[Keys.DNS_PROVIDER] ?: DnsProvider.CLOUDFLARE.name
        val dns = DnsProvider.entries.firstOrNull { it.name == dnsName } ?: DnsProvider.CLOUDFLARE
        VpnSettings(
            autoFastSwitchEnabled = prefs[Keys.AUTO_FAST_SWITCH] ?: true,
            latencyThresholdMs = prefs[Keys.LATENCY_THRESHOLD] ?: 220,
            preferWireGuardEngine = prefs[Keys.PREFER_WIREGUARD] ?: true,
            dnsProvider = dns,
            splitTunnelExcludeLan = prefs[Keys.EXCLUDE_LAN] ?: true,
            customWireGuardConfig = prefs[Keys.CUSTOM_WG_CONFIG].orEmpty()
        )
    }

    val selectedServerIdFlow: Flow<String> = context.sentinelDataStore.data.map { prefs ->
        prefs[Keys.SELECTED_SERVER_ID] ?: VpnServer.SMART_AUTO_ID
    }

    val favoriteIdsFlow: Flow<Set<String>> = context.sentinelDataStore.data.map { prefs ->
        prefs[Keys.FAVORITE_IDS] ?: emptySet()
    }

    suspend fun updateSettings(transform: (VpnSettings) -> VpnSettings) {
        val current = settingsFlow.first()
        val updated = transform(current)
        context.sentinelDataStore.edit { prefs ->
            prefs[Keys.AUTO_FAST_SWITCH] = updated.autoFastSwitchEnabled
            prefs[Keys.LATENCY_THRESHOLD] = updated.latencyThresholdMs
            prefs[Keys.PREFER_WIREGUARD] = updated.preferWireGuardEngine
            prefs[Keys.DNS_PROVIDER] = updated.dnsProvider.name
            prefs[Keys.EXCLUDE_LAN] = updated.splitTunnelExcludeLan
            prefs[Keys.CUSTOM_WG_CONFIG] = updated.customWireGuardConfig
        }
    }

    suspend fun setSelectedServerId(serverId: String) {
        context.sentinelDataStore.edit { prefs ->
            prefs[Keys.SELECTED_SERVER_ID] = serverId
        }
    }

    suspend fun toggleFavorite(serverId: String) {
        context.sentinelDataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_IDS] ?: emptySet()
            prefs[Keys.FAVORITE_IDS] = if (serverId in current) current - serverId else current + serverId
        }
    }

    suspend fun saveCachedServers(servers: List<VpnServer>) {
        val json = runCatching { serverListAdapter.toJson(servers) }.getOrNull() ?: return
        context.sentinelDataStore.edit { prefs ->
            prefs[Keys.CACHED_SERVERS_JSON] = json
        }
    }

    suspend fun loadCachedServers(): List<VpnServer> {
        val json = context.sentinelDataStore.data.first()[Keys.CACHED_SERVERS_JSON] ?: return emptyList()
        return runCatching { serverListAdapter.fromJson(json) }.getOrNull() ?: emptyList()
    }
}
