package dev.devinknox.fivegtile

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import de.robv.android.xposed.XposedHelpers

@TargetApi(36)
@SuppressLint("MissingPermission") // 仅在 SystemUI 中运行，初始化时校验宿主权限。
internal class ColorOSFiveG(private val context: Context) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val radioAccessFamily = XposedHelpers.findClass(
        "android.telephony.RadioAccessFamily", context.classLoader
    )

    init {
        for (permission in arrayOf(Manifest.permission.MODIFY_PHONE_STATE,
            "android.permission.READ_PRIVILEGED_PHONE_STATE", Manifest.permission.WRITE_SECURE_SETTINGS)) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                "系统界面缺少权限：$permission"
            }
        }
    }

    fun isCapable(): Boolean =
        telephony.supportedRadioAccessFamily and TelephonyManager.NETWORK_TYPE_BITMASK_NR != 0L

    fun isEnabled(subscriptionId: Int): Boolean {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) return false
        val manager = telephony.createForSubscriptionId(subscriptionId)
        val mask = manager.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
        return mask and TelephonyManager.NETWORK_TYPE_BITMASK_NR != 0L
    }

    fun setEnabled(subscriptionId: Int, enabled: Boolean) {
        require(SubscriptionManager.isValidSubscriptionId(subscriptionId)) { "无有效默认上网卡" }
        val manager = telephony.createForSubscriptionId(subscriptionId)
        val current = manager.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
        // 只改变 NR 位，保留用户已有的其他网络选项和运营商等其他 reason 的限制。
        val next = if (enabled) current or TelephonyManager.NETWORK_TYPE_BITMASK_NR
            else current and TelephonyManager.NETWORK_TYPE_BITMASK_NR.inv()
        check(next != 0L) { "关闭 5G 后没有可用的网络类型" }
        val mode = XposedHelpers.callStaticMethod(radioAccessFamily, "getNetworkTypeFromRaf", next.toInt()) as Int
        check(mode >= 0) { "无法转换网络模式" }
        manager.setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, next)

        // 本机 TeleService 同时保存 networkMode 整数，供省电及恢复策略读取；这里不能写 bitmask。
        val resolver = context.contentResolver
        check(Settings.Global.putInt(resolver, "oplus_user_preferred_network_mode$subscriptionId", mode)) {
            "保存 5G 用户网络偏好失败"
        }
        val powerSaving = Settings.Global.getInt(resolver, "low_power", 0) == 1
        val disablesFiveG = Settings.Global.getInt(resolver, "power_save_disable_five_g_state", 0) == 1
        if (powerSaving && disablesFiveG) {
            check(Settings.Global.putInt(resolver, "oplus_power_save_user_preferred_network_mode$subscriptionId", mode)) {
                "保存省电模式网络偏好失败"
            }
        }
    }
}
