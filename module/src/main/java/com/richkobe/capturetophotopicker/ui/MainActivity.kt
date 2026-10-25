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
import com.richkobe.capturetophotopicker.config.RedirectConfig
import kotlinx.coroutines.launch

/**
 * 模块主界面：Material 3（动态取色、edge-to-edge）。
 *
 * 结构（v1.0.3 终态，按用户要求回到 LSPosed 模块的朴素形态）：
 *  - TopAppBar：模块名「我没带身份证」+ 版本号 + GitHub 图标（octocat 剪影，随主题着色）；
 *  - Tab「状态」：激活状态卡 + **三种使用方式的详细文字说明**（LSPosed / 免root内置 /
 *    免root本地，纯说明、无切换控件）+ 启用计数 + 分步指引 + FAQ；
 *  - Tab「作用域」：应用列表（搜索/排序/筛选），点行=勾选/取消勾选（弹确认框）。
 *
 * 勾选状态**只以磁盘上的生效名单为准**（RedirectConfig 私有 prefs）：
 * 取消勾选必然把名单写回并立即反映到界面，不存在「取消不掉」的残留态；
 * 写失败时明确弹错误提示而不是静默保持勾选。
 *
 * 免 root 两种模式不需要任何界面开关：
 *  - 内置模式：模块被嵌进宿主补丁包，hook 侧自动识别宿主并直接生效（FrameworkEnv.isPatchedHost）；
 *  - 本地模式：打过补丁的宿主进程内同样自动生效；名单只作为 LSPosed 下的实时启停开关。
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
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Tab 0：激活状态 + 三种使用方式说明 + 指引 + FAQ。 */
@Composable
private fun StatusScreen(
    onMessage: (String) -> Unit,
    onGoScope: () -> Unit,
) {
    val fs = rememberFrameworkServiceState()
    val context = LocalContext.current
    var enabledCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { enabledCount = RedirectConfig.readEnabled(context).size }

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
                    imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.HourglassEmpty,
                    contentDescription = null,
                    tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = if (ok) "模块已激活" else "正在检测框架服务…",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (ok) "${fs.frameworkName} ${fs.frameworkVersion} · API ${fs.apiVersion}"
                        else "使用免 root 补丁（LSPatch/NPatch）时此处不显示连接属正常，见下方说明",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 启用计数 + 入口
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

        // 三种使用方式（纯文字说明，无切换控件）
        Text("三种使用方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        InfoCard(Icons.Filled.Verified, "LSPosed（需 root）",
            "装模块 APK → LSPosed 管理器启用本模块 → 重启手机 → 在「作用域」页点应用加入作用域（系统弹授权框）→ 重启该应用一次完成注入。之后在作用域页勾选/取消即实时启停。")
        InfoCard(Icons.Filled.AutoFixHigh, "免 root · 内置模式",
            "用 NPatch/LSPatch 打补丁时把本模块 APK 一起嵌进目标应用（NPatch 命令行：-m 模块.apk；管理器里选「集成/内置」）。装好补丁包即自动生效，无需任何配置，本界面也可以完全不用。")
        InfoCard(Icons.Filled.PhoneAndroid, "免 root · 本地模式",
            "模块 APK 单独安装，再用 NPatch/LSPatch 管理器给目标应用勾选本模块后打补丁。打过补丁的应用自动生效；本界面「作用域」页的勾选可作为实时启停开关（对打过补丁的应用有效，未打补丁的应用勾了也不生效）。")

        // 分步指引（LSPosed 路线）
        SetupGuideCard()

        // FAQ
        FaqCard()
    }
}

/** 首次使用三步指引卡（可折叠，LSPosed 路线）。 */
@Composable
private fun SetupGuideCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
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
 * Tab 1：作用域/启停管理。
 *
 * 勾选状态只以磁盘名单（RedirectConfig 私有 prefs）为准：
 *  - 取消勾选 = 从名单移除并写回磁盘，界面立即反映；写失败会弹错误，不会留下「取消不掉」的假勾选；
 *  - LSPosed 服务已连接时，勾选/取消同时同步框架作用域（requestScope 弹授权框 / removeScope）；
 *  - 免 root 补丁宿主不在此请求作用域（框架不允许运行时改），打过补丁的宿主由 hook 侧自动生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeScreen(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val fs = rememberFrameworkServiceState()

    var apps by remember { mutableStateOf<List<AppListLoader.AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = runCatching { AppListLoader.load(context) }.getOrNull() }

    // 磁盘名单 = 界面勾选状态的唯一来源
    var refreshTick by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 框架真实作用域（仅 LSPosed 服务已连接时有值，用于徽标与同步移除）
    var realScope by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(refreshTick, fs.connected) {
        enabled = RedirectConfig.readEnabled(context)
        realScope = if (fs.connected) {
            runCatching { YukiHook.Module.scope.packages.toSet() }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var sortType by rememberSaveable { mutableStateOf(AppSortType.NAME) }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER_ONLY) }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var selectedFirst by rememberSaveable { mutableStateOf(true) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    var pending by remember { mutableStateOf<PendingAction?>(null) }

    val visibleApps = remember(apps, query, sortType, filter, reverse, selectedFirst, enabled) {
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
                    if (selectedFirst) enabled.contains(it.info.packageName) else false
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.scope_count, enabled.size),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (fs.connected) stringResource(R.string.scope_hint_connected)
                    else stringResource(R.string.scope_hint_off),
                    style = MaterialTheme.typography.bodySmall,
                )
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
                    val isChecked = enabled.contains(pkg)
                    AppRow(
                        entry = entry,
                        isChecked = isChecked,
                        // 徽标：已勾选时补充说明注入状态（只读信息，不影响取消操作）
                        chip = when {
                            !isChecked -> null
                            fs.connected && realScope.contains(pkg) -> "已生效" to MaterialTheme.colorScheme.primary
                            fs.connected -> "待授权" to MaterialTheme.colorScheme.tertiary
                            entry.info.isPatched -> "已生效" to MaterialTheme.colorScheme.primary
                            else -> "待注入" to MaterialTheme.colorScheme.tertiary
                        },
                        onClick = {
                            pending = PendingAction(pkg, entry.info.label, if (isChecked) Action.DISABLE else Action.ENABLE)
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

    // 确认框：勾选/取消（取消只改名单，必然成功或明确报错，不会残留）
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
                    val remoteWritable = fs.connected
                    if (act.action == Action.ENABLE) {
                        val next = RedirectConfig.readEnabled(context).toMutableSet().apply { add(act.pkg) }
                        val ok = RedirectConfig.writeEnabled(context, next, remoteWritable)
                        refreshTick++
                        if (!ok) {
                            onMessage("写入失败，请重试")
                            return@TextButton
                        }
                        if (fs.connected) {
                            runCatching {
                                YukiHook.Module.scope.request(act.pkg) { approved, errorMessage, _ ->
                                    if (approved) {
                                        refreshTick++
                                        onMessage("已加入作用域：${act.label}（重启该应用后生效）")
                                    } else {
                                        onMessage("作用域授权未通过（${errorMessage ?: "已取消"}）；启停名单已写入，可在 LSPosed 管理器补勾作用域")
                                    }
                                }
                                onMessage("已勾选「${act.label}」，请在系统弹框点「允许」")
                            }.onFailure { onMessage("已勾选「${act.label}」；作用域请求失败：${it.message}") }
                        } else {
                            onMessage("已勾选「${act.label}」：打过补丁的应用立即生效")
                        }
                    } else {
                        val next = RedirectConfig.readEnabled(context).toMutableSet().apply { remove(act.pkg) }
                        val ok = RedirectConfig.writeEnabled(context, next, remoteWritable)
                        refreshTick++
                        if (!ok) {
                            onMessage("写入失败，请重试")
                            return@TextButton
                        }
                        // LSPosed 下同步移出作用域（失败不阻塞：名单已移除，宿主下次调起即恢复原生）
                        if (fs.connected && realScope.contains(act.pkg)) {
                            runCatching { YukiHook.Module.scope.remove(act.pkg) }
                                .onFailure { onMessage("已取消勾选；移出作用域失败：${it.message}") }
                        }
                        onMessage("已取消「${act.label}」：立即恢复原相机行为")
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

/**
 * 应用行：图标 + 名称 + 包名 + 状态徽标 + 勾选图标。整行点击=勾选/取消（弹确认框）。
 * @param chip 已勾选时的补充说明（已生效/待授权/待注入）；未勾选为 null。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRow(
    entry: AppListLoader.AppEntry,
    isChecked: Boolean,
    chip: Pair<String, Color>?,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.info.label, maxLines = 1) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.info.packageName, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                chip?.let { (text, color) ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
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
            Icon(
                imageVector = if (isChecked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (isChecked) "已勾选" else "未勾选",
                tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(28.dp),
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
