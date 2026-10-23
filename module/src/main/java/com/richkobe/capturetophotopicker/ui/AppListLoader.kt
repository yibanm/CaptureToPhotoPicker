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

        installed.mapNotNull { appInfo ->
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
    }
}
