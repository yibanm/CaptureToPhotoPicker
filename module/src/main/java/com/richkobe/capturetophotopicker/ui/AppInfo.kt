package com.richkobe.capturetophotopicker.ui

/**
 * 应用列表条目模型。
 *
 * 结构参考 daxiaamu/Guise_Reborn 的 module/apps（AppInfo / 排序维度）与
 * wxxsfxyzm/InstallerX-Revived 的 apply 页，但本项目为干净自写实现，
 * 不复制其 GPL-3.0 代码，仅采用等价的字段与排序/筛选维度。
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val installTime: Long,
    val updateTime: Long,
    val isSystemApp: Boolean,
    /**
     * 是否已被 LSPatch/NPatch 打过补丁（manifest 带补丁配置 meta-data 或 loader stub 特征）。
     *
     * 用途：**本地(local)模式下**，注入哪些应用由补丁管理器在打补丁时决定，框架作用域（`Module.scope`）
     * 在 UI 进程读不到（恒为空），故不能用它判断「这个应用会不会被注入」——那样会对所有应用都说
     * 「还没打补丁」，是假信息。这里直接查目标应用自身的 manifest 得出真实状态，
     * UI 据此区分「勾了就生效」与「勾了但还没打补丁、需先去管理器」。
     */
    val isPatched: Boolean = false,
)

/** 排序维度：名称 / 包名 / 安装时间 / 更新时间。 */
enum class AppSortType(val labelZh: String) {
    NAME("名称"),
    PACKAGE_NAME("包名"),
    INSTALL_TIME("安装时间"),
    UPDATE_TIME("更新时间"),
}

/** 筛选维度：全部 / 仅用户应用 / 仅系统应用。 */
enum class AppFilter(val labelZh: String) {
    ALL("全部"),
    USER_ONLY("用户应用"),
    SYSTEM_ONLY("系统应用"),
}
