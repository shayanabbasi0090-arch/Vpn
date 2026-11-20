package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vpn.VpnNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app_name matches Sentinel VPN`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Sentinel VPN", appName)
    }

    @Test
    fun `notification helper builds foreground vpn notification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = VpnNotificationHelper.buildNotification(
            context = context,
            title = context.getString(R.string.vpn_notif_title_connected),
            contentText = "🇯🇵 Japan • 00:01:24 • ↓ 2.4 MB/s ↑ 410.0 KB/s",
            subText = "WireGuard GoBackend"
        )
        assertNotNull(notification)
        assertEquals(2, notification.actions.size)
    }
}
