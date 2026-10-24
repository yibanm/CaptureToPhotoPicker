package com.richkobe.capturetophotopicker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.highcapable.yukihookapi.YukiHook
import com.richkobe.capturetophotopicker.BuildConfig
import com.richkobe.capturetophotopicker.R
import com.richkobe.capturetophotopicker.config.FrameworkEnv
import com.richkobe.capturetophotopicker.config.RedirectConfig
import kotlinx.coroutines.launch

/**
 * 模块主界面：Material 3（动态取色、edge-to-edge）。
 *
 * 结构（v1.0.2 按用户要求精简）：
 *  - TopAppBar：标题「我没带身份证」+ 版本号 + 右上角 GitHub 图标；
 *  - Tab「状态」：激活状态（observeFrameworkService 异步监听）+ 三步指引 + FAQ；
 *  - Tab「作用域」：应用列表（搜索/排序/筛选），**点行打勾=启用（同时写入框架作用域与
 *    生效名单），再点=取消（立即停用）**。没有多余开关——一个应用只有一个状态：启用/未启用。
 *
 * 免 root（LSPatch/NPatch）：本地模式下点行仍先写生效名单，requestScope 失败时
 * 引导去补丁管理器勾选；内置模式随宿主自动生效，UI 显示说明卡。
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
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "v${BuildConfig.VERSION_NAME}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { openGitHub(context) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_github),
                                        contentDescription = stringResource(R.string.topbar_github),
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    },
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
                            StatusScreen(
                                onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                                onGoScope = { tab = 1 },
                            )
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
 * 首次 false 只代表「尚未连接」不代表「未激活」——连接成功回调会再刷新。
 */
@Composable
private fun rememberFrameworkServiceState(): FrameworkState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(FrameworkState()) }

    DisposableEffect(lifecycleOwner) {
        val bridge = YukiHook.Status.observeFrameworkService(lifecycleOwner.lifecycle) { available ->
            if (available) {
                state.value = FrameworkState(
                    connected = true,
                    active = runCatching { YukiHook.Status.isModuleActive }.getOrDefault(true),
                    frameworkName = runCatching { YukiHook.Status.Executor.name }.getOrNull() ?: "",
                    frameworkVersion = runCatching { YukiHook.Status.Executor.version }.getOrNull() ?: "",
                    apiVersion = runCatching { YukiHook.Status.Executor.apiVersion }.getOrNull() ?: 0,
                )
            } else {
                state.value = state.value.copy(connected = false)
            }
        }
        onDispose { bridge?.unregister() }
    }
    return state.value
}

/** 框架服务状态快照。 */
private data class FrameworkState(
    val connected: Boolean = false,
    val active: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val apiVersion: Int = 0,
)

/** Tab 0：激活状态 + 三步指引 + FAQ。 */
@Composable
private fun StatusScreen(onMessage: (String) -> Unit, onGoScope: () -> Unit) {
    val fs = rememberFrameworkServiceState()
    val context = LocalContext.current
    var enabledCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { enabledCount = RedirectConfig.readEnabled(context).size }
    val isPatchEnv = FrameworkEnv.isPatchFramework(fs.frameworkName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 激活状态卡
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
                            else -> "若长时间停留在此状态，请确认模块已在 LSPosed 中启用并重启过手机"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 免 root 环境说明卡（仅补丁框架下显示）
        if (isPatchEnv) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.patch_env_title, fs.frameworkName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.patch_env_local_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = { openPatchManager(context, onMessage) }) {
                        Text(stringResource(R.string.patch_open_manager))
                    }
                }
            }
        }

        // 启用计数 + 一句话用法
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.status_enabled_count, enabledCount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.status_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { enabledCount = RedirectConfig.readEnabled(context).size }) {
                        Text(stringResource(R.string.status_refresh))
                    }
                    Button(onClick = onGoScope) { Text("去作用域页") }
                }
            }
        }

        // 三步指引
        SetupGuideCard()

        // 工作方式（简版，3 卡）
        Text("工作方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        InfoCard(Icons.Filled.CameraAlt, "拦截相机调起",
            "已勾选的 App 调起相机拍照页（ACTION_IMAGE_CAPTURE 或 com.oplus.camera 拍照页）时被拦截，改弹系统相册选择器。")
        InfoCard(Icons.Filled.CheckCircle, "选图装填",
            "选中的图片按相机式结果回填：写入 App 原定的输出文件、内联缩略图、图片地址三种方式同时补齐——App 无论怎么读都像刚拍完照。")
        InfoCard(Icons.Filled.Bolt, "实时启停",
            "勾选=下次调起相机立即转为相册；取消=立即恢复原相机。都不需要重启应用（仅首次勾选后需重启一次完成注入）。")

        // FAQ
        FaqCard()
    }
}

/** 首次使用三步指引卡（可折叠）。 */
@Composable
private fun SetupGuideCard() {
    var expanded by rememberSaveable { mutableStateOf(true) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.guide_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.guide_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GuideStep(1, stringResource(R.string.guide_step1), stringResource(R.string.guide_step1_detail))
                    GuideStep(2, stringResource(R.string.guide_step2), stringResource(R.string.guide_step2_detail))
                    GuideStep(3, stringResource(R.string.guide_step3), stringResource(R.string.guide_step3_detail))
                    TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.guide_fold))
                    }
                }
            }
        }
    }
}

/** 单步指引：序号圆徽 + 标题 + 细节。 */
@Composable
private fun GuideStep(index: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$index",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** FAQ 折叠列表。 */
@Composable
private fun FaqCard() {
    val qa = listOf(
        stringResource(R.string.faq_q1) to stringResource(R.string.faq_a1),
        stringResource(R.string.faq_q2) to stringResource(R.string.faq_a2),
        stringResource(R.string.faq_q3) to stringResource(R.string.faq_a3),
        stringResource(R.string.faq_q4) to stringResource(R.string.faq_a4),
        stringResource(R.string.faq_q5) to stringResource(R.string.faq_a5),
    )
    var openIndex by remember { mutableStateOf(-1) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.faq_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            qa.forEachIndexed { i, (q, a) ->
                Row(
                    Modifier.fillMaxWidth().clickable { openIndex = if (openIndex == i) -1 else i }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(q, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(
                        if (openIndex == i) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = openIndex == i) {
                    Text(
                        a,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
                if (i != qa.lastIndex) HorizontalDivider()
            }
        }
    }
}

/**
 * Tab 1：作用域页（单一交互：点行=启用/取消，无多余开关）。
 *
 * 「已启用」= 在框架作用域内 且 在生效名单内（两层同时满足才算真正生效）。
 * 点未启用的行 → 弹确认框「启用」→ 写生效名单 + scope.request（框架弹授权框）；
 * 点已启用的行 → 弹确认框「取消启用」→ 移出生效名单（立即停用）+ scope.remove。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeScreen(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val fs = rememberFrameworkServiceState()
    val isPatchEnv = FrameworkEnv.isPatchFramework(fs.frameworkName)

    var apps by remember { mutableStateOf<List<AppListLoader.AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = runCatching { AppListLoader.load(context) }.getOrNull() }

    // 注入层（框架真实作用域）与生效层（名单），任何变更后 refreshTick++ 重读
    var refreshTick by remember { mutableIntStateOf(0) }
    var realScope by remember { mutableStateOf<Set<String>>(emptySet()) }
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

    // 待确认操作
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
        // 搜索 + 排序
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.scope_search_hint)) },
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

        // 筛选 chips
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.labelZh) })
            }
        }

        // 状态说明卡
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (fs.connected || isPatchEnv) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.scope_count, enabled.size),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        isPatchEnv -> stringResource(R.string.patch_env_local_detail)
                        fs.connected -> stringResource(R.string.scope_hint_lsp)
                        else -> stringResource(R.string.scope_hint_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!fs.connected && !isPatchEnv) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { openLsposedManager(context) }) {
                        Text(stringResource(R.string.scope_open_lsp))
                    }
                }
            }
        }

        // 应用列表
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
                    val isEnabled = enabled.contains(pkg)
                    AppRow(
                        entry = entry,
                        isEnabled = isEnabled,
                        inScope = inScope,
                        onClick = {
                            if (!fs.connected && !isPatchEnv) {
                                onMessage("框架服务未连接，无法管理作用域")
                            } else {
                                pending = if (isEnabled || inScope) {
                                    PendingAction(pkg, entry.info.label, Action.DISABLE)
                                } else {
                                    PendingAction(pkg, entry.info.label, Action.ENABLE)
                                }
                            }
                        },
                    )
                }
                if (visibleApps.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.scope_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // 确认对话框：启用/取消（唯一交互入口）
    pending?.let { act ->
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = {
                Icon(
                    if (act.action == Action.ENABLE) Icons.Filled.AddCircleOutline else Icons.Filled.RemoveCircleOutline,
                    contentDescription = null,
                )
            },
            title = {
                Text(
                    stringResource(
                        if (act.action == Action.ENABLE) R.string.confirm_add_title else R.string.confirm_remove_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (act.action == Action.ENABLE) R.string.confirm_add_body else R.string.confirm_remove_body,
                        act.label,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    when (act.action) {
                        Action.ENABLE -> {
                            // 生效名单立即写入（实时层，所有环境都有效）
                            enabled = RedirectConfig.enable(context, act.pkg)
                            if (isPatchEnv) {
                                // LSPatch/NPatch 本地模式：作用域=「哪些应用用本模块打了补丁」，
                                // 由补丁管理器管（运行时 requestScope 必被框架拒绝，不调用）；
                                // 软件内勾选写生效名单即「在软件里自己开作用域」
                                onMessage(
                                    if (realScope.contains(act.pkg))
                                        "已启用「${act.label}」：立即生效，无需重启"
                                    else
                                        "已启用「${act.label}」：该应用还没用本模块打补丁，请到补丁管理器添加后重装补丁包",
                                )
                            } else if (fs.connected) {
                                // LSPosed：注入层走框架官方通道（弹「允许作用域」授权框）
                                runCatching {
                                    YukiHook.Module.scope.request(act.pkg) { approved, errorMessage, _ ->
                                        if (approved) {
                                            refreshTick++
                                            onMessage("已加入作用域：${act.label}（重启该应用后生效）")
                                        } else {
                                            onMessage("作用域授权未通过（${errorMessage ?: "已取消"}），请到 LSPosed 管理器手动勾选")
                                        }
                                    }
                                    onMessage("已启用「${act.label}」，请在系统弹框点「允许」，然后重启该应用一次")
                                }.onFailure {
                                    onMessage("已启用「${act.label}」（名单已写入）；作用域请求失败：${it.message}")
                                }
                            } else {
                                onMessage("已启用「${act.label}」（框架服务未连接，作用域需到管理器手动勾选）")
                            }
                            refreshTick++
                        }
                        Action.DISABLE -> {
                            // 取消：生效名单立即移除（宿主下次调起立即恢复原生）
                            enabled = RedirectConfig.disable(context, act.pkg)
                            // 注入层同步移除（仅 LSPosed；补丁框架下 requestScope/removeScope 无意义）
                            if (!isPatchEnv && fs.connected && realScope.contains(act.pkg)) {
                                runCatching { YukiHook.Module.scope.remove(act.pkg) }
                                    .onFailure { onMessage("移出作用域失败：${it.message}（已立即停用）") }
                            }
                            refreshTick++
                            onMessage("已停用「${act.label}」：立即恢复原相机行为")
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            if (act.action == Action.ENABLE) R.string.confirm_add_btn else R.string.confirm_remove_btn,
                        ),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.confirm_cancel)) } },
        )
    }
}

/** 待确认操作。 */
private data class PendingAction(val pkg: String, val label: String, val action: Action)
private enum class Action { ENABLE, DISABLE }

/** 打开 LSPosed 管理器（该 fork 无作用域深链，开首页+Toast 指路）。 */
private fun openLsposedManager(context: Context) {
    val intent = Intent().setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")
    runCatching { context.startActivity(intent) }.onFailure {
        android.widget.Toast
            .makeText(context, "请手动打开 LSPosed → 模块 → 我没带身份证", android.widget.Toast.LENGTH_LONG)
            .show()
    }
}

/** 打开补丁管理器（按实测包名探测，失败 Toast 指路）。 */
private fun openPatchManager(context: Context, onMessage: (String) -> Unit) {
    // 实测（2026-10-24，NPatch 1.0.8-801 补丁包 config.json）：managerPackageName=top.nkbe.npatch
    // LSPatch 官方为 org.lsposed.lspatch；两个都试，都失败再兜底提示手动打开
    val candidates = listOf("top.nkbe.npatch", "org.lsposed.lspatch", "com.npatch.manager")
    val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    for (pkg in candidates) {
        val intent = Intent(launch).setPackage(pkg)
        if (intent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(intent) }.onSuccess { return }
        }
    }
    onMessage("未找到补丁管理器，请手动打开 LSPatch / NPatch")
}

/** 打开 GitHub 仓库页。 */
private fun openGitHub(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO)))
    }.onFailure {
        android.widget.Toast
            .makeText(context, "无法打开浏览器：$GITHUB_REPO", android.widget.Toast.LENGTH_LONG)
            .show()
    }
}

private const val GITHUB_REPO = "https://github.com/yibanm/CaptureToPhotoPicker"

/** 应用行：图标 + 名称 + 包名 + 勾选标记。整行点击=启用/取消（唯一交互）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRow(
    entry: AppListLoader.AppEntry,
    isEnabled: Boolean,
    inScope: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.info.label, maxLines = 1) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.info.packageName, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                when {
                    isEnabled && inScope -> ScopeChip("已启用", MaterialTheme.colorScheme.primary)
                    isEnabled && !inScope -> ScopeChip("待授权", MaterialTheme.colorScheme.tertiary)
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
        // 勾选状态图标（纯展示，点击走整行 onClick；不用 Checkbox——onCheckedChange=null 会渲染成禁用灰色）
        trailingContent = {
            Icon(
                imageVector = if (isEnabled) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (isEnabled) "已启用" else "未启用",
                tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(28.dp),
            )
        },
    )
    HorizontalDivider()
}

/** 状态小徽标。 */
@Composable
private fun ScopeChip(text: String, color: Color) {
    Spacer(Modifier.width(6.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
    )
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
