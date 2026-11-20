package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.SentinelApp
import com.example.ui.VpnViewModel
import com.example.ui.theme.SentinelVpnTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels {
        val app = application as SentinelApplication
        VpnViewModel.Factory(
            serverRepository = app.serverRepository,
            settingsRepository = app.settingsRepository,
            connectionManager = app.connectionManager
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SentinelVpnTheme {
                SentinelApp(viewModel = viewModel)
            }
        }
    }
}
