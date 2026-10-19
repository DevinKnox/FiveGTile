package dev.devinknox.fivegtile

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.widget.Switch
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

class FiveGTileHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != SYSTEM_UI || param.processName != SYSTEM_UI) return
        val profile = DeviceProfile.current()
        if (profile == null) {
            log("跳过未验证设备：${Build.DEVICE}/API ${Build.VERSION.SDK_INT}")
            return
        }
        if (profile == DeviceProfile.COLOROS) {
            hookColorOSBridge()
            return
        }
        val hooks = mutableListOf<XC_MethodHook.Unhook>()
        try {
            val tileClass = XposedHelpers.findClass(profile.tileClassName, param.classLoader)
            val factoryClass = XposedHelpers.findClass(profile.factoryClassName, param.classLoader)
            val iconClass = XposedHelpers.findClass(
                "com.android.systemui.qs.tileimpl.QSTileImpl\$DrawableIcon", param.classLoader
            )
            val managerClass = if (profile == DeviceProfile.MIUI)
                XposedHelpers.findClass("miui.telephony.TelephonyManager", param.classLoader) else null
            hookTileCallbacks(hooks, tileClass, profile)
            hookTileFactory(hooks, factoryClass, tileClass, managerClass, iconClass, profile)
            hookStockTileList(hooks, profile)
            log("Hook 安装完成：$profile，等待 SystemUI 创建应用")
        } catch (error: Throwable) {
            for (hook in hooks) hook.unhook()
            STOCK_IDS.clear()
            logError("本机 Hook 点不匹配，已撤回本次 Hook", error)
        }
    }

    private fun hookColorOSBridge() {
        // ColorOS 使用标准 TileService 展示磁贴，Hook 仅提供有权限的操作入口。
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(hook: MethodHookParam) {
                    val context = hook.args[0] as Context
                    if (context.packageName != SYSTEM_UI) return
                    try {
                        FiveGSettings.registerInSystemUi(context)
                        ColorOSTileBridge.registerInSystemUi(context)
                        log("ColorOS 标准磁贴操作入口已注册")
                    } catch (error: Throwable) {
                        logError("注册 ColorOS 标准磁贴入口失败", error)
                    }
                }
            })
    }

    private fun hookTileCallbacks(
        hooks: MutableList<XC_MethodHook.Unhook>, tileClass: Class<*>, profile: DeviceProfile
    ) {
        hookTile(hooks, tileClass, "isAvailable", false) { controller, _ -> controller.isCapable() }
        hookTile(hooks, tileClass, "getTileLabel", "5G") { _, _ -> "5G" }
        hookTile(hooks, tileClass, "getLongClickIntent", null) { controller, _ -> controller.settingsIntent() }
        hookTile(hooks, tileClass, "handleClick", null) { controller, _ ->
            controller.click()
            null
        }
        hookTile(hooks, tileClass, "handleSecondaryClick", null) { controller, _ ->
            controller.click()
            null
        }
        hookTile(hooks, tileClass, "handleUpdateState", null) { controller, hook ->
            controller.updateState(hook.args[0])
            null
        }
        hookTile(hooks, tileClass, "handleSetListening", null) { controller, hook ->
            // 父类已在调用此方法前管理监听生命周期；此处替换 NFC 的广播注册。
            controller.setListening(hook.args[0] as Boolean)
            null
        }
        if (profile == DeviceProfile.COLOROS) {
            hookTile(hooks, tileClass, "handleInitialize", null) { _, _ -> null }
            hookTile(hooks, tileClass, "getDefaultIcon", null) { controller, _ -> controller.icon }
        }
        val destroyClass = if (profile == DeviceProfile.MIUI) tileClass.superclass
            else declaringClass(tileClass, "handleDestroy")
        hooks.addAll(XposedBridge.hookAllMethods(destroyClass, "handleDestroy", object : XC_MethodHook() {
            override fun beforeHookedMethod(hook: MethodHookParam) {
                controllerOf(hook.thisObject)?.destroy()
                // 保留原始父类销毁流程，清理回调及生命周期。
            }

            override fun afterHookedMethod(hook: MethodHookParam) {
                // 父类销毁时还会调用 handleSetListening(false)，此后才能移除标记。
                XposedHelpers.removeAdditionalInstanceField(hook.thisObject, FIELD)
            }
        }))
    }

    private fun hookTileFactory(
        hooks: MutableList<XC_MethodHook.Unhook>,
        factoryClass: Class<*>,
        tileClass: Class<*>,
        managerClass: Class<*>?,
        iconClass: Class<*>,
        profile: DeviceProfile,
    ) {
        hooks.add(XposedHelpers.findAndHookMethod(factoryClass, "createTile", String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(hook: MethodHookParam) {
                    if (hook.args[0] != SPEC) return
                    hook.result = null
                    createFiveGTile(hook, tileClass, managerClass, iconClass, profile)
                }
            }))
    }

    private fun createFiveGTile(
        hook: XC_MethodHook.MethodHookParam,
        tileClass: Class<*>,
        managerClass: Class<*>?,
        iconClass: Class<*>,
        profile: DeviceProfile,
    ) {
        var ownedTile: Any? = null
        try {
            val provider = XposedHelpers.getObjectField(hook.thisObject, profile.nfcProviderField)
            val tile = XposedHelpers.callMethod(provider, "get")
            check(tileClass.isInstance(tile) && controllerOf(tile) == null
                && XposedHelpers.callMethod(tile, "getTileSpec") == null) {
                "磁贴 Provider 未返回独立实例"
            }
            val controller = Controller(tile, managerClass, iconClass, profile)
            XposedHelpers.setAdditionalInstanceField(tile, FIELD, controller)
            // 只有标记成功的独立实例才归模块管理，失败时才能销毁。
            ownedTile = tile
            XposedHelpers.callMethod(tile, "setTileSpec", SPEC)
            // 两份目标固件的工厂均使用 12 初始化、11 触发首次刷新。
            val handler = XposedHelpers.getObjectField(tile, "mHandler") as Handler
            handler.sendEmptyMessage(12)
            handler.sendEmptyMessage(11)
            hook.result = tile
            log("已创建 5G 磁贴")
        } catch (error: Throwable) {
            if (ownedTile != null) {
                try {
                    XposedHelpers.callMethod(ownedTile, "destroy")
                } catch (cleanupError: Throwable) {
                    logError("清理失败的磁贴", cleanupError)
                }
            }
            logError("创建 5G 磁贴失败", error)
        }
    }

    private fun hookStockTileList(hooks: MutableList<XC_MethodHook.Unhook>, profile: DeviceProfile) {
        // 插件通过宿主的 ResourcesHelper 读取此字符串，无需另行 Hook 插件类加载器。
        hooks.add(XposedHelpers.findAndHookMethod(Resources::class.java, "getString", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(hook: MethodHookParam) {
                    val id = hook.args[0] as Int
                    if (id !in STOCK_IDS) return
                    val stock = hook.result as? String ?: return
                    val resources = hook.thisObject as Resources
                    if (resources.getResourcePackageName(id) != SYSTEM_UI) return
                    if (SPEC !in stock.split(',')) hook.result = "$stock,$SPEC"
                }
            }))
        hooks.add(XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(hook: MethodHookParam) {
                    val context = hook.args[0] as Context
                    if (context.packageName != SYSTEM_UI) return
                    val resources = context.resources
                    for (name in arrayOf("miui_quick_settings_tiles_stock",
                        "miui_quick_settings_tiles_stock_pad", "quick_settings_tiles_stock")) {
                        val id = resources.getIdentifier(name, "string", SYSTEM_UI)
                        if (id != 0) STOCK_IDS.add(id)
                    }
                    if (profile == DeviceProfile.COLOROS && Build.VERSION.SDK_INT >= 34) {
                        FiveGSettings.registerInSystemUi(context)
                    }
                    log("模块已加载；已注册 5G 编辑列表，资源数量=${STOCK_IDS.size}")
                }
            }))
    }

    private fun hookTile(
        hooks: MutableList<XC_MethodHook.Unhook>,
        tileClass: Class<*>,
        method: String,
        fallback: Any?,
        callback: (Controller, XC_MethodHook.MethodHookParam) -> Any?
    ) {
        // 保留小米已验证的 Hook 层级，仅对一加查找父类声明的方法。
        val owner = if (tileClass.name == DeviceProfile.COLOROS.tileClassName) declaringClass(tileClass, method)
            else tileClass
        val added = XposedBridge.hookAllMethods(owner, method, object : XC_MethodHook() {
            override fun beforeHookedMethod(hook: MethodHookParam) {
                val controller = controllerOf(hook.thisObject) ?: return
                // 先阻止原 NFC 逻辑；即使 5G 接口异常，也不能回落成 NFC 操作。
                hook.result = fallback
                try {
                    hook.result = callback(controller, hook)
                } catch (error: Throwable) {
                    logError(method, error)
                    controller.lastError = "接口异常，请查看模块日志"
                    if (method == "handleUpdateState") {
                        XposedHelpers.setObjectField(hook.args[0], "label", "5G")
                        XposedHelpers.setIntField(hook.args[0], "state", 0)
                    }
                }
            }
        })
        check(added.isNotEmpty()) { "缺少 Hook 点：$method" }
        hooks.addAll(added)
    }

    private fun declaringClass(tileClass: Class<*>, method: String): Class<*> {
        var current: Class<*>? = tileClass
        while (current != null) {
            if (current.declaredMethods.any { it.name == method }) return current
            current = current.superclass
        }
        error("缺少 Hook 点：$method")
    }

    private class Controller(
        val tile: Any, managerClass: Class<*>?, iconClass: Class<*>, private val profile: DeviceProfile
    ) {
        private val context = XposedHelpers.getObjectField(tile, "mContext") as Context
        private val handler = XposedHelpers.getObjectField(tile, "mHandler") as Handler
        private val manager = managerClass?.let { XposedHelpers.callStaticMethod(it, "getDefault") }
        private val colorosNetwork = if (profile == DeviceProfile.COLOROS) ColorOSFiveG(context) else null
        val icon: Any = XposedHelpers.newInstance(
            iconClass, context.createPackageContext(PACKAGE, 0).getDrawable(R.drawable.ic_fiveg)
        )
        private val uiHandler = Handler(Looper.getMainLooper())
        private var listeningEnabled = false
        private var observerRegistered = false
        private var receiverRegistered = false
        private var pending = false
        private var target = false
        private var attempts = 0
        private var targetSlot = 0
        private var targetSubscription = 0
        var lastError: String? = null
        private val confirmation = Runnable { confirmChange() }
        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key = uri?.lastPathSegment
                if (key == null || key.startsWith("fiveg_user_enable") || key.startsWith("dual_nr_enabled")
                    || key.startsWith("preferred_network_mode") || key == "airplane_mode_on"
                    || key.startsWith("oplus_user_preferred_network_mode")
                    || key.startsWith("oplus_power_save_user_preferred_network_mode")
                    || key == "multi_sim_data_call") refresh()
            }
        }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context?, intent: Intent?) = refresh()
        }

        fun isCapable(): Boolean = colorosNetwork?.isCapable()
            ?: (XposedHelpers.callMethod(manager, "isFiveGCapable") == true)

        private fun isEnabled(): Boolean {
            if (colorosNetwork != null) {
                return colorosNetwork.isEnabled(SubscriptionManager.getDefaultDataSubscriptionId())
            }
            // 无参版本由本机 TelephonyManagerEx 转到默认上网卡 slotId。
            return XposedHelpers.callMethod(manager, "isUserFiveGEnabled") == true
        }

        private fun airplaneMode(): Boolean =
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

        private fun hasDataSim(): Boolean =
            SubscriptionManager.isValidSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())

        fun updateState(state: Any) {
            val enabled = isEnabled()
            val airplane = airplaneMode()
            val dataSim = hasDataSim()
            val subtitle = stateSubtitle(enabled, airplane, dataSim)
            val tileState = when {
                airplane || !dataSim -> 0
                enabled -> 2
                else -> 1
            }
            XposedHelpers.setObjectField(state, "label", "5G")
            XposedHelpers.setObjectField(state, "secondaryLabel", subtitle)
            XposedHelpers.setObjectField(state, "contentDescription", "5G，$subtitle")
            XposedHelpers.setObjectField(state, "stateDescription", subtitle)
            XposedHelpers.setObjectField(state, "expandedAccessibilityClassName", Switch::class.java.name)
            XposedHelpers.setObjectField(state, "icon", icon)
            XposedHelpers.setObjectField(state, "iconSupplier", null)
            XposedHelpers.setBooleanField(state, "value", enabled)
            XposedHelpers.setBooleanField(state, "isTransient", pending)
            XposedHelpers.setBooleanField(state, "handlesLongClick", true)
            XposedHelpers.setBooleanField(state, "dualTarget", false)
            XposedHelpers.setBooleanField(state, "disabledByPolicy", false)
            XposedHelpers.setIntField(state, "state", tileState)
            if (profile == DeviceProfile.COLOROS) {
                // OplusNfcTile.newTileState 会预置 NFC 动画，必须清除后使用模块矢量图标。
                XposedHelpers.setObjectField(state, "lottiePrefix", null)
                XposedHelpers.setIntField(state, "lottieSupport", 0)
                XposedHelpers.setBooleanField(state, "canTips", false)
            }
        }

        private fun stateSubtitle(enabled: Boolean, airplane: Boolean, dataSim: Boolean): String {
            val error = lastError
            return when {
                airplane -> "飞行模式"
                !dataSim -> "无默认上网卡"
                pending -> "切换中…"
                error != null -> error
                enabled -> "已开启"
                else -> "已关闭"
            }
        }

        fun click() {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (keyguard?.isKeyguardLocked == true) {
                try {
                    val starter = XposedHelpers.getObjectField(tile, "mActivityStarter")
                    XposedHelpers.callMethod(starter, "postQSRunnableDismissingKeyguard", Runnable {
                        handler.post {
                            try {
                                click()
                            } catch (error: Throwable) {
                                fail("切换失败，请查看模块日志", error)
                            }
                        }
                    })
                } catch (error: Throwable) {
                    fail("请解锁后重试", error)
                }
                return
            }
            if (pending || airplaneMode() || !hasDataSim()) return
            lastError = null
            targetSubscription = SubscriptionManager.getDefaultDataSubscriptionId()
            targetSlot = SubscriptionManager.getSlotIndex(targetSubscription)
            if (targetSlot < 0) return
            // 将一次操作绑定到点击时的卡槽，避免双卡切换期间操作到另一张卡。
            target = !isTargetEnabled()
            pending = true
            attempts = 0
            try {
                if (colorosNetwork != null) colorosNetwork.setEnabled(targetSubscription, target)
                else XposedHelpers.callMethod(manager, "setUserFiveGEnabled", target, targetSlot)
                // 使用真实设置回读确认；接口吞掉权限异常时也不会直接显示成功。
                handler.postDelayed(confirmation, 300)
            } catch (error: Throwable) {
                fail("切换失败，请查看模块日志", error)
            }
            refresh()
        }

        private fun confirmChange() {
            try {
                if (SubscriptionManager.getDefaultDataSubscriptionId() != targetSubscription) {
                    pending = false
                    log("默认上网卡已切换，结束上一张卡的状态确认")
                } else if (isTargetEnabled() == target) {
                    pending = false
                    lastError = null
                    log("5G 设置回读确认：$target")
                } else if (++attempts < 10) {
                    handler.postDelayed(confirmation, 400)
                } else {
                    fail("切换未生效，请在系统设置中检查", null)
                }
            } catch (error: Throwable) {
                fail("读取 5G 状态失败", error)
            }
            refresh()
        }

        private fun isTargetEnabled(): Boolean = colorosNetwork?.isEnabled(targetSubscription)
            ?: (XposedHelpers.callMethod(manager, "isUserFiveGEnabled", targetSlot) == true)

        fun setListening(enabled: Boolean) {
            if (listeningEnabled == enabled) return
            listeningEnabled = enabled
            if (!enabled) {
                unregister()
                return
            }
            try {
                context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
                observerRegistered = true
                val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
                    addAction("android.intent.action.SIM_STATE_CHANGED")
                }
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
                receiverRegistered = true
            } catch (error: Throwable) {
                unregister()
                logError("注册状态监听", error)
            }
            refresh()
        }

        private fun unregister() {
            if (observerRegistered) {
                try {
                    context.contentResolver.unregisterContentObserver(observer)
                } catch (error: Throwable) {
                    logError("注销状态观察器", error)
                }
                observerRegistered = false
            }
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (error: Throwable) {
                    logError("注销状态广播", error)
                }
                receiverRegistered = false
            }
        }

        fun destroy() {
            unregister()
            handler.removeCallbacks(confirmation)
        }

        fun settingsIntent(): Intent {
            var intent = FiveGSettings.intent(profile)
            if (intent.resolveActivity(context.packageManager) == null) {
                intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            }
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        private fun refresh() {
            XposedHelpers.callMethod(tile, "refreshState")
        }

        private fun fail(message: String, error: Throwable?) {
            pending = false
            lastError = message
            handler.removeCallbacks(confirmation)
            if (error != null) logError(message, error) else log(message)
            uiHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }

    private companion object {
        private const val PACKAGE = "dev.cheng.fivegtile"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SPEC = "cheng_5g"
        private const val FIELD = "$PACKAGE.controller"
        private val STOCK_IDS = ConcurrentHashMap.newKeySet<Int>()

        fun controllerOf(tile: Any): Controller? =
            XposedHelpers.getAdditionalInstanceField(tile, FIELD) as Controller?

        fun log(text: String) = XposedBridge.log("[FiveGTile] $text")

        fun logError(action: String, error: Throwable) {
            log("${action}：$error")
            XposedBridge.log(error)
        }
    }
}
