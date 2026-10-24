package com.richkobe.capturetophotopicker.config

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject

/**
 * 运行框架环境检测。
 *
 * 本模块支持三种运行环境：
 *  1. **LSPosed**（root，libxposed API 102）：作用域经 YukiHook.Module.scope 官方通道管理，
 *     requestScope 由框架弹「允许作用域」授权框；生效开关由模块 UI 写名单；
 *  2. **LSPatch/NPatch 本地(local/经理)模式**（免 root）：模块作为独立 App 单独安装，
 *     作用域由用户在补丁管理器里给目标 App 勾选本模块（运行时 requestScope **必然失败**——
 *     NPatch 源码原文 "scope is set by patching in NPatch; it cannot be granted at runtime"，
 *     属设计行为不是 bug），生效开关仍由本模块 UI 写名单；
 *  3. **LSPatch/NPatch 内置(embed/integrated)模式**（免 root）：模块 APK 被嵌进目标 App 的补丁包，
 *     框架把作用域**固定为宿主包名**（NPatch EmbeddedXposedService.getScope() 实测返回
 *     singletonList(hostPackageName)），模块随宿主启动自动加载。此模式下模块**没有独立安装的
 *     App 入口、用户根本没机会打开 UI 拨开关**，故必须自动生效——
 *     「自动识别本软件、自动捕捉作用域」即此模式。
 *
 * ⚠️ 检测判据的血泪教训（2026-10-24 实机取证，NPatch 1.0.9 / sigBypassLevel=2 / 内置模式）：
 * **绝不能用 `appInfo.sourceDir` 开 zip 找内嵌模块**。NPatch 在签名绕过模式下把宿主的
 * sourceDir/publicSourceDir 重定向到 cache 里解出的 **origin.apk（原始未打补丁的包）**：
 * `OriginApkHelper.prepareOriginApk()` → `SigBypass.setPaths(cacheApkPath, installedApkPath)` →
 * `loadedApkSourceDir = cacheApkPath` → `appInfo.sourceDir = loadedApkSourceDir`
 * （LSPApplication.java:482/483/529）。origin.apk 里没有补丁资源，按 sourceDir 检测必然 false
 * → 内置模式**静默不生效**（不崩、无日志、极难查）。本轮 v1.0.2 的修复只补了 assets 前缀
 * （assets/npatch/modules），因为读的是被重定向的 sourceDir，所以照样无效。
 *
 * 正确判据（按可靠性排序）：
 *  A. **宿主 manifest 的补丁配置 meta-data**（NPatch 实测键名 "npatch"、LSPatch 同源约定 "lspatch"，
 *     值为 base64 编码的 config JSON，含 useManager 字段：false=内置、true=本地）。
 *     走 PackageManager 向 PMS 查**真实已安装补丁包**，不受进程内 sourceDir 重定向影响
 *     ——NPatch 自己解析 sigBypassLevel 用的就是这个手法（LSPApplication.java:186
 *     `getApplicationInfo(pkg, GET_META_DATA).metaData.getString("npatch")`）。
 *     实机解码验证：useManager=false、managerPackageName=top.nkbe.npatch、VERSION_NAME=1.0.9。
 *  B. `appComponentFactory` 被换成补丁 loader stub（NPatch/LSPatch 均为
 *     "…metaloader.LSPAppComponentFactoryStub"）＝补丁环境，且本模块未独立安装＝被嵌入。
 *     仅在 A 拿不到 meta-data 时兜底；注意 Android 11+ 包可见性会让「未独立安装」假阴性，
 *     误判方向是「自动生效」而非「静默失效」，可接受。
 */
object FrameworkEnv {

    /** 本模块包名（判断「模块是否独立安装」用）。 */
    private const val MODULE_PKG = "com.richkobe.capturetophotopicker"

    /** 补丁框架写进宿主 manifest 的配置 meta-data 键。 */
    private val PATCH_CONFIG_META_KEYS = listOf("npatch", "lspatch")

    /** 补丁 loader stub 的 appComponentFactory 特征片段（NPatch 与 LSPatch 通用）。 */
    private const val METALOADER_STUB_MARK = "metaloader.LSPAppComponentFactoryStub"

    /** 检测结果缓存（宿主进程内不变；object 单例按进程隔离，不会串宿主）。 */
    @Volatile
    private var cached: Boolean? = null

    /**
     * 判断当前宿主是否为「本模块被内置嵌入的补丁 App」（宿主侧调用，结果缓存）。
     *
     * @param hostPackageName 宿主包名
     * @param hostAppInfo     框架给宿主的 ApplicationInfo（取 metaData 与 appComponentFactory）
     * @param packageManager  任一可用的 PackageManager（metaData 为空时用它向 PMS 重新取）；null 则跳过兜底
     *
     * 任何异常/信息缺失一律返回 false——退回常规生效名单逻辑，
     * 宁可「少生效」也不要在无法确认用户意图时擅自改写宿主行为。
     */
    fun isEmbeddedHost(
        hostPackageName: String,
        hostAppInfo: ApplicationInfo?,
        packageManager: PackageManager?,
    ): Boolean {
        cached?.let { return it }
        val result = runCatching {
            detect(hostPackageName, hostAppInfo, packageManager)
        }.getOrDefault(false)
        cached = result
        return result
    }

    private fun detect(
        hostPackageName: String,
        hostAppInfo: ApplicationInfo?,
        packageManager: PackageManager?,
    ): Boolean {
        // ---- 判据 A：补丁配置 meta-data（最权威，能精确区分内置/本地）----
        // 先试框架直接给的 metaData；为空则用 PackageManager 向 PMS 重取
        //（进程内 appInfo 可能没带 GET_META_DATA，而 PMS 里存的是真实补丁包）。
        val metaData = hostAppInfo?.metaData
            ?: packageManager?.let { pm ->
                runCatching {
                    pm.getApplicationInfo(hostPackageName, PackageManager.GET_META_DATA).metaData
                }.getOrNull()
            }
        if (metaData != null) {
            for (key in PATCH_CONFIG_META_KEYS) {
                val encoded = runCatching { metaData.getString(key) }.getOrNull() ?: continue
                val json = runCatching {
                    JSONObject(String(Base64.decode(encoded, Base64.DEFAULT)))
                }.getOrNull() ?: continue
                // 解析成功就以 useManager 为唯一判据：false=内置(embed)、true=本地(local)。
                // 缺字段时按 true（本地）处理——保守，避免把本地模式误判成自动生效。
                return !json.optBoolean("useManager", true)
            }
        }

        // ---- 判据 B：loader stub 特征 + 模块未独立安装 ----
        // appComponentFactory 是 API 28(P) 才有的字段，低版本跳过（minSdk 26）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            hostAppInfo?.appComponentFactory?.contains(METALOADER_STUB_MARK) == true &&
            packageManager != null &&
            !isModuleStandaloneInstalled(packageManager)
        ) {
            return true
        }
        return false
    }

    /** 本模块是否作为独立 App 安装（本地模式/LSPosed 下 true；内置模式下只嵌在宿主 assets 里）。 */
    private fun isModuleStandaloneInstalled(pm: PackageManager): Boolean = runCatching {
        pm.getPackageInfo(MODULE_PKG, 0)
        true
    }.getOrDefault(false)

    /**
     * UI 侧判断当前框架是否 LSPatch/NPatch 系（免 root 补丁框架）。
     * 依据 = YukiHook.Status.Executor.name（libxposed 底座下即框架 getFrameworkName()，
     * NPatch 的 EmbeddedXposedService 实测返回 "NPatch"）。
     */
    fun isPatchFramework(frameworkName: String): Boolean =
        frameworkName.contains("LSPatch", true) ||
            frameworkName.contains("NPatch", true) ||
            frameworkName.contains("Patch", true)

    /**
     * 读取宿主补丁配置（诊断/UI 展示用）：返回 useManager 与框架版本，取不到则 null。
     * 与 [detect] 同源，但把解析结果交出去而非只给布尔，便于在 UI 上说明当前处于哪种模式。
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
