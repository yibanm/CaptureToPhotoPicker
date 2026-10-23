package com.richkobe.capturetophotopicker.config

import android.content.Context
import com.highcapable.yukihookapi.hook.factory.preferences
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * 重定向「生效开关」的跨进程存储（实时生效的关键层）。
 *
 * 双层作用域设计（缺一不可）：
 *  1. **注入层** = LSPosed 真实作用域（`YukiHook.Module.scope`）：决定模块代码进不进目标进程。
 *     首次纳入某个 App 后必须让该 App 重启一次完成注入——Xposed 框架铁律，任何模块都绕不过。
 *  2. **生效层** = 本类的开关名单：hook 每次触发时实时读，命中才重定向。
 *     写在 libxposed RemotePreferences 里（YukiHook `preferences()` 在 libxposed 底座下的实现），
 *     模块 UI 进程写、宿主进程读，**同一 group 跨进程同步**，因此开/关都无需重启宿主：
 *      - 打开 → 宿主下一次调起相机即被重定向；
 *      - 关闭 → 宿主下一次调起相机立即恢复原生行为（即使它仍在作用域内、hook 代码仍驻留）。
 *
 * 相比项目 3 的自建 exported ContentProvider 方案：无需对外暴露 Provider（减少泄露面与被检测面），
 * 通道由框架背书，且宿主侧读取是框架内存态、无 IPC 往返开销。
 *
 * 存储名固定为 [STORE_NAME]，两侧必须一致；键 [KEY_ENABLED] 存包名集合。
 */
object RedirectConfig {

    /** RemotePreferences group 名（UI 侧与宿主侧共用，改名需两侧同步）。 */
    const val STORE_NAME = "redirect_config"

    /** 生效名单键：值为已启用重定向的包名集合。 */
    private const val KEY_ENABLED = "enabled_packages"

    /**
     * 系统级重定向总开关键（Boolean）。
     *
     * 与 [KEY_ENABLED]（逐 App 相机重定向名单）相互独立：
     *  - [KEY_ENABLED] 控制 app-scope 的 [com.richkobe.capturetophotopicker.hook.CameraRedirectHooker]，按包名逐个生效；
     *  - 本键控制 framework-scope 的 [com.richkobe.capturetophotopicker.hook.SystemPickerRedirectHooker]，
     *    全系统一个总闸（它跑在 system_server，无「当前宿主包名」概念，故不按包名、只按总开关）。
     * 默认 **true**：用户把「系统框架」纳入作用域本身即是一次显式授权，故缺省开启；
     * UI 提供总开关可随时关断（关断后全系统立即恢复原生选择器，无需重启）。
     */
    private const val KEY_SYSTEM_REDIRECT = "system_redirect_enabled"

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

    /**
     * 读取系统级重定向总开关（UI 侧）。
     * 存储不可用时返回 **true**（与默认值一致，UI 显示为「开」）。
     */
    fun readSystemRedirect(context: Context): Boolean = runCatching {
        context.preferences(STORE_NAME).getBoolean(KEY_SYSTEM_REDIRECT, true)
    }.getOrDefault(true)

    /**
     * 写入系统级重定向总开关（UI 侧）。
     * @return 是否写入成功（存储不可用时为 false，调用方据此提示用户）
     */
    fun writeSystemRedirect(context: Context, enabled: Boolean): Boolean = runCatching {
        val prefs = context.preferences(STORE_NAME)
        if (!prefs.isWritable) return@runCatching false
        prefs.edit { putBoolean(KEY_SYSTEM_REDIRECT, enabled) }
        true
    }.getOrDefault(false)

    // ============================ 宿主侧（被注入进程，只读） ============================

    /**
     * 判断系统级重定向是否开启（system_server 侧，hook 热路径调用）。
     *
     * 读取失败（存储不可用/框架无 PROP_CAP_REMOTE）时返回 **true**（fail-open）——
     * 与 [isEnabledFor] 的 fail-closed 相反，理由：本 hooker 只在 system_server 运行，
     * 而「系统框架」能被注入本身就要求用户在 LSPosed 里显式勾选作用域（=显式授权），
     * 故缺省按开启处理；用户若要关，走 UI 总开关写入 false（正常路径可读，不受 fail-open 影响）。
     */
    fun isSystemRedirectEnabled(param: PackageParam): Boolean = runCatching {
        param.preferences(STORE_NAME).getBoolean(KEY_SYSTEM_REDIRECT, true)
    }.getOrDefault(true)

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
