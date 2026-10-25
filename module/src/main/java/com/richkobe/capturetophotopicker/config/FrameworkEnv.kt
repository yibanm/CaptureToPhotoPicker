package com.richkobe.capturetophotopicker.config

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject

/**
 * 补丁宿主识别（供 hook 侧判断「自动生效」）。
 *
 * 本模块三种使用方式（界面「状态」页有详细说明，无切换控件）：
 *  - LSPosed：作用域经 `YukiHook.Module.scope` 管理，启停实时开关走 [RedirectConfig] 名单；
 *  - 免 root 内置模式：模块 APK 被嵌进目标应用补丁包，作用域固定为宿主、随宿主自动加载；
 *  - 免 root 本地模式：模块单独安装，打过补丁的宿主由补丁管理器决定注入。
 * 后两种情况下，模块代码能跑在某宿主进程里且该宿主 manifest 带补丁配置，
 * 即说明用户已明确把它选进作用域 ⇒ **直接生效，不查名单**（见 [isPatchedHost]）。
 *
 * ⚠️ 判据血泪教训（2026-10-24 实机取证，NPatch 1.0.9-833）：
 * **绝不能用 `appInfo.sourceDir` 开 zip 找内嵌模块**。签名绕过模式下框架把宿主路径重定向到
 * cache 里解出的 origin.apk（原始未打补丁包）（NPatch LSPApplication.java:482/483/529），
 * 按 sourceDir 检测必然 false → 静默不生效。正解＝读宿主 manifest 的补丁配置 meta-data
 * （走 PackageManager 查 PMS，不受重定向影响）。
 */
object FrameworkEnv {

    /** 补丁框架写进宿主 manifest 的配置 meta-data 键（NPatch 实测为 npatch；LSPatch 同源约定为 lspatch）。 */
    private val PATCH_CONFIG_META_KEYS = listOf("npatch", "lspatch")

    /** 补丁 loader stub 的 appComponentFactory 特征片段（NPatch 与 LSPatch 通用）。 */
    private const val METALOADER_STUB_MARK = "metaloader.LSPAppComponentFactoryStub"

    @Volatile
    private var cachedPatchedHost: Boolean? = null

    /**
     * 判断当前宿主是否为「打过补丁且带补丁配置的 App」（内置或本地模式皆算）。
     *
     * 语义：模块代码能跑在这个宿主进程里，且宿主 manifest 带 npatch/lspatch 配置 ⇒
     * 该宿主是补丁产物、模块是被管理器/打补丁流程明确选进来的 ⇒ 用户意图已明确，直接生效。
     * LSPosed 注入的宿主没有补丁 meta-data → false → 走 [RedirectConfig] 名单实时开关。
     * 异常/信息缺失一律 false（宁可少生效也不擅自改写宿主行为）。
     */
    fun isPatchedHost(
        hostPackageName: String,
        hostAppInfo: ApplicationInfo?,
        packageManager: PackageManager?,
    ): Boolean {
        cachedPatchedHost?.let { return it }
        val result = runCatching {
            val cfg = readPatchConfig(hostMetaData(hostPackageName, hostAppInfo, packageManager))
            cfg != null || loaderStubFallback(hostAppInfo, packageManager)
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

    /** 本模块是否作为独立 App 安装（内置模式下为 false）。 */
    fun isModuleStandaloneInstalled(pm: PackageManager): Boolean = runCatching {
        pm.getPackageInfo("com.richkobe.capturetophotopicker", 0)
        true
    }.getOrDefault(false)

    /**
     * 解析补丁配置 meta-data：返回 useManager 等字段，取不到则 null。
     * UI 侧用它标记「该应用是否已打补丁」（本地模式下框架作用域读不到，只能查 manifest）。
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
