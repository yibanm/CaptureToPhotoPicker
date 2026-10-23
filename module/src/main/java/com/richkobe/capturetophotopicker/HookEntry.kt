package com.richkobe.capturetophotopicker

import com.highcapable.yukihookapi.YukiHook
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule
import com.richkobe.capturetophotopicker.hook.CameraRedirectHooker

/**
 * LSPosed 模块入口（libxposed 底座，Modern Xposed API 102）。
 *
 * KSP(yukihook-compiler) 据本注解自动生成 META-INF/xposed/ 下的
 * java_init.list、module.prop、scope.list（源码目录不得手写，否则拒绝编译）。
 *
 * 作用域策略：scope 留空 + staticScope=false（默认）——不写死推荐作用域，
 * 由用户在模块「作用域」页经 YukiHook.Module.scope（底层 XposedService.requestScope）
 * 动态管理，框架弹「允许作用域」授权框。
 * hook 侧用无名 loadApp(excludeSelf=true)：框架把模块注入到作用域内每个 App，
 * 注入了就装载 CameraRedirectHooker；是否重定向由 RedirectConfig 生效名单实时决定
 * （勾选即写入名单，取消即移除——「选中作用域后马上生效、取消后马上失效」的落点）。
 *
 * 免 root（LSPatch/NPatch）：内置模式随宿主自动加载自动生效（CameraRedirectHooker
 * 检测宿主补丁包内嵌本模块即跳过名单检查）；本地模式名单照常由模块 UI 管理。
 *
 * 隐蔽加固（通用）：release 构建关闭 debug 与 YLog 输出，不往宿主 logcat 打模块 tag；
 * dataChannel 关闭，不额外 hook 宿主 Application 生命周期；libxposed 底座本身
 * 不向宿主注入 XposedBridge 类，天然规避 Class.forName 式探测。
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
            debug = BuildConfig.DEBUG
            dataChannel = false
        }
    }

    override fun onHook() = YukiHook.encase {
        // 无名 loadApp：对作用域内被注入的每一个 App 生效（排除模块自身）
        loadApp(excludeSelf = true) {
            loadHooker(CameraRedirectHooker)
        }
    }
}
