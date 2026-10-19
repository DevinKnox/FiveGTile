package dev.devinknox.fivegtile

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.device_summary).text =
            getString(R.string.device_summary, Build.MODEL, Build.VERSION.RELEASE)
        findViewById<View>(R.id.open_fiveg).setOnClickListener { openFiveGPage() }
        try {
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.version).text = getString(R.string.app_version, version)
        } catch (_: PackageManager.NameNotFoundException) {
            // 当前应用的版本不存在时，保留布局中的应用名称。
        }
    }

    private fun openFiveGPage() {
        // 本机 TeleService manifest 已确认该页面 exported=true，无额外 Activity 权限。
        val intent = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.android.phone", "com.android.phone.settings.MiuiFiveGNetworkSetting")
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showPageUnavailable()
        } catch (_: SecurityException) {
            showPageUnavailable()
        }
    }

    private fun showPageUnavailable() {
        AlertDialog.Builder(this)
            .setTitle(R.string.fiveg_page_unavailable_title)
            .setMessage(R.string.fiveg_page_unavailable_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
