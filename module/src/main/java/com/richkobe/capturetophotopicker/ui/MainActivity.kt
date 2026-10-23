package com.richkobe.capturetophotopicker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.highcapable.yukihookapi.YukiHook
import com.richkobe.capturetophotopicker.config.RedirectConfig
import kotlinx.coroutines.launch

/**
 * 模块主界面：Google Material 3（动态取色、edge-to-edge、Snackbar 反馈）。
 * 两个 Tab：「状态」与「作用域」。
 *
 * 作用域页 = 双层模型（见 [RedirectConfig] 详述）：
 *  - **注入层**（LSPosed 真实作用域）：勾选态来自 `YukiHook.Module.scope.packages`（框架真相源）；
 *    加入走 `scope.request()` → 框架弹「允许作用域」授权框；移除走 `scope.remove()`（同步）。
 *    首次纳入的 App 需重启一次完成注入（框架铁律，UI 会明确提示）。
 *  - **生效层**（实时开关）：Switch 写 RedirectConfig（RemotePreferences 跨进程），
 *    开=宿主下次调起相机即被重定向，关=立即恢复原生，**都不需要重启宿主**。
 *
 * 状态页：libxposed 底座下 isModuleActive 依赖框架服务异步连接，
 * 用官方 observeFrameworkService(lifecycle) 监听（注册即回调一次，连接/断开再回调），
 * 修复 v1「恒显示未激活」的错误（v1 直接同步读 isModuleActive，且自建 listener 抢注了底座服务槽）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarHostState() }

            var tab by rememberSaveable { mutableIntStateOf(0) }

            // 动态取色：isSystemInDarkTheme 是 @Composable，须在 remember 外调用
            val isDark = isSystemInDarkTheme()
            val scheme = remember(isDark) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    }.getOrNull() ?: if (isDark) darkColorScheme() else lightColorScheme()
                } else {
                    if (isDark) darkColorScheme() else lightColorScheme()
                }
            }

            MaterialTheme(colorScheme = scheme) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                label = { Text("状态") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
                                label = { Text("作用域") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        if (tab == 0) {
                            StatusScreen()
                        } else {
                            ScopeScreen(
                                onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 框架服务连接状态：observeFrameworkService 异步回调驱动。
 * 语义（官方文档）：注册立即回调一次；服务连接/断开再回调；
 * 首次 false 只代表「尚未连接」不代表「未激活」——短暂等待后再判定，后续回调继续刷新。
 */
@Composable
private fun rememberFrameworkServiceState(): FrameworkState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(FrameworkState()) }

    DisposableEffect(lifecycleOwner) {
        // 500ms 内没连上才判「未激活」（官方建议的等待窗口）
        var decided = false
        val bridge = YukiHook.Status.observeFrameworkService(lifecycleOwner.lifecycle) { available ->
            if (available) {
                decided = true
                state.value = FrameworkState(
                    connected = true,
                    active = runCatching { YukiHook.Status.isModuleActive }.getOrDefault(true),
                    frameworkName = runCatching { YukiHook.Status.Executor.name }.getOrNull() ?: "",
                    frameworkVersion = runCatching { YukiHook.Status.Executor.version }.getOrNull() ?: "",
                    apiVersion = runCatching { YukiHook.Status.Executor.apiVersion }.getOrNull() ?: 0,
                )
            } else if (!decided) {
                // 尚未连接：保持「检测中」；连接成功的回调会再刷新
                state.value = state.value.copy(connected = false)
            } else {
                // 已判定过又断开：服务掉线
                state.value = state.value.copy(connected = false)
            }
        }
        onDispose { bridge?.unregister() }
    }
    return state.value
}

/** 框架服务状态快照（不可变，供 UI 渲染）。 */
private data class FrameworkState(
    val connected: Boolean = false,
    val active: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val apiVersion: Int = 0,
)

/** Tab 0：激活状态与工作方式。 */
@Composable
private fun StatusScreen() {
    val fs = rememberFrameworkServiceState()
    val context = LocalContext.current
    // 生效名单数量（实时开关层）
    var enabledCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { enabledCount = RedirectConfig.readEnabled(context).size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                val ok = fs.connected && fs.active
                Icon(
                    imageVector = if (ok) Icons.Filled.CheckCircle else if (fs.connected) Icons.Filled.Warning else Icons.Filled.HourglassEmpty,
                    contentDescription = null,
                    tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = when {
                            ok -> "模块已激活"
                            fs.connected -> "服务已连接但未检测到激活"
                            else -> "正在检测框架服务…"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when {
                            ok -> "${fs.frameworkName} ${fs.frameworkVersion} · API ${fs.apiVersion}"
                            fs.connected -> "请在 LSPosed 管理器中启用本模块"
                            else -> "libxposed 服务尚未连接（异步）。若长时间停留在此状态，请确认模块已在 LSPosed 中启用"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("实时生效开关", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已启用重定向的应用：$enabledCount 个。\n" +
                        "在「作用域」页拨动 Switch 即时开/关，目标应用无需重启；" +
                        "首次把新应用加入作用域后，需重启该应用一次完成注入（框架限制）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { enabledCount = RedirectConfig.readEnabled(context).size }) {
                    Text("刷新")
                }
            }
        }

        Text("工作方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        InfoCard(Icons.Filled.CameraAlt, "拦截相机调起",
            "作用域内且已启用开关的 App 调起相机拍照页（com.oplus.camera/.component.CapturePhotoActivity 或 ACTION_IMAGE_CAPTURE）时被拦截。")
        InfoCard(Icons.Filled.PhotoLibrary, "重定向到 PhotoPicker",
            "出站 Intent 就地改写为 ACTION_PICK_IMAGES，直接弹出系统相册选择器（com.android.photopicker）。")
        InfoCard(Icons.Filled.CheckCircle, "结果翻译（关键）",
            "选中的图同时以三种姿势回填：EXTRA_OUTPUT 字节回写、\"data\" Bitmap 注入、data/clipData Uri——调用方无论怎么读都像刚拍完照。")
        InfoCard(Icons.Filled.Hub, "作用域官方通道",
            "作用域经 libxposed XposedService（YukiHook.Module.scope）直接读写框架真相源：本页勾选状态即 LSPosed 实际作用域，选中应用由框架弹「允许作用域」授权框。")
    }
}

/** Tab 1：作用域管理（注入层=框架 scope；生效层=实时开关）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeScreen(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val fs = rememberFrameworkServiceState()

    // 应用列表（一次性加载）
    var apps by remember { mutableStateOf<List<AppListLoader.AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = runCatching { AppListLoader.load(context) }.getOrNull() }

    // 注入层：LSPosed 真实作用域（框架真相源），任何增删后 refreshTick++ 重读
    var refreshTick by remember { mutableIntStateOf(0) }
    var realScope by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 生效层：实时开关名单
    var enabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(refreshTick, fs.connected) {
        if (fs.connected) {
            realScope = runCatching { YukiHook.Module.scope.packages.toSet() }.getOrDefault(emptySet())
        }
        enabled = RedirectConfig.readEnabled(context)
    }

    var query by rememberSaveable { mutableStateOf("") }
    var sortType by rememberSaveable { mutableStateOf(AppSortType.NAME) }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER_ONLY) }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var selectedFirst by rememberSaveable { mutableStateOf(true) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    // 待确认操作：(包名, 显示名, 动作)
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    val visibleApps = remember(apps, query, sortType, filter, reverse, selectedFirst, realScope, enabled) {
        (apps ?: emptyList()).asSequence()
            .filter { e ->
                when (filter) {
                    AppFilter.ALL -> true
                    AppFilter.USER_ONLY -> !e.info.isSystemApp
                    AppFilter.SYSTEM_ONLY -> e.info.isSystemApp
                }
            }
            .filter { e ->
                query.isBlank() ||
                    e.info.label.contains(query, true) ||
                    e.info.packageName.contains(query, true)
            }
            .sortedWith(
                compareByDescending<AppListLoader.AppEntry> {
                    if (selectedFirst) realScope.contains(it.info.packageName) else false
                }.thenBy(
                    if (reverse) reverseOrder() else naturalOrder(),
                ) { e ->
                    when (sortType) {
                        AppSortType.NAME -> e.info.label.lowercase()
                        AppSortType.PACKAGE_NAME -> e.info.packageName
                        AppSortType.INSTALL_TIME -> e.info.installTime.toString().padStart(20, '0')
                        AppSortType.UPDATE_TIME -> e.info.updateTime.toString().padStart(20, '0')
                    }
                },
            )
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索应用名 / 包名") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                }
                DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                    AppSortType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.labelZh) },
                            leadingIcon = { if (type == sortType) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { sortType = type; sortMenuExpanded = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (reverse) "降序" else "升序") },
                        leadingIcon = { Icon(if (reverse) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, contentDescription = null) },
                        onClick = { reverse = !reverse; sortMenuExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("已选置顶") },
                        leadingIcon = { if (selectedFirst) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = { selectedFirst = !selectedFirst; sortMenuExpanded = false },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.labelZh) })
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (fs.connected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = "作用域 ${realScope.size} 个 · 实时生效 ${enabled.size} 个",
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (fs.connected)
                        "点应用行=加入/移出 LSPosed 真实作用域（加入时框架弹「允许作用域」授权框）；" +
                            "行内 Switch=实时生效开关，拨动立即开/关重定向，无需重启目标应用。" +
                            "首次加入作用域的应用需重启一次完成注入。"
                    else
                        "框架服务未连接：无法读写真实作用域。请确认模块已在 LSPosed 中启用后重进本页。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!fs.connected) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { openLsposedManager(context) }) { Text("打开 LSPosed 管理器") }
                }
            }
        }

        if (apps == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(visibleApps, key = { it.info.packageName }) { entry ->
                    val pkg = entry.info.packageName
                    val inScope = realScope.contains(pkg)
                    AppRow(
                        entry = entry,
                        inScope = inScope,
                        enabledNow = enabled.contains(pkg),
                        serviceReady = fs.connected,
                        // 点行 → 弹确认框（加入/移出作用域）
                        onRowClick = {
                            if (!fs.connected) {
                                onMessage("框架服务未连接，无法管理作用域")
                            } else {
                                pending = if (inScope) PendingAction(pkg, entry.info.label, Action.REMOVE_SCOPE)
                                else PendingAction(pkg, entry.info.label, Action.ADD_SCOPE)
                            }
                        },
                        // Switch → 实时生效开关（仅作用域内的应用可拨动）
                        onSwitch = { want ->
                            if (!inScope) {
                                onMessage("请先把「${entry.info.label}」加入作用域")
                                return@AppRow
                            }
                            enabled = if (want) RedirectConfig.enable(context, pkg)
                            else RedirectConfig.disable(context, pkg)
                            onMessage(
                                if (want) "已启用：「${entry.info.label}」的拍照请求立即转为相册选择"
                                else "已停用：「${entry.info.label}」立即恢复原相机行为",
                            )
                        },
                    )
                }
                if (visibleApps.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text("没有匹配的应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // 确认对话框：加入 → scope.request（框架弹授权框）；移出 → scope.remove（同步）
    pending?.let { act ->
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = {
                Icon(
                    if (act.action == Action.ADD_SCOPE) Icons.Filled.AddCircleOutline else Icons.Filled.RemoveCircleOutline,
                    contentDescription = null,
                )
            },
            title = { Text(if (act.action == Action.ADD_SCOPE) "加入作用域" else "移出作用域") },
            text = {
                Text(
                    when (act.action) {
                        Action.ADD_SCOPE ->
                            "将「${act.label}」加入本模块作用域？\n" +
                                "确认后系统会弹出「允许作用域」授权框，点允许即加入；" +
                                "之后需重启该应用一次完成注入，并在列表里拨动 Switch 启用实时生效。"
                        Action.REMOVE_SCOPE ->
                            "将「${act.label}」移出本模块作用域？\n" +
                                "移出后立即不再注入该应用（其进程重启后彻底恢复原生行为），实时开关同时关闭。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    when (act.action) {
                        Action.ADD_SCOPE -> {
                            runCatching {
                                YukiHook.Module.scope.request(act.pkg) { approved, errorMessage, approvedPkgs ->
                                    // 回调在主线程
                                    if (approved) {
                                        refreshTick++
                                        onMessage("已加入作用域：${act.label}（重启该应用后注入生效）")
                                    } else {
                                        onMessage("授权失败：${errorMessage ?: "用户未允许或服务异常"}")
                                    }
                                }
                                onMessage("已发起授权请求，请在系统弹框点「允许」")
                            }.onFailure { onMessage("requestScope 异常：${it.message}") }
                        }
                        Action.REMOVE_SCOPE -> {
                            val ok = runCatching {
                                YukiHook.Module.scope.remove(act.pkg)
                                // 移出作用域时同步关掉实时开关，防止「已移出但名单残留」
                                RedirectConfig.disable(context, act.pkg)
                                true
                            }.getOrDefault(false)
                            if (ok) { refreshTick++; onMessage("已移出作用域：${act.label}") }
                            else onMessage("移出失败：框架服务异常")
                        }
                    }
                }) { Text(if (act.action == Action.ADD_SCOPE) "确认加入" else "确认移出") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
        )
    }
}

/** 待确认的作用域操作。 */
private data class PendingAction(val pkg: String, val label: String, val action: Action)
private enum class Action { ADD_SCOPE, REMOVE_SCOPE }

/** 打开 LSPosed 管理器（该 fork 无作用域深链，只能开首页+文案指路，见项目经验）。 */
private fun openLsposedManager(context: Context) {
    val candidates = listOf(
        Intent().setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity"),
    )
    for (intent in candidates) {
        runCatching { context.startActivity(intent) }.onSuccess { return }
    }
    android.widget.Toast
        .makeText(context, "请手动打开 LSPosed → 模块 → 我没带身份证", android.widget.Toast.LENGTH_LONG)
        .show()
}

/** 应用行：图标 + 名称 + 包名 +（作用域标记）+ 实时生效 Switch。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRow(
    entry: AppListLoader.AppEntry,
    inScope: Boolean,
    enabledNow: Boolean,
    serviceReady: Boolean,
    onRowClick: () -> Unit,
    onSwitch: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = serviceReady, onClick = onRowClick),
        headlineContent = { Text(entry.info.label, maxLines = 1) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.info.packageName, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (inScope) {
                    Spacer(Modifier.width(6.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text("作用域内", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp),
                    )
                }
            }
        },
        leadingContent = {
            val bitmap = remember(entry.info.packageName) {
                runCatching { entry.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
            }
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(40.dp))
            }
        },
        trailingContent = {
            Switch(
                checked = enabledNow,
                // 只有已在作用域（已注入）的应用才能拨动实时开关
                enabled = serviceReady && inScope,
                onCheckedChange = onSwitch,
            )
        },
    )
    HorizontalDivider()
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, desc: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
