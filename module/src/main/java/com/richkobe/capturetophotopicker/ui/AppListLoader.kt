package com.richkobe.capturetophotopicker.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装应用加载器。
 *
 * 仅用标准 PackageManager 查询已安装应用（manifest 已声明 QUERY_ALL_PACKAGES）。
 * 图标/标签的读取放在 IO 线程，避免卡 UI；单个应用取信息失败时跳过而不整体崩。
 */
object AppListLoader {

    /** 承载 UI 展示所需的最小信息（含图标）。 */
    data class AppEntry(
        val info: AppInfo,
        val icon: Drawable?,
    )

    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val entries = installed.mapNotNull { appInfo ->
            runCatching {
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                val pkgInfo = runCatching { pm.getPackageInfo(appInfo.packageName, 0) }.getOrNull()
                AppEntry(
                    info = AppInfo(
                        label = label,
                        packageName = appInfo.packageName,
                        installTime = pkgInfo?.firstInstallTime ?: 0L,
                        updateTime = pkgInfo?.lastUpdateTime ?: 0L,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    ),
                    icon = icon,
                )
            }.getOrNull()
        }

        // 额外置顶补一个「系统框架」(packageName=android) 合成条目：
        // getInstalledApplications 不返回 android 包，但 framework scope 的系统级重定向
        // （SystemPickerRedirectHooker 经 loadSystem 注入）要求用户能把「系统框架」勾进作用域。
        // label 硬编码为「系统框架」以便一眼识别；图标取真实 android 图标，取不到则用默认兜底（AppRow 已处理）。
        val frameworkEntry = AppEntry(
            info = AppInfo(
                label = "系统框架",
                packageName = "android",
                installTime = 0L,
                updateTime = 0L,
                isSystemApp = true,
            ),
            icon = runCatching { pm.getApplicationIcon("android") }.getOrNull(),
        )
        listOf(frameworkEntry) + entries
    }
}
