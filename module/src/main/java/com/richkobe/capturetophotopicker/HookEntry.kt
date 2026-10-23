package com.richkobe.capturetophotopicker

import com.highcapable.yukihookapi.YukiHook
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule
import com.richkobe.capturetophotopicker.hook.CameraRedirectHooker
import com.richkobe.capturetophotopicker.hook.SystemPickerRedirectHooker

/**
 * LSPosed 模块入口（libxposed 底座，Modern Xposed API 102）。
 *
 * KSP(yukihook-compiler) 据本注解自动生成 META-INF/xposed/ 下的
 * java_init.list、module.prop、scope.list（源码目录不得手写，否则拒绝编译）。
 *
 * 作用域策略：scope 留空 + staticScope=false（默认）——不写死推荐作用域，
 * 由用户在模块「作用域」页经 YukiHook.Module.scope（底层 XposedService.requestScope）
 * 动态管理，框架弹「允许作用域」授权框。
 * hook 侧用无名 loadApp(excludeSelf=true)：LSPosed 把模块注入到作用域内每个 App，
 * 注入了就装载 CameraRedirectHooker；是否真正生效再由 RedirectConfig 实时开关决定。
 *
 * 隐蔽加固（通用）：
 *  - release 构建关闭 debug 与 YLog 输出，不往宿主 logcat 打模块 tag，
 *    避免宿主 App 用 logcat 探测 hook 痕迹；debug 构建保留日志便于开发验证；
 *  - dataChannel 关闭：不额外 hook 宿主 Application 生命周期，减少行为面；
 *  - libxposed 底座本身不向宿主注入 XposedBridge 类（与框架走 AIDL），
 *    天然规避 Class.forName("de.robv.android.xposed.XposedBridge") 式探测。
 */
@YukiHookLibXposedEntry(targetApiVersion = 102)
object HookEntry : YukiHookXposedModule {

    override fun onInit() {
        YukiHook.configure {
            logging {
                // tag 仅在 debug 构建下会出现在 logcat；release 全部静默
                tag = "CapToPicker"
                enabled = BuildConfig.DEBUG
                recording = false
            }
            // 调试期开启详细日志；发布版关闭（隐蔽加固：不向宿主 logcat 输出任何痕迹）
            debug = BuildConfig.DEBUG
            // 未使用模块↔宿主通讯桥，关闭以减少对宿主 Application 生命周期的额外 hook
            dataChannel = false
        }
    }

    override fun onHook() = YukiHook.encase {
        // 无名 loadApp：对作用域内被注入的每一个 App 生效（排除模块自身）
        // —— app scope 层：相机拍照重定向（逐 App 注入 + 结果翻译）
        loadApp(excludeSelf = true) {
            loadHooker(CameraRedirectHooker)
        }
        // framework scope 层：系统级图片选择重定向（只注入 system_server，不碰任何目标 App）。
        // 仅当用户在 LSPosed 作用域里勾选了「系统框架」时，本 hooker 才会被装载。
        loadSystem(SystemPickerRedirectHooker)
    }
}
