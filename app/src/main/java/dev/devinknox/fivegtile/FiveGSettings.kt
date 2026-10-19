package dev.devinknox.fivegtile

import android.annotation.TargetApi
import android.app.Activity
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.Log
import de.robv.android.xposed.XposedBridge

@TargetApi(34)
internal object FiveGSettings {
    private const val MODULE_PACKAGE = "dev.cheng.fivegtile"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val OPEN_ACTION = "$MODULE_PACKAGE.OPEN_FIVEG_SETTINGS"
    private const val PAGE_OPEN_FAILED = Activity.RESULT_FIRST_USER

    fun intent(profile: DeviceProfile): Intent = when (profile) {
        DeviceProfile.MIUI -> Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.android.phone", "com.android.phone.settings.MiuiFiveGNetworkSetting")
        )
        DeviceProfile.COLOROS -> {
            val subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId()
            check(SubscriptionManager.isValidSubscriptionId(subscriptionId)) { "无有效默认上网卡" }
            Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.android.phone", "com.android.simsettings.activity.OplusSimInfoActivity")
            ).putExtra("simid", subscriptionId)
        }
    }

    fun requestFromApp(context: Context, onUnavailable: (Int) -> Unit) {
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        context.sendOrderedBroadcast(
            Intent(OPEN_ACTION).setPackage(SYSTEM_UI).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            null,
            options,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (resultCode == Activity.RESULT_OK) return
                    // 默认结果表示入口未响应；只有 SystemUI 收到请求后才返回启动失败。
                    val message = if (resultCode == Activity.RESULT_CANCELED)
                        R.string.fiveg_module_not_loaded_message else R.string.fiveg_page_open_failed_message
                    Log.w("FiveGTile", "5G 页面请求未完成，result=$resultCode")
                    onUnavailable(message)
                }
            },
            Handler(Looper.getMainLooper()),
            Activity.RESULT_CANCELED,
            null,
            null,
        )
    }

    fun registerInSystemUi(context: Context) {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, request: Intent) {
                if (request.action != OPEN_ACTION) return
                try {
                    // 使用系统提供的发送方身份，拒绝其他应用及未共享身份的请求。
                    val moduleUid = context.packageManager.getPackageUid(MODULE_PACKAGE, 0)
                    if (sentFromUid != moduleUid || sentFromPackage != MODULE_PACKAGE) {
                        XposedBridge.log("[FiveGTile] 拒绝 5G 页面请求：发送方身份不匹配")
                        resultCode = PAGE_OPEN_FAILED
                        return
                    }
                    context.startActivity(intent(DeviceProfile.COLOROS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    resultCode = Activity.RESULT_OK
                } catch (error: Exception) {
                    XposedBridge.log("[FiveGTile] 打开 5G 页面失败：$error")
                    resultCode = PAGE_OPEN_FAILED
                }
            }
        }, IntentFilter(OPEN_ACTION), Context.RECEIVER_EXPORTED)
        XposedBridge.log("[FiveGTile] 5G 页面入口已注册")
    }
}
