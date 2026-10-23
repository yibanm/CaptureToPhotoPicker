package com.richkobe.capturetophotopicker.demo

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable

/**
 * Intent 序列化工具：把一个 Intent 的所有关键字段转成可读多行文本。
 * 这是 Demo 靶子的核心——把相机 / PhotoPicker 两条链路的
 * 「出站 Intent」和「入站 result Intent」完整 dump 出来，
 * 供后续 HookNext 日志分析对比，定位「移花接木」为何失败。
 */
object IntentDump {

    /** 把 resultCode 数值翻译成常量名，方便阅读。 */
    fun resultCodeName(code: Int): String = when (code) {
        android.app.Activity.RESULT_OK -> "RESULT_OK(-1)"
        android.app.Activity.RESULT_CANCELED -> "RESULT_CANCELED(0)"
        android.app.Activity.RESULT_FIRST_USER -> "RESULT_FIRST_USER(1)"
        else -> "UNKNOWN($code)"
    }

    /** 完整 dump 一个 Intent（可能为 null，做容错）。 */
    fun dump(intent: Intent?): String {
        // result Intent 常常为 null（例如用户按返回键取消），必须容错
        if (intent == null) return "  <intent == null>"
        val sb = StringBuilder()
        sb.appendLine("  action     = ${intent.action}")
        sb.appendLine("  component  = ${intent.component?.flattenToShortString()}")
        sb.appendLine("  data       = ${intent.data}")
        sb.appendLine("  type       = ${intent.type}")
        sb.appendLine("  categories = ${intent.categories}")
        sb.appendLine("  flags      = 0x${Integer.toHexString(intent.flags)}")
        // clipData：多选场景 PhotoPicker 会把多个 Uri 放这里
        val clip = intent.clipData
        sb.appendLine(
            "  clipData   = " + if (clip == null) "null"
            else "itemCount=${clip.itemCount}, uri0=${safeItem(clip, 0)}"
        )
        val extras = intent.extras
        sb.appendLine("  extras     = ${if (extras == null) "null" else "size=" + extras.size()}")
        if (extras != null) {
            for (key in extras.keySet()) {
                // 逐个 extra 打印类型和内容，Bitmap 只打尺寸避免刷屏
                sb.appendLine("    - $key : ${describe(extras.get(key))}")
            }
        }
        return sb.toString()
    }

    /** 安全取 clipData 第 index 项的 Uri，越界或异常返回占位符。 */
    private fun safeItem(clip: android.content.ClipData, index: Int): Any? =
        try {
            clip.getItemAt(index)?.uri
        } catch (e: Exception) {
            "<err:${e.message}>"
        }

    /** 描述任意 extra 值的类型与内容。 */
    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Bitmap -> "Bitmap(${value.width}x${value.height})"
        is Uri -> "Uri($value)"
        is Bundle -> "Bundle(keys=${value.keySet()})"
        is Parcelable -> "${value.javaClass.simpleName} -> $value"
        is Array<*> -> "Array(size=${value.size}) ${value.contentDeepToString()}"
        is ByteArray -> "ByteArray(size=${value.size})"
        else -> "${value.javaClass.simpleName}($value)"
    }
}
