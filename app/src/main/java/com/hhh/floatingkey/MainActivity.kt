package com.hhh.floatingkey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= 31 && darkTheme -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestNotificationPermission.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onRequestNotificationPermission: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var imeEnabled by remember { mutableStateOf(false) }
    var imeSelected by remember { mutableStateOf(false) }
    var buttonVisible by remember { mutableStateOf(OverlayService.running) }
    var selectedKey by remember { mutableStateOf(Prefs.getKeyCode(context)) }
    var keyMenuExpanded by remember { mutableStateOf(false) }

    fun refreshStatuses() {
        overlayGranted = Settings.canDrawOverlays(context)
        val component = ComponentName(context, KeyImeService::class.java).flattenToString()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imeEnabled = imm.enabledInputMethodList.any { it.id == component }
        imeSelected = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) == component
        buttonVisible = OverlayService.running
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStatuses()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("悬浮按键") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("运行状态", style = MaterialTheme.typography.titleMedium)

                    StatusRow(
                        title = "悬浮窗权限",
                        status = if (overlayGranted) "已授予" else "未授予",
                        ok = overlayGranted
                    ) {
                        if (!overlayGranted) {
                            Button(onClick = { openOverlaySettings(context) }) {
                                Text("去授权")
                            }
                        }
                    }

                    StatusRow(
                        title = "输入法",
                        status = when {
                            imeSelected -> "已启用 · 使用中"
                            imeEnabled -> "已启用 · 未选中"
                            else -> "未启用"
                        },
                        ok = imeSelected
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                }
                            ) { Text("启用") }
                            OutlinedButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 28) {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SHOW_INPUT_METHOD_PICKER)
                                        )
                                    } else {
                                        context.startActivity(
                                            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                        )
                                    }
                                }
                            ) { Text("切换") }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("按钮按键", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "按下悬浮按钮时发送的键盘按键",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExposedDropdownMenuBox(
                        expanded = keyMenuExpanded,
                        onExpandedChange = { keyMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = Keys.labelFor(selectedKey),
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            trailingIcon = { Text("▼") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = keyMenuExpanded,
                            onDismissRequest = { keyMenuExpanded = false }
                        ) {
                            Keys.all.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.label) },
                                    onClick = {
                                        selectedKey = key.code
                                        Prefs.setKeyCode(context, key.code)
                                        keyMenuExpanded = false
                                        OverlayService.refresh(context)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("显示悬浮按钮", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (!imeSelected) "提示：需先启用并选择「悬浮按键输入法」"
                            else "点击按钮即可发送所选按键",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = buttonVisible,
                        onCheckedChange = { want ->
                            if (want) {
                                if (overlayGranted) {
                                    onRequestNotificationPermission()
                                    OverlayService.start(context)
                                    buttonVisible = true
                                } else {
                                    openOverlaySettings(context)
                                }
                            } else {
                                OverlayService.stop(context)
                                buttonVisible = false
                            }
                        }
                    )
                }
            }

            Text(
                text = "版本 ${versionName(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StatusRow(
    title: String,
    status: String,
    ok: Boolean,
    actions: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions
        )
    }
}

private fun openOverlaySettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    )
}

private fun versionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}
