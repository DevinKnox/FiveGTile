package dev.devinknox.fivegtile

import android.annotation.TargetApi
import android.app.Activity
import android.app.BroadcastOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.telephony.SubscriptionManager
import android.util.Log

/** 标准磁贴运行在应用中；电话接口只在持有系统权限的 SystemUI 中调用。 */
@TargetApi(34)
internal object ColorOSTileBridge {
    private const val MODULE_PACKAGE = "dev.cheng.fivegtile"
    private const val ACTION = "$MODULE_PACKAGE.TILE_REQUEST"
    private const val TOGGLE = "toggle"
    private const val SUBSCRIPTION = "subscription"
    const val STATE = "state"
    const val SUBTITLE = "subtitle"

    fun request(context: Context, toggle: Boolean, onResult: (Bundle?) -> Unit) {
        context.sendOrderedBroadcast(
            Intent(ACTION).setPackage("com.android.systemui")
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra(TOGGLE, toggle),
            null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    onResult(if (resultCode == Activity.RESULT_OK) getResultExtras(false) else null)
                }
            },
            Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null,
        )
    }

    fun registerInSystemUi(context: Context) {
        // 电话 Binder 调用和回读不阻塞控制中心的主线程。
        val worker = HandlerThread("FiveGTile").apply { start() }
        val handler = Handler(worker.looper)
        val network by lazy(LazyThreadSafetyMode.NONE) { ColorOSFiveG(context) }
        var changing = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION) return
                val moduleUid = runCatching { context.packageManager.getPackageUid(MODULE_PACKAGE, 0) }
                    .getOrElse { return }
                if (sentFromUid != moduleUid || sentFromPackage != MODULE_PACKAGE) return
                val response = goAsync()
                try {
                    val state = readState(context, network, changing)
                    if (!intent.getBooleanExtra(TOGGLE, false) || state.getInt(STATE) == Tile.STATE_UNAVAILABLE) {
                        reply(response, state)
                        return
                    }
                    if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                        reply(response, unavailable("请解锁后重试"))
                        return
                    }
                    // 使用读取状态时的同一张卡，不能把旧卡的目标状态应用到新默认卡。
                    val subscription = state.getInt(SUBSCRIPTION)
                    if (subscription != SubscriptionManager.getDefaultDataSubscriptionId()) {
                        reply(response, unavailable("默认上网卡已切换"))
                        return
                    }
                    val target = state.getInt(STATE) != Tile.STATE_ACTIVE
                    changing = true
                    network.setEnabled(subscription, target)
                    confirmChange(context, network, handler, subscription, target, response) { changing = false }
                } catch (error: Exception) {
                    changing = false
                    Log.e("FiveGTile", "标准磁贴操作失败", error)
                    reply(response, unavailable("操作失败，请查看模块日志"))
                }
            }
        }
        try {
            context.registerReceiver(receiver, IntentFilter(ACTION), null, handler, Context.RECEIVER_EXPORTED)
        } catch (error: Exception) {
            worker.quitSafely()
            throw error
        }
    }

    private fun readState(context: Context, network: ColorOSFiveG, changing: Boolean): Bundle {
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
            return unavailable("飞行模式")
        }
        val subscription = SubscriptionManager.getDefaultDataSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subscription)) return unavailable("无默认上网卡")
        if (!network.isCapable()) return unavailable("设备不支持 5G")
        if (changing) return unavailable("切换中…")
        val enabled = network.isEnabled(subscription)
        return Bundle().apply {
            putInt(SUBSCRIPTION, subscription)
            putInt(STATE, if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
            putString(SUBTITLE, if (enabled) "已开启" else "已关闭")
        }
    }

    private fun confirmChange(
        context: Context, network: ColorOSFiveG, handler: Handler, subscription: Int,
        target: Boolean, response: BroadcastReceiver.PendingResult, onFinished: () -> Unit,
    ) {
        var attempts = 0
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val current = SubscriptionManager.getDefaultDataSubscriptionId()
                    when {
                        current != subscription -> finish("默认上网卡已切换")
                        network.isEnabled(subscription) == target -> finish(null)
                        ++attempts < 10 -> handler.postDelayed(this, 400)
                        else -> finish("切换未生效，请在系统设置中检查")
                    }
                } catch (error: Exception) {
                    Log.e("FiveGTile", "回读 5G 状态失败", error)
                    finish("读取 5G 状态失败")
                }
            }

            private fun finish(error: String?) {
                onFinished()
                val state = if (error == null) readState(context, network, false) else unavailable(error)
                reply(response, state)
            }
        }, 300)
    }

    private fun unavailable(subtitle: String) = Bundle().apply {
        putInt(STATE, Tile.STATE_UNAVAILABLE)
        putString(SUBTITLE, subtitle)
    }

    private fun reply(response: BroadcastReceiver.PendingResult, state: Bundle) {
        response.resultCode = Activity.RESULT_OK
        response.setResultExtras(state)
        response.finish()
    }
}
