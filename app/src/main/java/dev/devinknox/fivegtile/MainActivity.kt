package dev.devinknox.fivegtile

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Compose 自己绘制内容，系统栏仍由 Activity 保持与原 XML 主题一致。
        window.statusBarColor = getColor(R.color.surface_window)
        window.navigationBarColor = getColor(R.color.surface_window)

        val deviceSummary = getString(R.string.device_summary, Build.MODEL, Build.VERSION.RELEASE)
        val version = try {
            getString(
                R.string.app_version,
                packageManager.getPackageInfo(packageName, 0).versionName,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            getString(R.string.app_name)
        }

        setContent {
            FiveGTileHome(
                deviceSummary = deviceSummary,
                version = version,
                onOpenFiveGPage = ::openFiveGPage,
            )
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

@Composable
private fun FiveGTileHome(
    deviceSummary: String,
    version: String,
    onOpenFiveGPage: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.surface_window))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colorResource(R.color.accent)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_fiveg),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = stringResource(R.string.app_name),
                        style = TextStyle(
                            color = colorResource(R.color.text_primary),
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.025).em,
                        ),
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(7.dp))
                    BasicText(
                        text = stringResource(R.string.home_subtitle),
                        style = TextStyle(
                            color = colorResource(R.color.text_secondary),
                            fontSize = 12.sp,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            BasicText(
                text = deviceSummary,
                style = TextStyle(
                    color = colorResource(R.color.text_tertiary),
                    fontSize = 12.sp,
                ),
            )

            Spacer(Modifier.height(26.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(colorResource(R.color.action_primary))
                    .clickable(onClick = onOpenFiveGPage)
                    .padding(start = 20.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = stringResource(R.string.enter_fiveg_page),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Image(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            BasicText(
                text = stringResource(R.string.enter_fiveg_hint),
                style = TextStyle(
                    color = colorResource(R.color.text_tertiary),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(30.dp))
            BasicText(
                text = stringResource(R.string.setup_title),
                style = TextStyle(
                    color = colorResource(R.color.text_secondary),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .semantics { heading() },
            )

            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colorResource(R.color.surface_card))
            ) {
                StepRow(1, stringResource(R.string.step_enable_title), stringResource(R.string.step_enable_body))
                StepDivider()
                StepRow(2, stringResource(R.string.step_scope_title), stringResource(R.string.step_scope_body))
                StepDivider()
                StepRow(3, stringResource(R.string.step_add_title), stringResource(R.string.step_add_body))
            }

            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colorResource(R.color.surface_card))
                    .padding(18.dp)
            ) {
                BasicText(
                    text = stringResource(R.string.usage_title),
                    style = TextStyle(
                        color = colorResource(R.color.text_primary),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = stringResource(R.string.usage_body),
                    style = TextStyle(
                        color = colorResource(R.color.text_secondary),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }

            Spacer(Modifier.height(24.dp))
            BasicText(
                text = version,
                style = TextStyle(
                    color = colorResource(R.color.text_tertiary),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepRow(number: Int, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colorResource(R.color.badge_background)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = number.toString(),
                style = TextStyle(
                    color = colorResource(R.color.badge_foreground),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = colorResource(R.color.text_primary),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(5.dp))
            BasicText(
                text = body,
                style = TextStyle(
                    color = colorResource(R.color.text_secondary),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
            )
        }
    }
}

@Composable
private fun StepDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(start = 57.dp, end = 16.dp)
            .background(colorResource(R.color.separator))
    )
}
