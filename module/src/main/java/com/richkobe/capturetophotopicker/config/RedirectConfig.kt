package com.richkobe.capturetophotopicker.config

import android.content.Context
import com.highcapable.yukihookapi.hook.factory.preferences

/**
 * 生效名单的跨进程配置通道（模式无关，避免任何模式下出现「假按钮」）。
 *
 * 为什么不能只用 libxposed RemotePreferences：
 *  - **LSPosed / NPatch 内置模式**：宿主进程经框架 `XposedInterface.getRemotePreferences()` 可读写，UI 侧服务也已连接 → 通道可用；
 *  - **NPatch 本地模式**：宿主侧 `LocalInjectedModuleService` 实现了 `requestRemotePreferences` 且报 `CAP_REMOTE`，
 *    但 UI 进程**默认拿不到 XposedService**（没人把 binder 喂给 `XposedServiceHelper`）；
 *    需主动向管理器 `RemoteApiProvider`（authority `<manager>.remote`，method `getRemoteService`）索取 binder，
 *    再经自己的 `XposedProvider`（method `IXposedService.SEND_BINDER`）喂入 `XposedServiceHelper` 才连得上；
 *  - **LSPatch 本地模式**：JingMatrix/LSPatch 的 manager manifest 里**只有 FileProvider 与 ShizukuProvider**，
 *    没有 RemoteApiProvider，UI 无法索取 binder → RemotePreferences 这条路走不通。
 *
 * 而 YukiHook 的 `preferences()` 在服务不可用时会**静默回退**到调用方自己的私有 SharedPreferences
 * （`LibXposedPreferences.Module.open` 的 `?: context.getSharedPreferences(...)`），既不抛异常也不告知。
 * 后果很隐蔽：UI 以为写成功了（写进了模块 App 私有 prefs），宿主读的是**它自己进程的**私有 prefs
 * （包名不同 → 文件不同 → 永远是空）→ 开关怎么拨都不生效，且无任何报错。
 *
 * 因此这里显式走双通道，且**以本模块导出的 ContentProvider 为主通道**：
 *  - UI 侧写：① 模块私有 prefs（Provider 的数据源，任何模式都可写）② RemotePreferences（服务可用时额外写）；
 *  - 宿主侧读：① 查模块 Provider（模块 App 已安装即可用——覆盖 LSPosed 与两种本地模式）
 *             ② 退回 RemotePreferences ③ 都取不到 → false（fail-closed，不擅自改写宿主行为）。
 *  内置模式下模块 App 未安装、Provider 查不到，但 [CameraRedirectHooker] 检测到内置模式会直接跳过名单，不受影响。
 *
 * @see ScopeProvider 主通道的 Provider 实现
 */
object RedirectConfig {

    /** RemotePreferences group 名（宿主侧与 UI 侧共用）。 */
    private const val REMOTE_GROUP = "redirect_config"

    /** 模块私有 prefs 文件名（Provider 的数据源；与 remote group 用不同名字以免混淆两种存储）。 */
    private const val LOCAL_FILE = "scope_local"

    /** 生效名单键。 */
    private const val KEY_ENABLED = "enabled_packages"

    /** Provider authority（宿主侧据此跨进程查询；见 ScopeProvider）。 */
    const val PROVIDER_AUTHORITY = "com.richkobe.capturetophotopicker.config"

    // ============================ UI 侧（模块进程，可写） ============================

    /**
     * 写入生效名单（双通道）。
     *
     * @param remoteWritable 框架服务是否可用（由 UI 的 observeFrameworkService 判定）；
     *                       为 true 时额外写 RemotePreferences，为 false 时只写私有 prefs（Provider 供宿主读）。
     * @return 私有 prefs 是否写入成功——这是主通道，它失败才算真失败。
     */
    fun writeEnabled(context: Context, packages: Set<String>, remoteWritable: Boolean): Boolean {
        // ① 主通道：模块私有 prefs（Provider 数据源）
        val localOk = runCatching {
            val prefs = context.getSharedPreferences(LOCAL_FILE, Context.MODE_PRIVATE)
            // putStringSet 必须传可变副本：SharedPreferences 会缓存 Set 引用，传外部集合可能不生效
            prefs.edit().putStringSet(KEY_ENABLED, HashSet(packages)).commit()
        }.getOrDefault(false)

        // ② 次通道：RemotePreferences（仅服务可用时；失败不影响主通道）
        if (remoteWritable) {
            runCatching {
                val remote = context.preferences(REMOTE_GROUP)
                if (remote.isWritable) {
                    remote.edit().putStringSet(KEY_ENABLED, HashSet(packages)).commit()
                }
            }
        }
        return localOk
    }

    /** 读取生效名单（UI 侧读自己写的私有 prefs，即权威副本）。 */
    fun readEnabled(context: Context): Set<String> = runCatching {
        context.getSharedPreferences(LOCAL_FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()
    }.getOrDefault(emptySet())

    /** 加入名单；返回写入后的集合。 */
    fun enable(context: Context, packageName: String, remoteWritable: Boolean): Set<String> {
        val next = readEnabled(context).toMutableSet().apply { add(packageName) }
        writeEnabled(context, next, remoteWritable)
        return readEnabled(context)
    }

    /** 移出名单；返回写入后的集合。 */
    fun disable(context: Context, packageName: String, remoteWritable: Boolean): Set<String> {
        val next = readEnabled(context).toMutableSet().apply { remove(packageName) }
        writeEnabled(context, next, remoteWritable)
        return readEnabled(context)
    }

    // ============================ 宿主侧（被注入进程，只读） ============================

    /**
     * 判断当前宿主是否已启用重定向（hook 热路径）。
     *
     * 读取顺序见类注释：Provider → RemotePreferences → false。
     * 任一环节异常都不抛出（hook 跑在宿主进程，抛异常会影响宿主）。
     */
    fun isEnabledFor(param: com.highcapable.yukihookapi.hook.param.PackageParam): Boolean {
        // ① 主通道：查模块 Provider（跨进程，模块 App 已安装即可用）
        val viaProvider = runCatching { queryProvider(param) }.getOrNull()
        if (viaProvider != null) return viaProvider

        // ② 次通道：框架 RemotePreferences（内置模式/LSPosed 下可用）
        return runCatching {
            param.preferences(REMOTE_GROUP).getStringSet(KEY_ENABLED, emptySet())
                ?.contains(param.packageName) == true
        }.getOrDefault(false)
    }

    /**
     * 经模块导出的 ContentProvider 查询本宿主是否在生效名单内。
     * @return true/false＝查到了；null＝Provider 不可达（模块未安装/被禁/查询异常），交由上层走次通道。
     */
    private fun queryProvider(
        param: com.highcapable.yukihookapi.hook.param.PackageParam,
    ): Boolean? {
        val context = param.appContext ?: return null
        val uri = android.net.Uri.parse("content://$PROVIDER_AUTHORITY/enabled")
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex("pkg")
                if (col < 0) return@use null
                val found = mutableListOf<String>()
                while (c.moveToNext()) found += c.getString(col)
                // 查到结果集（哪怕空）也算通道可用：空集＝用户没启用任何应用
                found.contains(param.packageName)
            }
        }.getOrNull()
    }
}
