package com.example

import android.app.Application
import com.example.data.local.SettingsRepository
import com.example.data.probe.ServerPingProber
import com.example.data.remote.PublicVpnApiClient
import com.example.data.remote.WarpApiClient
import com.example.data.repository.VpnServerRepository
import com.example.vpn.VpnConnectionManager
import com.example.vpn.VpnNotificationHelper

class SentinelApplication : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var serverRepository: VpnServerRepository
        private set

    lateinit var connectionManager: VpnConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        VpnNotificationHelper.ensureChannel(this)

        settingsRepository = SettingsRepository(this)
        val publicApiClient = PublicVpnApiClient()
        val pingProber = ServerPingProber()
        val warpApiClient = WarpApiClient()

        serverRepository = VpnServerRepository(
            publicVpnApiClient = publicApiClient,
            pingProber = pingProber,
            settingsRepository = settingsRepository
        )

        connectionManager = VpnConnectionManager(
            context = this,
            serverRepository = serverRepository,
            settingsRepository = settingsRepository,
            warpApiClient = warpApiClient
        )
    }
}
