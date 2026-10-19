package dev.devinknox.fivegtile

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

@TargetApi(34)
class FiveGTileService : TileService() {
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var observerRegistered = false
    private var pending = false
    private var requestId = 0
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val key = uri?.lastPathSegment ?: return
            if (key.startsWith("preferred_network_mode") || key.startsWith("oplus_user_preferred_network_mode")
                || key.startsWith("oplus_power_save_user_preferred_network_mode")
                || key == "airplane_mode_on" || key == "multi_sim_data_call") refresh()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onStartListening() {
        super.onStartListening()
        if (DeviceProfile.current() != DeviceProfile.COLOROS) return
        if (!listening) {
            try {
                contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
                observerRegistered = true
                registerReceiver(receiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
                    addAction("android.intent.action.SIM_STATE_CHANGED")
                }, RECEIVER_EXPORTED)
                listening = true
            } catch (error: RuntimeException) {
                stopListening()
                render(Tile.STATE_UNAVAILABLE, "无法监听状态，请重新打开控制中心")
                return
            }
        }
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (pending || DeviceProfile.current() != DeviceProfile.COLOROS) return
        unlockAndRun {
            if (pending) return@unlockAndRun
            pending = true
            render(Tile.STATE_UNAVAILABLE, getString(R.string.fiveg_tile_changing))
            request(toggle = true)
        }
    }

    override fun onStopListening() {
        stopListening()
        super.onStopListening()
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    private fun stopListening() {
        if (observerRegistered) {
            contentResolver.unregisterContentObserver(observer)
            observerRegistered = false
        }
        if (listening) {
            unregisterReceiver(receiver)
            listening = false
        }
    }

    private fun refresh() {
        if (listening && !pending) request(toggle = false)
    }

    private fun request(toggle: Boolean) {
        val id = ++requestId
        ColorOSTileBridge.request(this, toggle) callback@{ state ->
            if (toggle) pending = false
            if (!listening || id != requestId) return@callback
            val tileState = state?.getInt(ColorOSTileBridge.STATE, Tile.STATE_UNAVAILABLE) ?: Tile.STATE_UNAVAILABLE
            val subtitle = state?.getString(ColorOSTileBridge.SUBTITLE)
                ?: getString(R.string.fiveg_tile_module_unavailable)
            render(tileState, subtitle)
            if (toggle && tileState == Tile.STATE_UNAVAILABLE) {
                Toast.makeText(this, subtitle, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun render(state: Int, subtitle: String) {
        qsTile?.apply {
            label = getString(R.string.fiveg_tile_label)
            icon = Icon.createWithResource(this@FiveGTileService, R.drawable.ic_fiveg)
            this.state = state
            this.subtitle = subtitle
            contentDescription = "5G，$subtitle"
            updateTile()
        }
    }

    companion object {
        fun configure(context: Context) {
            // 只在 ColorOS 上启用应用磁贴，避免小米出现两个 5G 开关。
            val component = ComponentName(context, FiveGTileService::class.java)
            val desired = if (DeviceProfile.current() == DeviceProfile.COLOROS)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (context.packageManager.getComponentEnabledSetting(component) != desired) {
                context.packageManager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
