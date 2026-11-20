package com.hhh.floatingkey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var shizukuListener: Shizuku.OnRequestPermissionResultListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val listener = Shizuku.OnRequestPermissionResultListener { _, result ->
                if (result == PackageManager.PERMISSION_GRANTED) {
                    PrivilegedShell.refreshShizuku()
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            shizukuListener = listener
        } catch (t: Throwable) {
            // Shizuku 未安装或未运行
        }

        setContent {
            FloatingKeyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        shizukuListener?.let { listener ->
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (t: Throwable) {
                // ignore
            }
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onRequestNotificationPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var privilegeMode by remember { mutableStateOf(Prefs.getPrivilegeMode(context)) }
    var privilegedReady by remember { mutableStateOf(ImeSwitcher.canSwitchCached(context)) }
    var rootReady by remember { mutableStateOf(PrivilegedShell.rootAvailable) }
    var shizukuReady by remember { mutableStateOf(PrivilegedShell.shizukuAvailable) }
    var autoUpdate by remember { mutableStateOf(Prefs.isAutoUpdate(context)) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var pendingApk by remember { mutableStateOf<java.io.File?>(null) }
    var logText by remember { mutableStateOf<String?>(null) }

    fun refreshStatuses() {
        overlayGranted = Settings.canDrawOverlays(context)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val ourId = ImeSwitcher.ourImeId(context)
        imeEnabled = imm.enabledInputMethodList.any { it.id == ourId }
        imeSelected = ImeSwitcher.currentImeId(context) == ourId
        buttonVisible = OverlayService.running
        accessibilityOn = AutoSwitchService.isEnabled(context)
        privilegeMode = Prefs.getPrivilegeMode(context)
        privilegedReady = ImeSwitcher.canSwitchCached(context)
        rootReady = PrivilegedShell.rootAvailable
        shizukuReady = PrivilegedShell.shizukuAvailable
        holdMode = Prefs.isHoldMode(context)
        autoSwitch = Prefs.isAutoSwitchEnabled(context)
        autoFocus = Prefs.isAutoSwitchOnFocus(context)
        fixedApps = Prefs.getFixedApps(context)
        autoUpdate = Prefs.isAutoUpdate(context)
    }

    suspend fun runUpdate(notify: Boolean = false) {
        checking = true
        updateStatus = "正在检查更新…"
        val info = UpdateManager.checkForUpdate(context, versionName(context))
        checking = false
        if (info == null) {
            val error = UpdateManager.lastError
            updateStatus = if (error != null) "检查更新失败：$error" else "已是最新版本"
            if (error != null) toast(context, "检查更新失败：$error")
            else if (notify) toast(context, "已是最新版本")
            return
        }
        downloading = true
        toast(context, "发现新版本 v${info.version}，正在下载…")
        updateStatus = "正在下载 v${info.version}…"
        val file = UpdateManager.download(context, info.url)
        downloading = false
        if (file == null) {
            val error = UpdateManager.lastError ?: "网络异常"
            updateStatus = "下载 v${info.version} 失败：$error"
            toast(context, "下载失败：$error")
            return
        }
        pendingApk = file
        when (UpdateManager.install(context, file)) {
            InstallResult.SILENT -> {
                pendingApk = null
                updateStatus = "已静默安装 v${info.version}"
                toast(context, "已安装 v${info.version}")
            }
            InstallResult.OPENED -> {
                pendingApk = null
                updateStatus = "请在弹出的系统界面点击「安装」"
                toast(context, "请在弹出的系统界面点击「安装」")
            }
            InstallResult.NEED_PERMISSION -> {
                updateStatus = "请允许「安装未知应用」，返回后自动继续"
                toast(context, "请允许「安装未知应用」，返回后自动继续")
            }
            InstallResult.FAILED -> {
                val error = UpdateManager.lastError ?: "未知原因"
                updateStatus = "安装 v${info.version} 失败：$error"
                toast(context, "安装失败：$error")
            }
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatuses()
        OverlayService.refresh(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatuses()
                // 从「安装未知应用」授权页返回后自动继续安装
                val apk = pendingApk
                if (apk != null && apk.exists() && context.packageManager.canRequestPackageInstalls()) {
                    pendingApk = null
                    UpdateManager.launchInstaller(context, apk)
                    updateStatus = "请在弹出的系统界面点击「安装」"
                }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (Prefs.isAutoSwitchEnabled(context)) {
                            PrivilegedShell.ensureAccessibility(context)
                        }
                    }
                    accessibilityOn = AutoSwitchService.isEnabled(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 后台探测特权状态（Root/Shizuku 时自动授予 WRITE_SECURE_SETTINGS 并开启无障碍），并按需自动检查更新
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            PrivilegedShell.refreshRoot()
            PrivilegedShell.refreshShizuku()
            // 有 Root / Shizuku 就直接把 WRITE_SECURE_SETTINGS 授予自己，
            // 这样即使停留在 ADB 模式也能真正切换输入法
            if (!ImeSwitcher.hasSecureSettings(context)) {
                when {
                    PrivilegedShell.rootAvailable ->
                        PrivilegedShell.grantSecureSettings(context, PrivilegeMode.ROOT)
                    PrivilegedShell.shizukuAvailable ->
                        PrivilegedShell.grantSecureSettings(context, PrivilegeMode.SHIZUKU)
                }
            }
            if (Prefs.isAutoSwitchEnabled(context)) {
                PrivilegedShell.ensureAccessibility(context)
            }
        }
        refreshStatuses()
        if (Prefs.isAutoUpdate(context)) {
            runUpdate(notify = false)
        }
    }

    // 系统广播 + Settings 变更监听：输入法被启用/选中时即时刷新（不再轮询）
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                refreshStatuses()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_INPUT_METHOD_CHANGED)
            addAction("android.intent.action.INPUT_METHOD_CHANGED")
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshStatuses()
            }
        }
        listOf(
            Settings.Secure.DEFAULT_INPUT_METHOD,
            Settings.Secure.ENABLED_INPUT_METHODS,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            Settings.Secure.ACCESSIBILITY_ENABLED
        ).forEach { key ->
            try {
                context.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key),
                    false,
                    observer
                )
            } catch (t: Throwable) {
                // ignore
            }
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (t: Throwable) {
                // ignore
            }
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    val autoHintReady = autoSwitch && imeEnabled && privilegedReady &&
        (accessibilityOn || privilegeMode != PrivilegeMode.ADB)
    val autoHint = when {
        !autoSwitch -> "自动切换已关闭"
        !imeEnabled -> "「悬浮按键输入法」未在系统设置中启用，无法自动切换"
        !privilegedReady -> "缺少「${privilegeMode.label}」授权，无法写入系统输入法设置"
        !accessibilityOn && privilegeMode == PrivilegeMode.ADB ->
            "无障碍未能自动开启，请点「去设置」手动开启"
        !accessibilityOn -> "将由「${privilegeMode.label}」轮询检测前台应用（无障碍未开启）"
        else -> "自动切换已就绪"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("悬浮按键") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
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
                                onClick = { showImePicker(context) }
                            ) { Text("切换") }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("权限方式", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "用于自动切换输入法与静默安装更新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrivilegeMode.values().forEach { mode ->
                            FilterChip(
                                selected = privilegeMode == mode,
                                onClick = {
                                    privilegeMode = mode
                                    Prefs.setPrivilegeMode(context, mode)
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            when (mode) {
                                                PrivilegeMode.ROOT -> PrivilegedShell.refreshRoot()
                                                PrivilegeMode.SHIZUKU -> PrivilegedShell.refreshShizuku()
                                                PrivilegeMode.ADB -> Unit
                                            }
                                        }
                                        privilegedReady = ImeSwitcher.canSwitchCached(context)
                                        rootReady = PrivilegedShell.rootAvailable
                                        shizukuReady = PrivilegedShell.shizukuAvailable
                                        OverlayService.refresh(context)
                                    }
                                },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    when (privilegeMode) {
                        PrivilegeMode.ADB -> StatusRow(
                            title = "ADB 授权",
                            status = if (privilegedReady) "已授权" else "未授权（需 adb 授权一次）",
                            ok = privilegedReady
                        ) {
                            if (!privilegedReady) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(ImeSwitcher.GRANT_COMMAND))
                                        toast(context, "已复制，请连接电脑执行")
                                    }
                                ) { Text("复制命令") }
                            }
                        }

                        PrivilegeMode.ROOT -> StatusRow(
                            title = "Root 权限",
                            status = if (rootReady) "已获取" else "未获取",
                            ok = rootReady
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            PrivilegedShell.refreshRoot()
                                        }
                                        rootReady = ok
                                        privilegedReady = ImeSwitcher.canSwitchCached(context)
                                        toast(context, if (ok) "Root 已就绪" else "未获取到 Root")
                                        OverlayService.refresh(context)
                                    }
                                }
                            ) { Text("检测") }
                        }

                        PrivilegeMode.SHIZUKU -> StatusRow(
                            title = "Shizuku",
                            status = when {
                                shizukuReady -> "已连接并授权"
                                PrivilegedShell.isShizukuInstalled() -> "已运行 · 未授权"
                                else -> "未运行"
                            },
                            ok = shizukuReady
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val granted = requestShizuku(context)
                                        if (granted) {
                                            PrivilegedShell.refreshShizuku()
                                            shizukuReady = PrivilegedShell.shizukuAvailable
                                            privilegedReady = ImeSwitcher.canSwitchCached(context)
                                            toast(context, "Shizuku 已授权")
                                            OverlayService.refresh(context)
                                        }
                                    }
                                }
                            ) { Text("授权") }
                        }
                    }

                    Text(
                        "无障碍服务用于检测前台应用与输入框，开启后会一直保持",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                OverlayService.refresh(context)
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
                                OverlayService.refresh(context)
                            }
                        )
                    }

                    StatusRow(
                        title = "无障碍服务",
                        status = if (accessibilityOn) "已开启（自动）"
                        else "未开启（缺少权限，请手动开启）",
                        ok = accessibilityOn
                    ) {
                        if (!accessibilityOn) {
                            OutlinedButton(onClick = { openAccessibilitySettings(context) }) {
                                Text("去设置")
                            }
                        }
                    }

                    Text(
                        text = autoHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (autoHintReady) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ImeSwitcher.trySwitchToOurs(context)
                                }
                                toast(context, result.message)
                                refreshStatuses()
                                if (result == SwitchResult.OK) {
                                    // 2 秒后自动切回，演示完整的「切过去 → 切回来」流程
                                    delay(2000)
                                    val restored = withContext(Dispatchers.IO) {
                                        ImeSwitcher.restorePrevious(context)
                                    }
                                    toast(
                                        context,
                                        if (restored) "已切回原输入法" else "未能切回，请检查授权"
                                    )
                                    refreshStatuses()
                                }
                            }
                        }
                    ) { Text("测试切换输入法") }

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
                        OutlinedButton(
                            onClick = {
                                pickerLauncher.launch(
                                    Intent(context, AppPickerActivity::class.java)
                                )
                            }
                        ) { Text("选择应用") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("版本 ${versionName(context)}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "从 GitHub 发布页检查更新",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val status = updateStatus
                    if (status != null) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.contains("失败")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { logText = AppLog.read(context) }
                        ) { Text("查看日志") }
                        OutlinedButton(
                            enabled = !checking && !downloading,
                            onClick = { scope.launch { runUpdate(notify = true) } }
                        ) {
                            Text(
                                when {
                                    checking -> "检查中…"
                                    downloading -> "下载中…"
                                    else -> "检查并更新"
                                }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动更新", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "启动时自动检查，发现新版本自动下载",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = {
                                autoUpdate = it
                                Prefs.setAutoUpdate(context, it)
                            }
                        )
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

    val log = logText
    if (log != null) {
        AlertDialog(
            onDismissRequest = { logText = null },
            title = { Text("运行日志") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        log,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { logText = null }) { Text("关闭") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { AppLog.clear(context); logText = null }) {
                        Text("清空")
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(log))
                            toast(context, "日志已复制")
                        }
                    ) { Text("复制") }
                }
            }
        )
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

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun showImePicker(context: Context) {
    if (Build.VERSION.SDK_INT >= 28) {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            return
        } catch (e: SecurityException) {
            // fall through
        }
    }
    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
}

/** 请求 Shizuku 授权，返回是否已获得权限。 */
private fun requestShizuku(context: Context): Boolean {
    return try {
        if (!Shizuku.pingBinder()) {
            toast(context, "请先安装并启动 Shizuku")
            return false
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            toast(context, "请在 Shizuku 中允许本应用")
            return false
        }
        Shizuku.requestPermission(1001)
        false
    } catch (t: Throwable) {
        toast(context, "Shizuku 不可用")
        false
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun versionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}
