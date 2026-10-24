package com.richkobe.capturetophotopicker.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject

/**
 * 运行框架环境 / 模式检测。
 *
 * 本模块支持四种运行环境（[RuntimeMode]）：
 *  - [RuntimeMode.LSPOSED]：root + LSPosed（libxposed API 102）。作用域经
 *    `YukiHook.Module.scope` 官方通道读写，`requestScope` 会由框架弹「允许作用域」授权框。
 *  - [RuntimeMode.PATCH_EMBEDDED]：LSPatch/NPatch **内置(集成/embed)模式**。模块 APK 被嵌进目标
 *    App 的补丁包，框架把作用域固定为宿主包名（NPatch `EmbeddedXposedService.getScope()` 实测返回
 *    `singletonList(hostPackageName)`），随宿主自动加载。模块**没有独立 App 入口，用户没机会拨开关**，
 *    故自动生效。此模式下模块 UI 通常根本不会被打开。
 *  - [RuntimeMode.PATCH_LOCAL]：LSPatch/NPatch **本地(经理/local)模式**。模块作为独立 App 安装，
 *    注入哪些宿主由用户在补丁管理器里给目标 App 勾选模块决定（打补丁时固化）。
 *    **运行时 `requestScope()` 必然失败**——NPatch 源码原文：
 *    "scope is set by patching in NPatch; it cannot be granted at runtime"（设计行为，非 bug）。
 *    所以本地模式下模块**绝不请求作用域**，只在软件内管理「生效名单」。
 *  - [RuntimeMode.UNKNOWN]：框架服务未连上且探测不到补丁环境（未激活，或首次启动服务还没绑）。
 *
 * ⚠️ 关键约束（用户明确要求）：**模式检测不得依赖框架服务是否连接**。本地模式下模块 UI 进程
 * 本身不被注入、拿不到 XposedService，`observeFrameworkService` 永远回调 false；
 * 若把「管理作用域」门控在服务连接上，本地模式就成了死路。故 [detectMode] 在服务不可用时
 * 走「补丁管理器是否安装」的本地探测兜底。
 *
 * ⚠️ 内置模式检测判据的血泪教训（2026-10-24 实机取证，NPatch 1.0.9-833）：
 * **绝不能用 `appInfo.sourceDir` 开 zip 找内嵌模块**。签名绕过模式下框架把宿主路径重定向到
 * cache 里解出的 **origin.apk（原始未打补丁包）**：
 * `OriginApkHelper.prepareOriginApk()` → `SigBypass.setPaths(cacheApkPath, installedApkPath)` →
 * `appInfo.sourceDir = cacheApkPath`（NPatch LSPApplication.java:482/483/529）。
 * origin.apk 里没有 assets/npatch/...，按 sourceDir 检测必然 false → 静默不生效（不崩、无日志）。
 * 正解＝读宿主 manifest 的补丁配置 meta-data（走 PackageManager 查 PMS，不受重定向影响）。
 */
object FrameworkEnv {

    /** 本模块包名。 */
    const val MODULE_PKG = "com.richkobe.capturetophotopicker"

    /** 补丁框架写进宿主 manifest 的配置 meta-data 键（NPatch 实测为 npatch；LSPatch 同源约定为 lspatch）。 */
    private val PATCH_CONFIG_META_KEYS = listOf("npatch", "lspatch")

    /** 补丁 loader stub 的 appComponentFactory 特征片段（NPatch 与 LSPatch 通用）。 */
    private const val METALOADER_STUB_MARK = "metaloader.LSPAppComponentFactoryStub"

    /**
     * 补丁管理器候选包名（按优先级）。
     * NPatch 实测为 top.nkbe.npatch（从补丁包 config.json 的 managerPackageName 取得，
     * 凭印象写 com.npatch.manager 会打不开）；LSPatch 官方为 org.lsposed.lspatch。
     */
    private val PATCH_MANAGER_PKGS = listOf("top.nkbe.npatch", "org.lsposed.lspatch")

    /** 运行模式。 */
    enum class RuntimeMode {
        /** root + LSPosed：作用域走框架官方通道，可弹授权框。 */
        LSPOSED,

        /** LSPatch/NPatch 本地(经理)模式：不请求作用域，只在软件内管生效名单。 */
        PATCH_LOCAL,

        /** LSPatch/NPatch 内置(集成)模式：随宿主自动生效，无需任何配置。 */
        PATCH_EMBEDDED,

        /** 探测不到框架（未激活 / 服务尚未连上）。仍允许管理生效名单，不做作用域请求。 */
        UNKNOWN,
    }

    // ============================ UI 侧：模式检测（不依赖服务连接） ============================

    /** UI 侧模式缓存（进程内不变）。 */
    @Volatile
    private var cachedUiMode: RuntimeMode? = null

    /**
     * UI 侧检测当前运行模式。
     *
     * @param frameworkName  `YukiHook.Status.Executor.name`（服务未连时传 null/空）
     * @param serviceConnected 框架服务是否已连接（`observeFrameworkService` 的 available）
     * @param context        模块 UI 的 Context（用于探测补丁管理器是否安装）
     *
     * 判定顺序：
     *  1. 服务已连 + 框架名是 LSPatch/NPatch → 补丁框架。再判内置/本地：
     *     内置模式下模块 UI 根本不作为独立 App 运行，所以「UI 能跑起来」本身即本地模式的强证据；
     *     仍用「模块是否独立安装」二次确认（独立安装=本地）。
     *  2. 服务已连 + 其它框架名（LSPosed 等）→ [RuntimeMode.LSPOSED]。
     *  3. 服务未连：探测设备上是否装了补丁管理器 → 装了判 [RuntimeMode.PATCH_LOCAL]
     *     （本地模式下 UI 进程本来就拿不到服务，这是常态而非异常）；没装判 [RuntimeMode.UNKNOWN]。
     *
     * **任何分支都不阻止用户管理生效名单**——UNKNOWN 也允许操作，只是不做作用域请求。
     */
    fun detectMode(context: Context, frameworkName: String?, serviceConnected: Boolean): RuntimeMode {
        cachedUiMode?.let { return it }
        val mode = runCatching { resolveUiMode(context, frameworkName, serviceConnected) }
            .getOrDefault(RuntimeMode.UNKNOWN)
        cachedUiMode = mode
        return mode
    }

    /** 清除 UI 侧模式缓存（服务连接状态变化后重新判定）。 */
    fun invalidateUiModeCache() {
        cachedUiMode = null
    }

    private fun resolveUiMode(
        context: Context,
        frameworkName: String?,
        serviceConnected: Boolean,
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
        // 服务未连：本地模式的常态（UI 进程不被注入）。装了补丁管理器即判本地模式。
        return if (isAnyPatchManagerInstalled(context.packageManager)) RuntimeMode.PATCH_LOCAL
        else RuntimeMode.UNKNOWN
    }

    /** 框架名是否 LSPatch/NPatch 系。 */
    fun isPatchFramework(frameworkName: String): Boolean =
        frameworkName.contains("LSPatch", true) ||
            frameworkName.contains("NPatch", true) ||
            frameworkName.contains("Patch", true)

    /** 设备上是否装了任一补丁管理器。 */
    fun isAnyPatchManagerInstalled(pm: PackageManager): Boolean =
        PATCH_MANAGER_PKGS.any { isPackageInstalled(pm, it) }

    /** 本模块是否作为独立 App 安装。 */
    fun isModuleStandaloneInstalled(pm: PackageManager): Boolean = isPackageInstalled(pm, MODULE_PKG)

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        pm.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /** 找到已安装的补丁管理器包名（供「打开补丁管理器」按钮用）；都没装返回 null。 */
    fun findInstalledPatchManager(pm: PackageManager): String? =
        PATCH_MANAGER_PKGS.firstOrNull { isPackageInstalled(pm, it) }

    // ============================ 宿主侧：内置模式检测（hook 用，结果缓存） ============================

    @Volatile
    private var cachedEmbedded: Boolean? = null

    /**
     * 判断当前宿主是否为「本模块被内置嵌入的补丁 App」（宿主侧调用，结果缓存）。
     *
     * @param hostPackageName 宿主包名
     * @param hostAppInfo     框架给宿主的 ApplicationInfo（取 metaData 与 appComponentFactory）
     * @param packageManager  任一可用 PackageManager（metaData 为空时向 PMS 重取）；null 则跳过兜底
     *
     * 异常/信息缺失一律返回 false——退回生效名单逻辑，宁可少生效也不擅自改写宿主行为。
     */
    fun isEmbeddedHost(
        hostPackageName: String,
        hostAppInfo: ApplicationInfo?,
        packageManager: PackageManager?,
    ): Boolean {
        cachedEmbedded?.let { return it }
        val result = runCatching { detectEmbedded(hostPackageName, hostAppInfo, packageManager) }
            .getOrDefault(false)
        cachedEmbedded = result
        return result
    }

    private fun detectEmbedded(
        hostPackageName: String,
        hostAppInfo: ApplicationInfo?,
        packageManager: PackageManager?,
    ): Boolean {
        // 判据 A：补丁配置 meta-data（最权威，能精确区分内置/本地）
        val metaData = hostAppInfo?.metaData
            ?: packageManager?.let { pm ->
                runCatching {
                    pm.getApplicationInfo(hostPackageName, PackageManager.GET_META_DATA).metaData
                }.getOrNull()
            }
        readPatchConfig(metaData)?.let { return it.isEmbedded }

        // 判据 B：loader stub 特征 + 模块未独立安装（仅 A 取不到时兜底）。
        // appComponentFactory 是 API 28(P)+ 字段；Android 11+ 包可见性可能让「未独立安装」假阴性，
        // 误判方向是「自动生效」而非「静默失效」，可接受。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            hostAppInfo?.appComponentFactory?.contains(METALOADER_STUB_MARK) == true &&
            packageManager != null &&
            !isModuleStandaloneInstalled(packageManager)
        ) {
            return true
        }
        return false
    }

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
