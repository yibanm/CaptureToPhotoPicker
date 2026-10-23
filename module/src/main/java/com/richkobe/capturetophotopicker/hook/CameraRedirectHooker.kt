package com.richkobe.capturetophotopicker.hook

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hookAll
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookParam
import com.richkobe.capturetophotopicker.config.FrameworkEnv
import com.richkobe.capturetophotopicker.config.RedirectConfig
import java.io.InputStream

/**
 * 相机拍照 → 系统 PhotoPicker 的重定向 + 结果翻译核心（YukiHook 1.5.0 / libxposed API 102）。
 *
 * 迁移自项目 3.PhotoPickerRedirect 已实机验证的逻辑，改动：
 *  - Hook DSL 从 1.3.2 迁到 1.5.0（KavaRef resolve()/method{}.hookAll{}，扩展在 extension.kavaref 包）；
 *  - 去掉项目 3 的「ScopeProvider 跨进程运行时开关」——改用 YukiHook `preferences()`
 *    （libxposed 底座下=RemotePreferences 跨进程通道），见 [RedirectConfig]；
 *  - 注入 "data" Bitmap 的降采样上限 2048→512：512x384 ARGB_8888≈786KB 已逼近 Binder ~1MB
 *    事务上限，2048 会 TransactionTooLargeException（调研取证）。
 *
 * 作用域两层设计（[RedirectConfig] 有详述）：
 *  - 注入层：LSPosed 真实作用域决定模块代码进不进宿主进程（首次纳入需宿主重启一次，框架铁律）；
 *  - 生效层：本 Hooker 每次触发都实时读 RedirectConfig 名单，**开/关都无需重启宿主**。
 *
 * 两条链路返回数据形态不同（「移花接木」失败的根因）：
 *  - 相机式调用方读结果有三种姿势：(a) EXTRA_OUTPUT 指定 Uri 里已有图字节；
 *    (b) 结果 Intent 的 "data" extra 里有内联 Bitmap；(c) 结果 Intent 的 data/clipData 里有 Uri。
 *  - PhotoPicker 只给 data.getData()/getClipData() 的 content:// Uri + 一次性临时读权限。
 * 因此翻译层对 RESULT_OK 的结果**同时补齐三种姿势**。
 *
 * 所有逻辑 runCatching 包裹，任何异常只记日志、绝不抛回宿主导致崩溃。
 */
object CameraRedirectHooker : YukiBaseHooker() {

    // 需要被重定向的「相机」来源特征
    private const val OPLUS_CAMERA_PKG = "com.oplus.camera"
    private const val OPLUS_CAPTURE_ACTIVITY = "com.oplus.camera.component.CapturePhotoActivity"
    private const val ACTION_IMAGE_CAPTURE = MediaStore.ACTION_IMAGE_CAPTURE

    // 重定向目标：系统 PhotoPicker 公开 action（隐式解析到 com.android.photopicker/.MainActivity）
    private const val ACTION_PICK_IMAGES = MediaStore.ACTION_PICK_IMAGES

    /** 注入 "data" Bitmap 的降采样长边上限（防 Binder ~1MB 事务溢出）。 */
    private const val INLINE_BITMAP_MAX_SIDE = 512

    /**
     * 出站重定向记录：key = "activityIdentity:requestCode"，value = 原 EXTRA_OUTPUT Uri。
     * 入站据此判断「这个 requestCode 是否我们重定向出去的」并取回写目标。
     */
    private val pendingOutputs = HashMap<String, Uri?>()

    /**
     * LSPatch/NPatch 内置(embed)模式标记：模块被嵌进宿主补丁包时，框架把作用域固定为宿主
     * 且随宿主启动自动加载——此模式下「自动识别、自动生效」，跳过生效层开关（用户没有机会
     * 打开模块 UI 拨开关）。onHook 时检测一次缓存（object 单例按进程隔离，不会串宿主）。
     */
    @Volatile
    private var embeddedMode = false

    private fun outputKey(activity: Any?, requestCode: Int) =
        "${System.identityHashCode(activity)}:$requestCode"

    override fun onHook() {
        // 内置模式检测：宿主补丁包 assets 里有本模块 APK 即 embed 模式（见 FrameworkEnv）
        embeddedMode = runCatching { FrameworkEnv.isEmbeddedIn(appInfo.sourceDir) }.getOrDefault(false)
        if (embeddedMode) {
            YLog.info("检测到 LSPatch/NPatch 内置模式：对宿主自动启用相机重定向（无需配置）")
        }

        // 出站：拦截 startActivityForResult（两个重载都 hook），把相机 Intent 改写成 PhotoPicker
        Activity::class.resolve()
            .optional(silent = true)
            .method { name = "startActivityForResult" }
            .hookAll {
                before {
                    runCatching { redirectOutgoing() }
                        .onFailure { YLog.error("重定向出站失败（已忽略，不影响宿主）", it) }
                }
            }

        // 入站：把 PhotoPicker 结果翻译成相机式结果（三种读取姿势全补齐）
        Activity::class.resolve()
            .optional(silent = true)
            .method { name = "onActivityResult" }
            .hookAll {
                before {
                    runCatching { translateIncoming() }
                        .onFailure { YLog.error("翻译入站结果失败（已忽略，不影响宿主）", it) }
                }
            }

        YLog.info("CameraRedirectHooker 已装载，相机→PhotoPicker 重定向就绪")
    }

    /** 出站改写：宿主在「生效名单」内且命中相机 Intent 才改成 PhotoPicker，并记录原 EXTRA_OUTPUT。 */
    private fun HookParam.redirectOutgoing() {
        val activity = instanceOrNull<Activity>() ?: return

        // 生效层开关（实时、无需重启宿主）：模块 UI 未勾选本宿主时一律放行，
        // 保持宿主原生相机行为——这是「取消作用域后马上失效」的落点。
        // 例外：LSPatch/NPatch 内置(embed)模式跳过开关——模块被嵌进宿主补丁包即应对宿主
        // 自动生效（用户没有打开模块 UI 拨开关的机会），这就是「自动识别本软件自动捕捉作用域」。
        // 接收者是外层 Hooker（YukiBaseHooker : PackageParam），持有宿主 packageName 与 preferences 通道。
        if (!embeddedMode && !RedirectConfig.isEnabledFor(this@CameraRedirectHooker)) return

        val intent = args.firstOrNull { it is Intent } as? Intent ?: return
        val requestCode = args.firstOrNull { it is Int } as? Int ?: return

        if (!isCameraIntent(intent)) return

        val originalOutput = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        }.getOrNull()
        pendingOutputs[outputKey(activity, requestCode)] = originalOutput

        YLog.debug("命中相机调用 → 重定向到 PhotoPicker｜原EXTRA_OUTPUT=$originalOutput｜requestCode=$requestCode")

        // 就地改写 Intent 对象（宿主随后拿到的是同一实例，原方法带着改后的 intent 继续）
        intent.component = null
        intent.action = ACTION_PICK_IMAGES
        intent.setDataAndType(null, "image/*")
        runCatching { intent.removeExtra(MediaStore.EXTRA_OUTPUT) }
    }

    /**
     * 入站翻译：把 PhotoPicker 结果补齐为相机式结果的三种读取姿势。
     * 就地修改 data 这个 Intent 对象（宿主随后拿到的就是同一实例）。
     */
    private fun HookParam.translateIncoming() {
        val activity = instanceOrNull<Activity>() ?: return
        val requestCode = (args.getOrNull(0) as? Int) ?: return
        val resultCode = (args.getOrNull(1) as? Int) ?: return
        val data = args.getOrNull(2) as? Intent

        val key = outputKey(activity, requestCode)
        if (!pendingOutputs.containsKey(key)) return
        val originalOutput = pendingOutputs.remove(key)

        if (resultCode != Activity.RESULT_OK) {
            YLog.debug("PhotoPicker 被取消（resultCode=$resultCode），不翻译")
            return
        }

        val picked = runCatching {
            data?.data ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        }.getOrNull()
        if (picked == null) {
            YLog.warn("RESULT_OK 但未取到选中 Uri，跳过翻译")
            return
        }

        YLog.debug("翻译 PhotoPicker 结果｜picked=$picked｜回写目标=$originalOutput")

        // 姿势 (a)：字节回写相机原 EXTRA_OUTPUT（相机式调用方读文件路径）
        if (originalOutput != null) {
            runCatching { copyUri(activity, picked, originalOutput) }
                .onFailure { YLog.error("回写 EXTRA_OUTPUT 抛异常", it) }
        }

        if (data == null) return

        // 姿势 (c)：把选中 Uri 设进结果 Intent 的 data / clipData
        runCatching {
            data.data = picked
            data.clipData = ClipData.newUri(activity.contentResolver, "picked", picked)
            data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { YLog.warn("设置 data/clipData 失败：${it.message}") }

        // 姿势 (b)：解码 Bitmap 注入 "data" extra（读内联缩略图的调用方）
        runCatching {
            val bmp = decodeSampled(activity, picked, INLINE_BITMAP_MAX_SIDE)
            if (bmp != null) {
                data.putExtra("data", bmp)
                YLog.debug("已注入 data Bitmap｜${bmp.width}x${bmp.height}")
            } else {
                YLog.warn("Bitmap 解码为 null，未注入 data extra｜picked=$picked")
            }
        }.onFailure { YLog.error("注入 data Bitmap 抛异常", it) }

        // 诊断：翻译完成后 dump 结果 Intent 的最终形态
        runCatching {
            val extrasKeys = data.extras?.keySet()?.joinToString(",") ?: "(none)"
            YLog.debug(
                "翻译完成｜action=${data.action}｜data=${data.data}｜" +
                    "clip=${data.clipData?.itemCount ?: 0}｜extras=[$extrasKeys]",
            )
        }
    }

    /** 判断 Intent 是否指向要被重定向的相机（显式 oplus 组件 或 标准拍照 action）。 */
    private fun isCameraIntent(intent: Intent): Boolean {
        val comp = intent.component
        val hitExplicit = comp != null &&
            comp.packageName == OPLUS_CAMERA_PKG &&
            comp.className == OPLUS_CAPTURE_ACTIVITY
        return hitExplicit || intent.action == ACTION_IMAGE_CAPTURE
    }

    /**
     * 打开选中 Uri 的输入流，带两级回退：
     *  1) contentResolver.openInputStream（临时授权，常规路径）；
     *  2) open 抛 FileNotFound 时，query 该 Uri 的 MediaColumns.DATA 拿真实文件路径，FileInputStream 直开；
     *  3) picker 代理 Uri → MediaStore 直连 Uri 回退。
     * 全失败返回 null 并打 WARN，调用方据此跳过而不是静默丢结果。
     */
    private fun openSource(context: Context, uri: Uri): InputStream? {
        runCatching { return context.contentResolver.openInputStream(uri) }
            .onFailure { YLog.warn("openInputStream 失败（${it.javaClass.simpleName}: ${it.message}），尝试 _data 回退") }
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val path = c.getString(0)
                        if (!path.isNullOrEmpty()) {
                            val f = java.io.File(path)
                            if (f.exists()) return f.inputStream()
                            YLog.warn("_data 文件不存在：$path")
                        }
                    }
                }
        }.onFailure { YLog.warn("_data 查询失败：${it.message}") }
        runCatching {
            val s = uri.toString()
            val m = Regex("content://media/picker/\\d+/[^/]+/media/(\\d+)").find(s)
            if (m != null) {
                val direct = Uri.parse("content://media/external/images/media/${m.groupValues[1]}")
                YLog.debug("尝试 MediaStore 直连回退：$direct")
                return context.contentResolver.openInputStream(direct)
            }
        }.onFailure { YLog.warn("MediaStore 直连回退失败：${it.message}") }
        return null
    }

    /**
     * 把 source 的字节完整拷贝到 dest（回写相机输出目标）。
     * 流为 null 时打 WARN（此前静默跳过，导致失败无痕迹）。
     */
    private fun copyUri(context: Context, source: Uri, dest: Uri) {
        val input = openSource(context, source)
        if (input == null) {
            YLog.warn("回写失败：source 打不开（回退均失败）｜source=$source")
            return
        }
        input.use { ins: InputStream ->
            val output = context.contentResolver.openOutputStream(dest)
            if (output == null) {
                YLog.warn("回写失败：openOutputStream(dest) 为 null｜dest=$dest")
                return
            }
            output.use { outs ->
                val bytes = ins.copyTo(outs)
                outs.flush()
                YLog.debug("回写完成｜$bytes 字节 → $dest")
            }
        }
    }

    /** 降采样解码 Uri 为 Bitmap（防大图 OOM）；源打不开时返回 null。 */
    private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val boundsStream = openSource(context, uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while ((bounds.outWidth / sample) > maxSide || (bounds.outHeight / sample) > maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = openSource(context, uri) ?: return null
        return decodeStream.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
