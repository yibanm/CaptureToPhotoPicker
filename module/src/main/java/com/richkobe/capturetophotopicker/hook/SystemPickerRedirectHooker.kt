package com.richkobe.capturetophotopicker.hook

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hookAll
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookParam
import com.richkobe.capturetophotopicker.config.RedirectConfig

/**
 * 系统级图片选择重定向（framework scope，**只跑在 system_server，不注入任何目标 App**）。
 *
 * 与 [CameraRedirectHooker]（app scope，逐 App 注入翻译相机结果）互补：本 Hooker 在
 * `ActivityTaskManagerService` 的 startActivity* 入口层，把全系统 App 发出的**标准图片选择
 * Intent**（`ACTION_GET_CONTENT` / `ACTION_PICK` + image 语义）统一改写为 Photo Picker
 * （`MediaStore.ACTION_PICK_IMAGES`）。因为改写发生在 system_server 内、`resultTo`（调用方
 * token）原样保留，Photo Picker 的结果由框架自动路由回原 App 的 onActivityResult，格式就是
 * 标准 content:// Uri——A 类 App 天然认识，无需任何 per-App 逆向或结果翻译。
 *
 * 取证依据（2026-10-23 反编译本机 ColorOS services.jar，附件见项目经验）：
 *  - 入口方法：startActivity / startActivityAsUser / startActivityAndWait /
 *    startActivityWithConfig / startActivityAsCaller（**无 startActivityWithFeature，ColorOS 已删**）；
 *  - Intent 恒为「第一个 Intent 类型参数」，其后一位即 resolvedType；
 *  - 收口 `obtainStarter(intent).setResolvedType(resolvedType).execute()` 用**参数 resolvedType**
 *    而非 intent.type，故改写时必须同步 resolvedType 参数，否则 PICK（vnd.android.cursor.dir/image）
 *    改 action 后 resolve 失败。
 *
 * 保守边界（缩小 system_server 爆炸半径）：
 *  - 只认 GET_CONTENT / PICK 的 image 语义；**不碰 SAF（OPEN_DOCUMENT/OPEN_DOCUMENT_TREE）**、
 *    不碰非图片、不碰 startActivityIntentSender（PendingIntent 补填语义不同）、startActivityFromRecents（无 Intent）；
 *  - 已是 ACTION_PICK_IMAGES 直接跳过（天然防循环）；
 *  - 全部逻辑 runCatching 包裹，任何异常只记日志、**绝不抛回 system_server**（system_server 崩溃=整机重启）。
 */
object SystemPickerRedirectHooker : YukiBaseHooker() {

    private const val ATMS_CLASS = "com.android.server.wm.ActivityTaskManagerService"

    /** ColorOS ATMS 实测的 Activity 启动入口方法名（见类注释取证依据）。 */
    private val START_METHOD_NAMES = listOf(
        "startActivity",
        "startActivityAsUser",
        "startActivityAndWait",
        "startActivityWithConfig",
        "startActivityAsCaller",
    )

    override fun onHook() {
        // system_server 进程内按字符串类名加载 ATMS（它不在模块编译 classpath 上）
        val atms = runCatching { Class.forName(ATMS_CLASS, false, hostClassLoader) }.getOrNull()
        if (atms == null) {
            YLog.error("未找到 $ATMS_CLASS（当前进程非 system_server 或厂商改名），系统级重定向跳过")
            return
        }

        var hooked = 0
        START_METHOD_NAMES.forEach { mName ->
            runCatching {
                // KavaRef 正确姿势：Class 实例用扩展 atms.resolve()（同 CameraRedirectHooker 的 Activity::class.resolve()）
                atms.resolve()
                    .optional(silent = true)
                    .method { name = mName }
                    .hookAll {
                        before {
                            runCatching { redirect() }
                                .onFailure { YLog.error("系统级重定向处理 $mName 失败（已忽略，不影响系统）", it) }
                        }
                    }
                hooked++
            }.onFailure { YLog.warn("hook $mName 失败：${it.message}") }
        }
        YLog.info("SystemPickerRedirectHooker 装载完成，命中 $hooked/${START_METHOD_NAMES.size} 个 startActivity 入口")
    }

    /** 出站改写：命中标准图片选择 Intent 就改成 Photo Picker，并同步 resolvedType 参数。 */
    private fun HookParam.redirect() {
        // 生效层总开关（实时、跨进程；读取失败默认开——用户已把系统框架纳入作用域即视为授权）
        if (!RedirectConfig.isSystemRedirectEnabled(this@SystemPickerRedirectHooker)) return

        val params = args
        // Intent 恒为第一个 Intent 类型参数；其后一位即 resolvedType
        val intentIndex = params.indexOfFirst { it is Intent }
        if (intentIndex < 0) return
        val intent = params[intentIndex] as Intent

        // 已是 Photo Picker 目标 action → 跳过（防循环）
        if (intent.action == MediaStore.ACTION_PICK_IMAGES) return
        // 只认标准图片选择语义，其余一律放行
        if (!isStandardImagePick(intent)) return

        val multi = runCatching { intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) }
            .getOrDefault(false)
        val from = "${intent.action}｜type=${intent.type ?: "-"}｜data=${intent.data ?: "-"}"

        // 就地改写为 Photo Picker（隐式解析到 com.android.photopicker/.MainActivity）
        intent.component = null
        intent.action = MediaStore.ACTION_PICK_IMAGES
        // PICK_IMAGES 靠 type 匹配 image/*；清掉原 data（PICK 的 content://media/.../images 不需要）
        intent.setDataAndType(null, "image/*")
        // 多选翻译：EXTRA_ALLOW_MULTIPLE → EXTRA_PICK_IMAGES_MAX（不设则 PICK_IMAGES 默认单选）
        if (multi) {
            runCatching {
                val max = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    MediaStore.getPickImagesMaxLimit()
                } else {
                    9 // API<33 无 getPickImagesMaxLimit，给一个合理上限
                }
                intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max)
            }.onFailure { YLog.warn("设置 EXTRA_PICK_IMAGES_MAX 失败：${it.message}") }
        }
        // 同步 resolvedType 参数（obtainStarter 用参数值而非 intent.type；不同步会 resolve 失败）
        val rtIndex = intentIndex + 1
        if (rtIndex < params.size && params[rtIndex] is String) {
            runCatching { arg(rtIndex).set("image/*") }
                .onFailure { YLog.warn("同步 resolvedType 失败：${it.message}") }
        }

        YLog.debug("系统级重定向命中：$from → ACTION_PICK_IMAGES(image/*)${if (multi) "｜多选" else ""}")
    }

    /** 是否「标准图片选择」语义：只认 GET_CONTENT / PICK 的 image，不碰 SAF、不碰非图片。 */
    private fun isStandardImagePick(intent: Intent): Boolean {
        val mimeIsImage = intent.type?.startsWith("image/") == true
        return when (intent.action) {
            Intent.ACTION_GET_CONTENT -> mimeIsImage || extraMimeHasImage(intent)
            Intent.ACTION_PICK -> mimeIsImage ||
                intent.data?.toString()?.contains("images") == true
            else -> false
        }
    }

    /** GET_CONTENT 可把 mime 放在 EXTRA_MIME_TYPES 数组里而非 type。 */
    private fun extraMimeHasImage(intent: Intent): Boolean = runCatching {
        @Suppress("DEPRECATION")
        intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.any { it.startsWith("image/") } == true
    }.getOrDefault(false)
}
