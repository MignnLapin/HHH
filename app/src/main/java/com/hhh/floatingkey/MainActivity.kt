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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
    val clipboard = LocalClipboardManager.current

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var imeEnabled by remember { mutableStateOf(false) }
    var imeSelected by remember { mutableStateOf(false) }
    var buttonVisible by remember { mutableStateOf(OverlayService.running) }
    var selectedKey by remember { mutableStateOf(Prefs.getKeyCode(context)) }
    var keyMenuExpanded by remember { mutableStateOf(false) }

    var holdMode by remember { mutableStateOf(Prefs.isHoldMode(context)) }
    var autoSwitch by remember { mutableStateOf(Prefs.isAutoSwitchEnabled(context)) }
    var autoFocus by remember { mutableStateOf(Prefs.isAutoSwitchOnFocus(context)) }
    var fixedApps by remember { mutableStateOf(Prefs.getFixedApps(context)) }
    var accessibilityOn by remember { mutableStateOf(AutoSwitchService.isEnabled(context)) }
    var secureGranted by remember { mutableStateOf(ImeSwitcher.canSwitch(context)) }
    var showAppPicker by remember { mutableStateOf(false) }

    fun refreshStatuses() {
        overlayGranted = Settings.canDrawOverlays(context)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val ourId = ImeSwitcher.ourImeId(context)
        imeEnabled = imm.enabledInputMethodList.any { it.id == ourId }
        imeSelected = ImeSwitcher.currentImeId(context) == ourId
        buttonVisible = OverlayService.running
        accessibilityOn = AutoSwitchService.isEnabled(context)
        secureGranted = ImeSwitcher.canSwitch(context)
        holdMode = Prefs.isHoldMode(context)
        autoSwitch = Prefs.isAutoSwitchEnabled(context)
        autoFocus = Prefs.isAutoSwitchOnFocus(context)
        fixedApps = Prefs.getFixedApps(context)
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
                                        try {
                                            val imm = context.getSystemService(
                                                Context.INPUT_METHOD_SERVICE
                                            ) as InputMethodManager
                                            imm.showInputMethodPicker()
                                        } catch (e: SecurityException) {
                                            context.startActivity(
                                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                            )
                                        }
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

                    Text(
                        "按键方式",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !holdMode,
                            onClick = {
                                holdMode = false
                                Prefs.setHoldMode(context, false)
                                OverlayService.refresh(context)
                            },
                            label = { Text("单次按键") }
                        )
                        FilterChip(
                            selected = holdMode,
                            onClick = {
                                holdMode = true
                                Prefs.setHoldMode(context, true)
                                OverlayService.refresh(context)
                            },
                            label = { Text("按住切换") }
                        )
                    }
                    Text(
                        if (holdMode) "按第一次＝按住不放，再按一次＝松开"
                        else "每按一次发送一次完整的按下 + 抬起",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动切换输入法", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "自动切到「悬浮按键输入法」，离开后切回原输入法",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoSwitch,
                            onCheckedChange = {
                                autoSwitch = it
                                Prefs.setAutoSwitchEnabled(context, it)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("进入输入框时自动切换", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "焦点进入输入框时切换，键盘收起后切回",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoFocus,
                            enabled = autoSwitch,
                            onCheckedChange = {
                                autoFocus = it
                                Prefs.setAutoSwitchOnFocus(context, it)
                            }
                        )
                    }

                    StatusRow(
                        title = "无障碍服务",
                        status = if (accessibilityOn) "已开启" else "未开启（用于识别应用与输入框）",
                        ok = accessibilityOn
                    ) {
                        OutlinedButton(onClick = { openAccessibilitySettings(context) }) {
                            Text(if (accessibilityOn) "设置" else "去开启")
                        }
                    }

                    StatusRow(
                        title = "切换权限",
                        status = if (secureGranted) "已授权" else "未授权（需 adb 授权一次）",
                        ok = secureGranted
                    ) {
                        if (!secureGranted) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(ImeSwitcher.GRANT_COMMAND))
                                }
                            ) { Text("复制命令") }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("固定应用", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (fixedApps.isEmpty()) "未选择"
                                else "已选择 ${fixedApps.size} 个应用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { showAppPicker = true }) {
                            Text("选择应用")
                        }
                    }
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

    if (showAppPicker) {
        AppPickerDialog(
            selected = fixedApps,
            onDismiss = { showAppPicker = false },
            onConfirm = { newSelection ->
                fixedApps = newSelection
                Prefs.setFixedApps(context, newSelection)
                showAppPicker = false
            }
        )
    }
}

private data class InstalledApp(val label: String, val pkg: String)

@Composable
private fun AppPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    val apps = remember { loadLauncherApps(context) }
    val current = remember { mutableStateOf(selected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择固定应用") },
        text = {
            if (apps.isEmpty()) {
                Text("未找到可启动的应用")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.pkg }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = current.value.contains(app.pkg),
                                onCheckedChange = { checked ->
                                    current.value = if (checked) {
                                        current.value + app.pkg
                                    } else {
                                        current.value - app.pkg
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current.value) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun loadLauncherApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            InstalledApp(info.loadLabel(pm).toString(), pkg)
        }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun versionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}
