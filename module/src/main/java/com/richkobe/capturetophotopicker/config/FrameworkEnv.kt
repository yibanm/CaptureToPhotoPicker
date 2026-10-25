package com.richkobe.capturetophotopicker.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import com.highcapable.yukihookapi.YukiHook
import org.json.JSONObject

/**
 * 运行框架环境 / 模式检测。
 *
 * 本模块支持四种运行环境（[RuntimeMode]）：
 *  - [RuntimeMode.LSPOSED]：LSPosed（libxposed API 102）。作用域经 `YukiHook.Module.scope`
 *    官方通道读写，`requestScope` 由框架弹「允许作用域」授权框。
 *  - [RuntimeMode.PATCH_EMBEDDED]：LSPatch/NPatch **内置(集成/embed)模式**。模块 APK 被嵌进目标
 *    App 补丁包，框架把作用域固定为宿主包名（NPatch `EmbeddedXposedService.getScope()` 返回
 *    `singletonList(hostPackageName)`），随宿主自动加载、自动生效，无需任何配置。
 *    此环境的 `requestScope` 才会失败（"scope is set by patching in NPatch; it cannot be
 *    granted at runtime"）——注意这是**内置模式**的限制，不是本地模式的。
 *  - [RuntimeMode.PATCH_LOCAL]：LSPatch/NPatch **本地(经理/local)模式**。模块作为独立 App 安装，
 *    作用域由补丁管理器动态管理（README："Modules and scope are managed dynamically by NPatch
 *    Manager, so changing or updating modules requires no re-patching"）。
 *    **判定必须有连接实证**：主动向管理器的 RemoteApiProvider 请求 XposedService binder 并
 *    验证成功才算（见 [tryConnectPatchManager]）。「设备上装了管理器」不构成判据——
 *    LSPosed 模块被关闭时服务同样连不上，凭安装探测会把那种状态谎报成本地模式（2026-10-24 用户指正的 bug）。
 *  - [RuntimeMode.UNKNOWN]：既没连上 LSPosed 服务，也没连上补丁管理器。如实显示「未检测到」，
 *    **不猜测**。生效名单仍可管理（框架真注入后即生效）。
 *
 * 本地模式连接通道（NPatch 源码实证，manager/…/RemoteApiProvider.kt + XposedServiceBinder.kt）：
 *  1. 管理器暴露 ContentProvider `content://top.nkbe.npatch.remote`，
 *     `call("getRemoteService", null, {modulePackageName=本模块包名})` → 返回 Bundle 里的可写
 *     IXposedService binder（校验调用方 uid 的包=模块自身、模块已在管理器注册）；
 *  2. 该 binder 的 `getScope`/`requestScope`/`removeScope`/`requestRemotePreferences` **全部真实现**
 *     （requestScope→ConfigManager.activateModule 写入管理器数据库并回调 approved）；
 *  3. 把 binder 喂给本模块自己的 XposedProvider（libxposed service 库随 manifest merger 并入，
 *     authority=`<模块包名>.XposedService`，method=`IXposedService.SEND_BINDER`）→
 *     `XposedServiceHelper.onBinderReceived` → YukiHook 底座 `LibXposedService` 点亮 →
 *     `YukiHook.Status` / `Module.scope` / `preferences()` 在 UI 进程全部可用。
 *  ⚠️ 不可给底座喂第二个 binder：`LibXposedService.service` 取 `services.values.singleOrNull()`，
 *  两个 binder 并存会让它变 null、全部服务瘫痪。故 [tryConnectPatchManager] 前置检查
 *  `YukiHook.Status.isModuleActive`（已有框架服务就不再连管理器），调用方也只在服务未连时尝试。
 *
 * ⚠️ 内置模式检测判据的血泪教训（2026-10-24 实机取证，NPatch 1.0.9-833）：
 * **绝不能用 `appInfo.sourceDir` 开 zip 找内嵌模块**。签名绕过模式下框架把宿主路径重定向到
 * cache 里解出的 **origin.apk（原始未打补丁包）**（NPatch LSPApplication.java:482/483/529），
 * 按 sourceDir 检测必然 false → 静默不生效。正解＝读宿主 manifest 的补丁配置 meta-data
 * （走 PackageManager 查 PMS，不受重定向影响）。
 */
object FrameworkEnv {

    private const val TAG = "CapToPickerEnv"

    /** 本模块包名。 */
    const val MODULE_PKG = "com.richkobe.capturetophotopicker"

    /** 本模块 XposedProvider 的 authority（libxposed service 库 manifest 约定 `${applicationId}.XposedService`）。 */
    private const val SELF_PROVIDER_AUTHORITY = "$MODULE_PKG.XposedService"

    /** 补丁框架写进宿主 manifest 的配置 meta-data 键（NPatch 实测为 npatch；LSPatch 同源约定为 lspatch）。 */
    private val PATCH_CONFIG_META_KEYS = listOf("npatch", "lspatch")

    /** 补丁 loader stub 的 appComponentFactory 特征片段（NPatch 与 LSPatch 通用）。 */
    private const val METALOADER_STUB_MARK = "metaloader.LSPAppComponentFactoryStub"

    /**
     * 补丁管理器候选包名（按优先级）。
     * NPatch 实测为 top.nkbe.npatch（从补丁包 config.json 的 managerPackageName 取得）；
     * LSPatch 官方为 org.lsposed.lspatch。
     */
    val PATCH_MANAGER_PKGS = listOf("top.nkbe.npatch", "org.lsposed.lspatch")

    /** RemoteApiProvider 的 call 协议常量（NPatch manager/RemoteApiProvider.kt 实证）。 */
    private const val METHOD_GET_REMOTE_SERVICE = "getRemoteService"
    private const val KEY_MODULE_PACKAGE = "modulePackageName"
    private const val KEY_BINDER = "binder"

    /**
     * libxposed 协议常量（interface/src/main/aidl/…/IXposedService.aidl 原文实证）。
     * IXposedService 属 libxposed interface artifact，本模块未依赖它，故用字面值：
     *  - SEND_BINDER = "SendBinder"：XposedProvider.call() 认的方法名；
     *  - AUTHORITY_SUFFIX = ".XposedService"：service 库 XposedProvider 的 authority 后缀。
     */
    private const val SEND_BINDER = "SendBinder"

    /** 运行模式。 */
    enum class RuntimeMode {
        /** LSPosed：作用域走框架官方通道，可弹授权框。 */
        LSPOSED,

        /** LSPatch/NPatch 本地(经理)模式：已实证连接管理器，作用域由管理器动态管理。 */
        PATCH_LOCAL,

        /** LSPatch/NPatch 内置(集成)模式：随宿主自动生效，无需任何配置。 */
        PATCH_EMBEDDED,

        /** 什么都没连上。如实显示未检测到，不猜测。 */
        UNKNOWN,
    }

    // ============================ UI 侧：补丁管理器连接（本地模式的唯一判据） ============================

    /** 是否已实证连上补丁管理器（binder 获取 + IXposedService 验证 + 喂入底座成功）。 */
    @Volatile
    var patchManagerConnected: Boolean = false
        private set

    /** 连上的补丁框架名（如 "NPatch"），未连为 null。 */
    @Volatile
    var patchManagerName: String? = null
        private set

    /**
     * 尝试连接补丁管理器并点亮 YukiHook 底座服务（本地模式的**连接实证**）。
     *
     * 流程：遍历候选管理器 → `content://<mgr>.remote` call `getRemoteService` 拿 binder →
     * 用 `IXposedService.Stub.asInterface(binder).frameworkName` 验证可用 →
     * 喂入本模块自己的 XposedProvider（SEND_BINDER）→ 底座 onServiceBind → Status/scope/preferences 全活。
     *
     * 前置保护：若 `YukiHook.Status.isModuleActive` 已为 true（LSPosed 服务已连或已喂过），
     * 直接返回——**绝不能喂第二个 binder**（底座 singleOrNull 会变 null 全瘫，见类注释）。
     *
     * 阻塞 IPC，须在 IO 线程调用。全程 runCatching，失败返回 false（模式判 UNKNOWN，不猜）。
     */
    fun tryConnectPatchManager(context: Context): Boolean {
        if (patchManagerConnected) return true
        // 已有框架服务（LSPosed 已连）就不再连管理器，避免双 binder 打爆底座 singleOrNull
        if (runCatching { YukiHook.Status.isModuleActive }.getOrDefault(false)) {
            return false
        }
        for (mgr in PATCH_MANAGER_PKGS) {
            if (!isPackageInstalled(context.packageManager, mgr)) continue
            // 1) 向管理器 RemoteApiProvider 请求可写服务 binder
            val binder = runCatching {
                context.contentResolver.call(
                    Uri.parse("content://$mgr.remote"),
                    METHOD_GET_REMOTE_SERVICE,
                    null,
                    Bundle().apply { putString(KEY_MODULE_PACKAGE, MODULE_PKG) },
                )?.getBinder(KEY_BINDER)
            }.onFailure { Log.w(TAG, "call $mgr.remote 失败: ${it.message}") }.getOrNull() ?: continue
            // 2) 喂入本模块自己的 XposedProvider（libxposed service 库并入的组件）
            //    → XposedServiceHelper.onBinderReceived → 底座 LibXposedService 点亮
            val fed = runCatching {
                context.contentResolver.call(
                    Uri.parse("content://$SELF_PROVIDER_AUTHORITY"),
                    SEND_BINDER,
                    null,
                    Bundle().apply { putBinder(KEY_BINDER, binder) },
                ) != null
            }.onFailure { Log.w(TAG, "喂入自身 XposedProvider 失败: ${it.message}") }.getOrDefault(false)
            if (!fed) continue
            // 3) 连接实证：底座 services 表登记成功 → isModuleActive 变 true，
            //    框架名从 binder 读（getFrameworkName = IXposedService 事务码 2）
            val active = runCatching { YukiHook.Status.isModuleActive }.getOrDefault(false)
            if (active) {
                patchManagerConnected = true
                patchManagerName = runCatching { YukiHook.Status.Executor.name }.getOrNull() ?: mgr
                Log.i(TAG, "已连接补丁管理器 $mgr（frameworkName=$patchManagerName）")
                return true
            }
        }
        return false
    }

    // ============================ UI 侧：模式检测 ============================

    @Volatile
    private var cachedUiMode: RuntimeMode? = null

    /**
     * UI 侧检测当前运行模式。
     *
     * @param frameworkName         `YukiHook.Status.Executor.name`（服务未连时传 null/空）
     * @param serviceConnected      框架服务是否已连接（observeFrameworkService 的 available）
     * @param patchManagerConnected 是否已实证连上补丁管理器（[tryConnectPatchManager] 的结果）
     *
     * 判定（全部基于连接实证，不基于「装了什么」猜测）：
     *  1. 服务已连 + 框架名是补丁系 → 模块 UI 能独立运行说明模块是单独安装的 App → PATCH_LOCAL；
     *  2. 服务已连 + 其它框架名（LSPosed 等）→ LSPOSED；
     *  3. 服务未连但 [patchManagerConnected]=true（binder 已喂入、回调尚未到达的竞态窗口）→ PATCH_LOCAL；
     *  4. 其它 → UNKNOWN（如实报未检测到；「装了管理器但没连上」也是 UNKNOWN——
     *     可能模块没在管理器注册、可能 LSPosed 模块被关闭，不猜）。
     */
    fun detectMode(
        context: Context,
        frameworkName: String?,
        serviceConnected: Boolean,
        patchManagerConnected: Boolean = FrameworkEnv.patchManagerConnected,
    ): RuntimeMode {
        cachedUiMode?.let { return it }
        val mode = runCatching { resolveUiMode(context, frameworkName, serviceConnected, patchManagerConnected) }
            .getOrDefault(RuntimeMode.UNKNOWN)
        cachedUiMode = mode
        return mode
    }

    /** 清除 UI 侧模式缓存（连接状态变化后重新判定）。 */
    fun invalidateUiModeCache() {
        cachedUiMode = null
    }

    private fun resolveUiMode(
        context: Context,
        frameworkName: String?,
        serviceConnected: Boolean,
        patchManagerConnected: Boolean,
    ): RuntimeMode {
        val name = frameworkName.orEmpty()
        if (serviceConnected && name.isNotEmpty()) {
            return if (isPatchFramework(name)) {
                // 模块 UI 能独立运行 ⇒ 模块是单独安装的 App ⇒ 本地模式
                if (isModuleStandaloneInstalled(context.packageManager)) RuntimeMode.PATCH_LOCAL
                else RuntimeMode.PATCH_EMBEDDED
            } else {
                RuntimeMode.LSPOSED
            }
        }
        // 服务未连：只有拿过 binder 实证才算本地模式；否则如实 UNKNOWN（修复「装了管理器就谎报本地模式」）
        return if (patchManagerConnected || this.patchManagerConnected) RuntimeMode.PATCH_LOCAL
        else RuntimeMode.UNKNOWN
    }

    /** 框架名是否 LSPatch/NPatch 系。 */
    fun isPatchFramework(frameworkName: String): Boolean =
        frameworkName.contains("LSPatch", true) ||
            frameworkName.contains("NPatch", true) ||
            frameworkName.contains("Patch", true)

    /** 设备上是否装了任一补丁管理器（仅用于「打开管理器」按钮与文案，不作模式判据）。 */
    fun isAnyPatchManagerInstalled(pm: PackageManager): Boolean =
        PATCH_MANAGER_PKGS.any { isPackageInstalled(pm, it) }

    /** 本模块是否作为独立 App 安装。 */
    fun isModuleStandaloneInstalled(pm: PackageManager): Boolean = isPackageInstalled(pm, MODULE_PKG)

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        pm.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    // ============================ 宿主侧：补丁宿主检测（hook 用，结果缓存） ============================

    @Volatile
    private var cachedEmbedded: Boolean? = null

    /**
     * 判断当前宿主是否为「本模块被内置嵌入的补丁 App」（内置模式自动生效的判据）。
     * 异常/信息缺失一律返回 false——退回生效名单逻辑，宁可少生效也不擅自改写宿主行为。
     */
    fun isEmbeddedHost(
        hostPackageName: String,
        hostAppInfo: ApplicationInfo?,
        packageManager: PackageManager?,
    ): Boolean {
        cachedEmbedded?.let { return it }
        val result = runCatching {
            readPatchConfig(hostMetaData(hostPackageName, hostAppInfo, packageManager))?.isEmbedded == true
        }.getOrDefault(false)
        cachedEmbedded = result
        return result
    }

    @Volatile
    private var cachedPatchedHost: Boolean? = null

    /**
     * 判断当前宿主是否为「打过补丁且带补丁配置的 App」（内置或本地模式皆算）。
     *
     * 语义：模块代码能跑在这个宿主进程里，且宿主 manifest 带 npatch/lspatch 配置 ⇒
     * 该宿主是补丁产物、模块是被管理器/打补丁流程明确选进来的 ⇒ **用户意图已明确，
     * 直接生效，不查生效名单**（用户要求：「本地模式检测到被选择作用域之后直接生效，
     * 不用在应用里点」；内置模式同理）。
     *
     * LSPosed 注入的宿主没有补丁 meta-data → false → 走生效名单实时开关。
     */
    fun isPatchedHost(
        hostPackageName: String,
        hostAppInfo: ApplicationInfo?,
        packageManager: PackageManager?,
    ): Boolean {
        cachedPatchedHost?.let { return it }
        val result = runCatching {
            val cfg = readPatchConfig(hostMetaData(hostPackageName, hostAppInfo, packageManager))
            if (cfg != null) true
            else loaderStubFallback(hostAppInfo, packageManager)
        }.getOrDefault(false)
        cachedPatchedHost = result
        return result
    }

    /** 宿主 metaData：优先用框架给的，为空则向 PMS 重取（sourceDir 可能被重定向，PMS 查询不受影响）。 */
    private fun hostMetaData(
        hostPackageName: String,
        hostAppInfo: ApplicationInfo?,
        packageManager: PackageManager?,
    ): Bundle? = hostAppInfo?.metaData
        ?: packageManager?.let { pm ->
            runCatching {
                pm.getApplicationInfo(hostPackageName, PackageManager.GET_META_DATA).metaData
            }.getOrNull()
        }

    /** 兜底判据：loader stub 特征 + 模块未独立安装（仅 meta-data 拿不到时）。 */
    private fun loaderStubFallback(hostAppInfo: ApplicationInfo?, packageManager: PackageManager?): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            hostAppInfo?.appComponentFactory?.contains(METALOADER_STUB_MARK) == true &&
            packageManager != null &&
            !isModuleStandaloneInstalled(packageManager)

    /**
     * 解析宿主补丁配置 meta-data：返回 useManager 等字段，取不到则 null。
     * useManager=false → 内置(embed)；true → 本地(local)。缺字段按 true（本地）处理，保守。
     */
    fun readPatchConfig(metaData: Bundle?): PatchConfig? {
        if (metaData == null) return null
        for (key in PATCH_CONFIG_META_KEYS) {
            val encoded = runCatching { metaData.getString(key) }.getOrNull() ?: continue
            val json = runCatching {
                JSONObject(String(Base64.decode(encoded, Base64.DEFAULT)))
            }.getOrNull() ?: continue
            return PatchConfig(
                framework = key,
                useManager = json.optBoolean("useManager", true),
                managerPackageName = json.optString("managerPackageName").takeIf { it.isNotEmpty() },
                versionName = json.optJSONObject("lspConfig")?.optString("VERSION_NAME"),
            )
        }
        return null
    }

    /** 补丁配置摘要。 */
    data class PatchConfig(
        val framework: String,
        val useManager: Boolean,
        val managerPackageName: String?,
        val versionName: String?,
    ) {
        /** 是否内置(embed)模式。 */
        val isEmbedded: Boolean get() = !useManager
    }
}
