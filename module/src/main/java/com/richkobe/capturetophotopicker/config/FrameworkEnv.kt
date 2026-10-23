package com.richkobe.capturetophotopicker.config

import java.util.zip.ZipFile

/**
 * 运行框架环境检测。
 *
 * 本模块支持三种运行环境（调研取证 2026-10-23，LSPatch=JingMatrix 活跃分支、NPatch=同源下游，
 * 两者补丁协议/模块加载完全一致）：
 *  1. **LSPosed**（root，libxposed API 102）：作用域经 YukiHook.Module.scope 官方通道管理，
 *     requestScope 由框架弹「允许作用域」授权框；
 *  2. **LSPatch/NPatch 本地(经理)模式**（免 root）：模块作为 companion app 单独安装，
 *     作用域由用户在补丁管理器里给目标 App 勾选本模块（运行时 requestScope **必然失败**——
 *     框架明确回调 onScopeRequestFailed，这是设计行为不是 bug），补丁后重新安装目标 App 生效；
 *  3. **LSPatch/NPatch 内置(embed)模式**（免 root）：模块 APK 被嵌进目标 App 的补丁包里，
 *     框架把作用域**固定为宿主包名**（singletonList(hostPackageName)），模块代码随宿主启动自动加载——
 *     「自动识别本软件、自动捕捉作用域」即此模式，无需用户任何配置，hook 直接生效。
 *
 * 内置模式检测特征（源码取证：ApkPatcher.embedModules 把模块写到宿主补丁包的
 * `assets/lspatch/modules/<模块包名>.apk`）：宿主进程内用 ZipFile 读自身 sourceDir 查该路径，
 * 一次检测缓存结果（每次 hook 触发都读 zip 太贵）。
 */
object FrameworkEnv {

    /** 本模块包名（内置模式下宿主 assets 里模块 APK 的文件名依据）。 */
    private const val MODULE_PKG = "com.richkobe.capturetophotopicker"

    /** 宿主补丁包内嵌模块的固定路径（LSPatch/NPatch embed 模式特征）。 */
    private const val EMBEDDED_MODULE_PATH = "assets/lspatch/modules/$MODULE_PKG.apk"

    /**
     * 判断当前宿主是否为「本模块被内置嵌入的补丁 App」（宿主侧调用，结果可缓存）。
     * 读取失败（非 zip/无权限）一律返回 false——退回常规开关逻辑，宁可少生效不误判。
     */
    fun isEmbeddedIn(hostSourceDir: String): Boolean = runCatching {
        ZipFile(hostSourceDir).use { it.getEntry(EMBEDDED_MODULE_PATH) != null }
    }.getOrDefault(false)

    /**
     * UI 侧判断当前框架是否 LSPatch/NPatch 系（免 root 补丁框架）。
     * 依据 = YukiHook.Status.Executor.name（libxposed 底座下即框架 getFrameworkName()：
     * LSPatch 返回 "LSPatch"，NPatch 返回其自报名，LSPosed 返回 "LSPosed"）。
     */
    fun isPatchFramework(frameworkName: String): Boolean =
        frameworkName.contains("LSPatch", true) ||
            frameworkName.contains("NPatch", true) ||
            frameworkName.contains("Patch", true)
}
