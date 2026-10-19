package dev.devinknox.fivegtile

import android.os.Build

internal enum class DeviceProfile(
    val tileClassName: String,
    val factoryClassName: String,
    val nfcProviderField: String,
) {
    MIUI(
        "com.android.systemui.qs.tiles.MiuiNfcTile",
        "com.android.systemui.qs.tileimpl.MiuiQSFactory",
        "nfcTileProvider",
    ),
    COLOROS(
        "com.oplus.systemui.qs.tiles.OplusNfcTile",
        "com.oplus.systemui.qs.tileimpl.OplusQSFactoryImpl",
        "mNfcTileProvider",
    );

    companion object {
        fun current(): DeviceProfile? = when {
            Build.DEVICE == "fuxi" && Build.VERSION.SDK_INT == 34 -> MIUI
            Build.DEVICE == "OP60FFL1" && Build.VERSION.SDK_INT == 36 -> COLOROS
            else -> null
        }
    }
}
