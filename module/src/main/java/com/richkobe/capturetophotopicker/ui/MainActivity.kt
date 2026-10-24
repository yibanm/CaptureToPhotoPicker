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

            // 运行模式检测（不依赖服务是否连接——本地模式 UI 进程本就拿不到服务，见 FrameworkEnv）
            val fs = rememberFrameworkServiceState()
            val mode = remember(fs.connected, fs.frameworkName) {
                FrameworkEnv.invalidateUiModeCache()
                FrameworkEnv.detectMode(context, fs.frameworkName, fs.connected)
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
                                fs = fs,
                                mode = mode,
                                onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                                onGoScope = { tab = 1 },
                            )
                        } else {
                            ScopeScreen(
                                fs = fs,
                                mode = mode,
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

/** Tab 0：运行模式、激活状态与使用指引。 */
@Composable
private fun StatusScreen(
    fs: FrameworkState,
    mode: FrameworkEnv.RuntimeMode,
    onMessage: (String) -> Unit,
    onGoScope: () -> Unit,
) {
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
        // 运行模式卡：本地模式下服务不连是常态，如实展示模式而非报「未激活」（模式检测不依赖服务连接）
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (mode) {
                            FrameworkEnv.RuntimeMode.LSPOSED -> Icons.Filled.CheckCircle
                            FrameworkEnv.RuntimeMode.PATCH_EMBEDDED -> Icons.Filled.AutoFixHigh
                            FrameworkEnv.RuntimeMode.PATCH_LOCAL -> Icons.Filled.PhoneAndroid
                            FrameworkEnv.RuntimeMode.UNKNOWN -> Icons.Filled.HourglassEmpty
                        },
                        contentDescription = null,
                        tint = if (mode == FrameworkEnv.RuntimeMode.UNKNOWN)
                            MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = when (mode) {
                            FrameworkEnv.RuntimeMode.LSPOSED -> "LSPosed 模式"
                            FrameworkEnv.RuntimeMode.PATCH_EMBEDDED -> "免 root · 内置模式"
                            FrameworkEnv.RuntimeMode.PATCH_LOCAL -> "免 root · 本地模式"
                            FrameworkEnv.RuntimeMode.UNKNOWN -> "未检测到运行框架"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (mode) {
                        FrameworkEnv.RuntimeMode.LSPOSED ->
                            if (fs.connected && fs.active)
                                "${fs.frameworkName} ${fs.frameworkVersion} · API ${fs.apiVersion}\n" +
                                    "在「作用域」页点应用打勾，系统会弹「允许作用域」授权框；之后实时启停无需重启应用。"
                            else
                                "服务连接中…若长时间停留在此状态，请确认已在 LSPosed 管理器启用本模块，并重启过一次手机。"
                        FrameworkEnv.RuntimeMode.PATCH_EMBEDDED ->
                            "本模块已被嵌进目标应用，对该应用自动生效，无需任何配置。\n" +
                                "作用域在打补丁时就固定为宿主自身（框架行为），这里不需要也不会去请求作用域。"
                        FrameworkEnv.RuntimeMode.PATCH_LOCAL ->
                            "免 root 本地模式：**不需要连接 Xposed 框架服务，也不请求作用域**。\n" +
                                "哪些应用被注入由补丁管理器决定（打补丁时给目标应用勾选本模块）；\n" +
                                "本框架不允许运行时改作用域，所以直接在「作用域」页点应用打勾即可实时启停重定向。\n" +
                                "还没给应用打补丁的，点下方按钮到管理器里勾选本模块后重新打补丁。"
                        FrameworkEnv.RuntimeMode.UNKNOWN ->
                            "没检测到 LSPosed，也没检测到已安装的 LSPatch/NPatch 管理器。\n" +
                                "· 用 LSPosed：装模块 APK 后到管理器启用，再重启一次手机；\n" +
                                "· 用免 root：装 NPatch/LSPatch 管理器，或直接用内置模式打补丁。\n" +
                                "「作用域」页仍可管理启停名单，但要等框架真正注入后才会生效。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mode == FrameworkEnv.RuntimeMode.PATCH_LOCAL ||
                    mode == FrameworkEnv.RuntimeMode.UNKNOWN
                ) {
                    Spacer(Modifier.height(12.dp))
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
            "勾选=下次调起相机立即转为相册；取消=立即恢复原相机。都不需要重启应用（LSPosed 下首次勾选后需重启一次完成注入）。")

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
 * **任何运行模式都能管理，不以「框架服务是否连接」为门槛**（用户明确要求）：
 *  - LSPOSED：写生效名单 + `scope.request()`（框架弹「允许作用域」授权框）；
 *  - PATCH_LOCAL：写生效名单，**绝不请求作用域**——本地模式下 UI 进程不被注入、拿不到服务，
 *    且 NPatch 明确「scope is set by patching, cannot be granted at runtime」，请求必失败还弹错误提示；
 *    注入哪些应用由补丁管理器在打补丁时决定，这里只管「注入后要不要动手」；
 *  - PATCH_EMBEDDED：模块嵌在宿主里自动生效，UI 通常不会被打开；
 *  - UNKNOWN：仍允许写生效名单（框架真注入后即生效），并提示去装/启用框架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeScreen(
    fs: FrameworkState,
    mode: FrameworkEnv.RuntimeMode,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    // 只有 LSPosed（服务已连）才有「框架真实作用域」概念；其它模式一律空集，不作为交互门槛
    val canManageScope = mode == FrameworkEnv.RuntimeMode.LSPOSED && fs.connected

    var apps by remember { mutableStateOf<List<AppListLoader.AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = runCatching { AppListLoader.load(context) }.getOrNull() }

    // 注入层（框架真实作用域）与生效层（名单），任何变更后 refreshTick++ 重读
    var refreshTick by remember { mutableIntStateOf(0) }
    var realScope by remember { mutableStateOf<Set<String>>(emptySet()) }
    var enabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(refreshTick, canManageScope) {
        if (canManageScope) {
            realScope = runCatching { YukiHook.Module.scope.packages.toSet() }.getOrDefault(emptySet())
        } else {
            realScope = emptySet()
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
                    // 置顶依据用「生效名单」而非框架作用域：本地/未知模式下 realScope 恒空，置顶会失效
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

        // 状态说明卡（按模式给不同说明；UNKNOWN 才用警示色，本地模式不是异常态）
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (mode == FrameworkEnv.RuntimeMode.UNKNOWN)
                    MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.scope_count, enabled.size),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (mode) {
                        FrameworkEnv.RuntimeMode.LSPOSED ->
                            if (canManageScope) stringResource(R.string.scope_hint_lsp)
                            else stringResource(R.string.scope_hint_off)
                        FrameworkEnv.RuntimeMode.PATCH_LOCAL -> stringResource(R.string.scope_hint_patch_local)
                        FrameworkEnv.RuntimeMode.PATCH_EMBEDDED -> stringResource(R.string.scope_hint_patch_embedded)
                        FrameworkEnv.RuntimeMode.UNKNOWN -> stringResource(R.string.scope_hint_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                when (mode) {
                    FrameworkEnv.RuntimeMode.LSPOSED -> if (!canManageScope) {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { openLsposedManager(context) }) {
                            Text(stringResource(R.string.scope_open_lsp))
                        }
                    }
                    FrameworkEnv.RuntimeMode.PATCH_LOCAL, FrameworkEnv.RuntimeMode.UNKNOWN -> {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { openPatchManager(context, onMessage) }) {
                            Text(stringResource(R.string.patch_open_manager))
                        }
                    }
                    FrameworkEnv.RuntimeMode.PATCH_EMBEDDED -> Unit
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
                    // 「注入是否就绪」按模式取不同真相源：
                    //  - LSPOSED：框架作用域（realScope）
                    //  - PATCH_LOCAL：该应用是否已用本模块打过补丁（realScope 在此模式恒空，不能用）
                    //  - PATCH_EMBEDDED：框架把作用域固定为宿主，凡出现在列表即视为已就绪
                    //  - UNKNOWN：未知，按未就绪显示（勾了也不会生效，文案会提示）
                    val injectReady = when (mode) {
                        FrameworkEnv.RuntimeMode.LSPOSED -> inScope
                        FrameworkEnv.RuntimeMode.PATCH_LOCAL -> entry.info.isPatched
                        FrameworkEnv.RuntimeMode.PATCH_EMBEDDED -> true
                        FrameworkEnv.RuntimeMode.UNKNOWN -> false
                    }
                    AppRow(
                        entry = entry,
                        isEnabled = isEnabled,
                        injectReady = injectReady,
                        onClick = {
                            // 任何模式都能管理生效名单，不再以「服务连接」为门槛
                            //（本地模式 UI 进程本就拿不到框架服务，那是常态不是异常）
                            val action = if (isEnabled || injectReady) Action.DISABLE else Action.ENABLE
                            pending = PendingAction(pkg, entry.info.label, action, entry.info.isPatched)
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
                    // 本地模式不弹授权框，必须用专属文案，否则用户会一直等一个不会出现的系统弹框
                    stringResource(
                        when {
                            act.action == Action.DISABLE -> R.string.confirm_remove_body
                            mode == FrameworkEnv.RuntimeMode.PATCH_LOCAL -> R.string.confirm_add_body_local
                            else -> R.string.confirm_add_body
                        },
                        act.label,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    // RemotePreferences 只在框架服务真正可用时才写；否则只写模块私有 prefs（Provider 供宿主读）
                    val remoteWritable = fs.connected
                    when (act.action) {
                        Action.ENABLE -> {
                            // 生效名单立即写入（主通道=模块私有 prefs，经 ScopeProvider 供宿主跨进程读；
                            // 服务可用时额外镜像一份到框架 RemotePreferences）
                            enabled = RedirectConfig.enable(context, act.pkg, remoteWritable)
                            refreshTick++
                            when (mode) {
                                FrameworkEnv.RuntimeMode.LSPOSED -> {
                                    // 注入层走框架官方通道（弹「允许作用域」授权框）
                                    if (canManageScope) {
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
                                        onMessage("已启用「${act.label}」；框架服务未连接，请到 LSPosed 管理器手动勾选作用域")
                                    }
                                }
                                FrameworkEnv.RuntimeMode.PATCH_LOCAL -> {
                                    // 本地模式：绝不请求作用域（框架必拒 + UI 拿不到服务）；
                                    // 注入由补丁管理器在打补丁时决定，这里只管「注入后要不要动手」
                                    onMessage(
                                        if (act.isPatched)
                                            "已启用「${act.label}」：该应用已打补丁，下次调起相机立即转为相册"
                                        else
                                            "已启用「${act.label}」：该应用还没用本模块打补丁，请到补丁管理器给它勾选本模块后重新打补丁",
                                    )
                                }
                                FrameworkEnv.RuntimeMode.PATCH_EMBEDDED ->
                                    onMessage("内置模式下模块对宿主自动生效，此处名单不影响该宿主。")
                                FrameworkEnv.RuntimeMode.UNKNOWN ->
                                    onMessage("已启用「${act.label}」（名单已写入）；未检测到框架，需框架实际注入后才会生效。")
                            }
                        }
                        Action.DISABLE -> {
                            // 取消：生效名单立即移除（宿主下次调起立即恢复原生）
                            enabled = RedirectConfig.disable(context, act.pkg, remoteWritable)
                            refreshTick++
                            // 注入层同步移除仅 LSPosed 有意义；补丁框架的作用域由管理器管，不在此动
                            if (mode == FrameworkEnv.RuntimeMode.LSPOSED && canManageScope && realScope.contains(act.pkg)) {
                                runCatching { YukiHook.Module.scope.remove(act.pkg) }
                                    .onFailure { onMessage("移出作用域失败：${it.message}（已立即停用）") }
                            }
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
/**
 * 待确认操作。
 * @param isPatched 该应用是否已用本模块打过补丁（本地模式下决定提示「立即生效」还是「请先去打补丁」）。
 *   随操作一起快照下来，因为确认框在 LazyColumn 之外、访问不到 item 作用域的局部变量。
 */
private data class PendingAction(
    val pkg: String,
    val label: String,
    val action: Action,
    val isPatched: Boolean = false,
)
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

/**
 * 应用行：图标 + 名称 + 包名 + 状态徽标 + 勾选图标。整行点击=启用/取消（唯一交互）。
 *
 * @param injectReady 该应用是否「已就绪被注入」（LSPosed=在框架作用域内；本地模式=已用本模块打过补丁）。
 *   已勾选但未就绪时显示「待生效」——用户据此知道还差一步（去授权 / 去打补丁），
 *   避免勾了没反应却不知原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRow(
    entry: AppListLoader.AppEntry,
    isEnabled: Boolean,
    injectReady: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.info.label, maxLines = 1) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.info.packageName, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                when {
                    isEnabled && injectReady -> ScopeChip("已生效", MaterialTheme.colorScheme.primary)
                    isEnabled && !injectReady -> ScopeChip("待生效", MaterialTheme.colorScheme.tertiary)
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
