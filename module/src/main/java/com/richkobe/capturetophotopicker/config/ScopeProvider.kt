package com.richkobe.capturetophotopicker.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 生效名单的跨进程只读通道（主通道）。
 *
 * 背景见 [RedirectConfig] 类注释：libxposed RemotePreferences 在「本地模式」下 UI 侧拿不到框架服务，
 * 会静默回退到 UI 自己的私有 prefs，与宿主读到的存储不是同一份 → 开关变假按钮。
 * 本 Provider 提供一条**不依赖框架服务**的通道：UI 写模块私有 prefs（[RedirectConfig.LOCAL_FILE]），
 * 宿主进程经 ContentResolver 查询本 Provider 读到同一份名单。只要模块 App 处于已安装状态即可用
 * （LSPosed 模式、NPatch/LSPatch 本地模式都满足；内置模式下模块未独立安装，但那种模式跳过名单直接生效）。
 *
 * 契约（与 [RedirectConfig.queryProvider] 对应）：
 *  - URI：`content://com.richkobe.capturetophotopicker.config/enabled`
 *  - 返回单列 `pkg` 的 Cursor，每行一个已启用的包名（可能为空集＝未启用任何应用）。
 *  - 只读：insert/update/delete 一律 no-op，避免宿主或第三方篡改名单。
 *
 * 安全：exported=true 但只暴露「哪些包名启用了重定向」这一非敏感信息；不返回任何凭证/路径/隐私数据。
 * 查询方无需特殊权限（ContentResolver 对已知 authority 的查询不受 Android 11+ 包可见性限制）。
 */
class ScopeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("pkg"))
        val ctx = context ?: return cursor
        // 读模块私有 prefs（UI 侧写入的权威副本）；异常时返回空集而非崩溃
        val enabled = runCatching {
            ctx.getSharedPreferences(LOCAL_FILE, android.content.Context.MODE_PRIVATE)
                .getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()
        }.getOrDefault(emptySet())
        enabled.forEach { pkg -> cursor.addRow(arrayOf(pkg)) }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    // 只读通道：写操作全部拒绝（返回 no-op 值，不抛异常以免影响宿主）
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        // 与 RedirectConfig 的私有 prefs 文件名/键保持一致（同进程内直接引用常量会引入耦合，这里就地声明）
        const val LOCAL_FILE = "scope_local"
        const val KEY_ENABLED = "enabled_packages"
    }
}
