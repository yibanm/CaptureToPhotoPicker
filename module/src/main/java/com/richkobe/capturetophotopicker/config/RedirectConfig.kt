package com.richkobe.capturetophotopicker.config

import android.content.Context
import com.highcapable.yukihookapi.hook.factory.preferences
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * 重定向「生效名单」的跨进程存储（实时生效/失效的关键层）。
 *
 * 双层作用域设计（缺一不可）：
 *  1. **注入层** = 框架真实作用域（`YukiHook.Module.scope`）：决定模块代码进不进目标进程。
 *     首次纳入某个 App 后必须让该 App 重启一次完成注入——Xposed 框架铁律，任何模块都绕不过。
 *  2. **生效层** = 本类的名单：hook 每次触发时实时读，命中才重定向。
 *     写在 libxposed RemotePreferences 里（YukiHook `preferences()` 在 libxposed 底座下的实现），
 *     模块 UI 进程写、宿主进程读，**同一 group 跨进程同步**，因此开/关都无需重启宿主：
 *      - 勾选 → 宿主下一次调起相机即被重定向；
 *      - 取消 → 宿主下一次调起相机立即恢复原生行为（即使它仍在作用域内、hook 代码仍驻留）。
 *
 * UI 只有一个控件（点应用行勾选/取消），背后同时写注入层与生效层——对用户是单一开关，
 * 对本类是「生效层实时、注入层需重启一次」的两层语义。
 *
 * 存储名固定为 [STORE_NAME]，两侧必须一致；键 [KEY_ENABLED] 存包名集合。
 */
object RedirectConfig {

    /** RemotePreferences group 名（UI 侧与宿主侧共用，改名需两侧同步）。 */
    const val STORE_NAME = "redirect_config"

    /** 生效名单键：值为已启用重定向的包名集合。 */
    private const val KEY_ENABLED = "enabled_packages"

    // ============================ UI 侧（模块进程，可写） ============================

    /**
     * 读取生效名单（UI 侧）。
     * 服务未连接/存储不可用时返回空集——UI 据此显示「未启用任何应用」而非崩溃。
     */
    fun readEnabled(context: Context): Set<String> = runCatching {
        context.preferences(STORE_NAME).getStringSet(KEY_ENABLED, emptySet())
    }.getOrDefault(emptySet())

    /**
     * 覆盖写入生效名单（UI 侧）。
     * @return 是否写入成功（存储不可用时为 false，调用方据此提示用户）
     */
    fun writeEnabled(context: Context, packages: Set<String>): Boolean = runCatching {
        val prefs = context.preferences(STORE_NAME)
        if (!prefs.isWritable) return@runCatching false
        // putStringSet 需要可变副本：SharedPreferences 会缓存 Set 引用，直接传外部集合可能不生效
        prefs.edit { putStringSet(KEY_ENABLED, HashSet(packages)) }
        true
    }.getOrDefault(false)

    /** 加入生效名单；返回写入后的完整集合（失败时返回原集合）。 */
    fun enable(context: Context, packageName: String): Set<String> {
        val current = readEnabled(context).toMutableSet()
        current.add(packageName)
        return if (writeEnabled(context, current)) current else readEnabled(context)
    }

    /** 移出生效名单；返回写入后的完整集合（失败时返回原集合）。 */
    fun disable(context: Context, packageName: String): Set<String> {
        val current = readEnabled(context).toMutableSet()
        current.remove(packageName)
        return if (writeEnabled(context, current)) current else readEnabled(context)
    }

    // ============================ 宿主侧（被注入进程，只读） ============================

    /**
     * 判断当前宿主是否已启用重定向（宿主侧，hook 热路径调用）。
     *
     * 读取失败（存储不可用/框架无 PROP_CAP_REMOTE）时返回 **false**——
     * 宁可「不重定向、保持宿主原生行为」，也不要在无法确认用户意图时擅自改写宿主的相机调用。
     */
    fun isEnabledFor(param: PackageParam): Boolean = runCatching {
        param.preferences(STORE_NAME).getStringSet(KEY_ENABLED, emptySet())
            .contains(param.packageName)
    }.getOrDefault(false)
}
