package com.richkobe.capturetophotopicker

import com.highcapable.yukihookapi.hook.xposed.application.ModuleApplication

/**
 * 模块 Application（仅运行在模块自己的 UI 进程）。
 *
 * **必须继承 [ModuleApplication]**：它在 onCreate 里调用
 * `YukiHookBridge.initializeModuleServices()` → libxposed 底座的
 * `LibXposedService.initialize()` → `XposedServiceHelper.registerListener(...)`。
 *
 * 底座这个 listener 是 `YukiHook.Status`（激活状态）、`YukiHook.Module.scope`（作用域）、
 * `preferences()`（RemotePreferences）三者共同的服务来源。
 *
 * 红线（v1 踩过的坑）：`XposedServiceHelper.registerListener` 是**单 listener 静态槽**，
 * 后注册者覆盖先注册者——模块代码**绝不能**自己再调 registerListener 抢注，
 * 否则底座 services 表永远为空，`isModuleActive` 恒为 false、scope/preferences 全失效
 * （v1「状态页恒显示未激活」的根因即在此）。
 */
class CaptureToPhotoPickerApplication : ModuleApplication()
