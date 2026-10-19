# 5G 快捷开关

针对 LSPosed / Xposed 模块。在 标题栏中新增独立「5G」磁贴，用户将其拖入已添加开关后即可操作。

当前代码版本为 **1.1.0 / versionCode 2**：采用已确认的 25dp MiSans C Semibold 图标；首页改为支持浅色、深色主题的卡片布局，型号与 Android 版本由系统动态读取；「进入5G页面」按钮直接打开系统 5G 设置。1.0.0 的模块功能已由用户真机确认有效；1.1.0 当前完成本地构建验证，尚未安装到设备。

首页及模块简介采用通用文案，方便后续沿用到其他机型；当前 Hook 和系统 5G 页面入口仍按下述固件适配，界面通用化不代表其他机型已支持。

## 适配范围与实测环境

2026-10-19 通过 ADB 只读取得：

| 项目         | 设备实际结果                                                                                |
|--------------|---------------------------------------------------------------------------------------------|
| 机型         | 小米 13，2211133C，代号 fuxi                                                                |
| Android      | 14，API 34                                                                                  |
| 系统版本     | MIUI V14.0.5.0.UMCCNXM，中国版                                                              |
| SystemUI     | com.android.systemui，20230316.0，versionCode 202303160                                     |
| 控制中心插件 | miui.systemui.plugin，14.0.1.66.1                                                           |
| 5G 设置页面  | com.android.phone/.settings.MiuiFiveGNetworkSetting，ADB 已确认可解析                       |
| 宿主权限     | SystemUI 的 MODIFY_PHONE_STATE、READ_PRIVILEGED_PHONE_STATE、WRITE_SECURE_SETTINGS 均已授予 |
| 初次读取设置 | fiveg_user_enable=0，dual_nr_enabled=0，airplane_mode_on=0                                  |

用户已确认设备 Root 且安装 LSPosed / 兼容框架。ADB shell 本身找不到 su，故没有通过 shell 提权进行设备修改。该模块在 SystemUI 内使用宿主现有权限，无需执行 su 命令。

代码限制在 fuxi / API 34 上加载，并检查本机所需 Hook 点。其他机型、HyperOS 和其他 Android 版本未验证。

## 安装与使用

1. 安装对应版本的模块 APK。
2. 在 LSPosed 中启用「5G 快捷开关」。
3. 作用域只勾选「系统界面」`com.android.systemui`，无需勾选系统框架、电话或所有应用。
4. 重启手机，使模块加载到 SystemUI。
5. 下拉控制中心，打开编辑开关，在「未添加开关」中找到 **5G**，拖到「已添加开关」，点击完成。

点击切换点击当时默认上网卡的 5G 用户设置；长按打开设备自带的 5G 设置页。飞行模式、没有默认上网卡时磁贴不可点击；锁屏操作先交给系统解锁流程。设置变化、切换默认上网卡及 SIM 状态变化会刷新显示。

打开模块首页，点击「进入5G页面」也会直接进入 `com.android.phone/.settings.MiuiFiveGNetworkSetting`。已读取本机 TeleService manifest 确认该 Activity 对外开放，未声明额外 Activity 权限；页面不可用时显示说明，不跳转到其他设置页。

「已开启」表示允许使用 5G，不代表当前位置已连接到 5G 基站。实际驻网仍受 SIM、套餐、运营商、覆盖及系统策略影响。

模块仅扩展可添加磁贴列表，不自动改写现有开关排列。关闭 5G 使用小米的用户设置接口；没有把网络模式强行改成 LTE-only。

## 实现与设备证据

主要代码：`app/src/main/java/dev/devinknox/fivegtile/FiveGTileHook.kt`；首页：同目录的 `MainActivity.kt`。

`handleLoadPackage` 负责设备检查与安装失败回滚，磁贴回调、工厂和编辑列表按职责安装；`updateState` 将副标题优先级集中到 `stateSubtitle`，避免嵌套条件。飞行模式、无默认上网卡、切换中、错误和开关状态的显示顺序保持一致。

- 本机 `MiuiQSFactory.createTile(String)` 通过 `nfcTileProvider.get()` 创建磁贴。模块为 `cheng_5g` 请求创建独立实例，用实例附加字段识别，再替换该实例的标签、可用性、点击、状态和监听行为。已有 NFC 实例不参与这些回调替换。
- 复用系统的布尔磁贴视图、布局与长按流程。图标来自模块自身的矢量资源，通过本机 `QSTileImpl.DrawableIcon(Drawable)` 交给 SystemUI。
- 控制中心插件的 `TileQueryHelper` 通过 `SystemUIResourcesHelper.getString("miui_quick_settings_tiles_stock")` 获取宿主开关列表。模块仅在 SystemUI 进程中扩展相关 stock 字符串，无需框架的资源替换功能，也无需额外 Hook 插件类加载器。
- 本机 `miui.telephony.TelephonyManager.getDefault()` 返回实际实现；`TelephonyManagerEx` 的无参读取使用默认上网卡槽。点击时先记录卡槽，再调用 `isUserFiveGEnabled(int)` 与 `setUserFiveGEnabled(boolean,int)`，经 `IMiuiTelephony` 转到系统电话服务。
- 切换后回读真实用户设置，最多等待约 4 秒。未生效时提示失败；不会先把界面直接改成成功状态。默认上网卡改变时结束旧卡的确认过程。
- 观察器与广播按磁贴监听生命周期注册，销毁时清理。异常不会回落执行原 NFC 点击操作。

实际 5G API 实现在另行提取的 `/system_ext/framework/miui-framework.jar` 中。没有将任何设备系统 APK、系统 JAR 或其他模块的实现打包进本模块。

## 构建

工程使用 Kotlin 2.3.10 与传统 Xposed API，兼容支持该 API 的 LSPosed。Kotlin 标准库由插件自动打包；Xposed API JAR 仅 `compileOnly`，不会进入 APK。无需额外引入 YukiHookAPI 或 KSP。

源码包名和资源 namespace 为 `dev.devinknox.fivegtile`，Xposed 入口与 Activity 类名对应此包；安装包 applicationId 为 `dev.cheng.fivegtile`，模块读取自身资源时使用 applicationId。

### Android Studio / Gradle

打开本目录，使用 JDK 17、Android SDK 34 与 Build Tools 34.0.0。工程带 Gradle 8.14.3 Wrapper，Android Gradle Plugin 为 8.11.2。

```powershell
.\gradlew.bat :app:assembleDebug
```

标准 Gradle 构建产物：`app/build/outputs/apk/debug/app-debug.apk`。

### PowerShell 构建入口

`build.ps1` 调用项目 Gradle Wrapper，统一完成 Kotlin 编译、标准库打包和 APK 调试签名。保留 SDK、JDK、构建目录、输出路径与调试证书参数；依赖未缓存时，首次构建需要联网下载，已有缓存时可传入 `-Offline`。

```powershell
.\build.ps1 `
    -SdkRoot 'C:\Users\CHENG\AppData\Local\Android\Sdk' `
    -JdkRoot 'C:\Server\Jdk\Jdk17.0.12'
```

输出为项目目录内的 `FiveGTile-1.1.0-debug.apk`。中间文件保留在 `.build`，脚本不执行删除操作。默认使用 Gradle 的 Android 调试证书；可通过 `-KeyStore` 指定已有调试证书（别名 `androiddebugkey`，密码 `android`）。重编译使用不同签名证书时无法直接覆盖已有安装，需要使用原证书重建。

## 验证结果与剩余步骤

- 已完成 Kotlin 编译、Android 资源编译和链接、DEX 打包与调试签名验证。
- 已核对目标固件的磁贴工厂、生命周期方法、状态字段、DrawableIcon 构造器与带卡槽参数的 5G API 签名。
- 已检查 APK 包名、minSdk/targetSdk、Xposed 入口、推荐作用域，确认 Xposed API 实现没有进入 DEX。
- APK 使用开发调试证书签名。
- **1.0.0：用户已确认模块在真机有效。1.1.0：完成本地构建与资源检查，新首页及直接跳转尚未在设备上安装验证。**

加载后在 LSPosed 日志搜索 `[FiveGTile]`，预期出现「Hook 安装完成」「模块已加载」和「已创建 5G 磁贴」。点击后若接口回读符合目标值，会记录「5G 设置回读确认」。若 Hook 点不匹配，模块撤回本次安装的 Hook 并记录异常。

如需移除，在控制中心编辑页移走 5G 磁贴，然后在 LSPosed 禁用模块并重启；普通开关排列由系统维护。

## 参考资料

- [LSPosed 模块作用域说明](https://github.com/LSPosed/LSPosed/wiki/Module-Scope)
- [Xposed API](https://api.xposed.info/reference/packages.html)
- [HyperCeiler 的 5G 磁贴与 TileUtils](https://github.com/ReChronoRain/HyperCeiler)：参考公开实现定位 MIUI 磁贴机制；本模块以设备提取文件为兼容性依据，未打包该项目代码。

第三方构建文件与 API 依赖的来源见 `THIRD_PARTY_NOTICES.txt`。
