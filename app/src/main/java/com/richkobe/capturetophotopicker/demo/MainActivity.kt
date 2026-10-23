package com.richkobe.capturetophotopicker.demo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Demo 靶子：分别以「隐式 action」和「显式 component」两种方式调起
 * 1) ColorOS 相机拍照页  2) 系统 PhotoPicker 选图页，
 * 全部走经典 startActivityForResult —— 这样既能被 hook 的
 * Instrumentation#execStartActivity 捕获出站 Intent，也能被 hook 的
 * Activity#onActivityResult 捕获入站 result Intent，两条链路的数据结构
 * 一目了然，为后续 YukiHookAPI 重定向 + 结果转换提供依据。
 *
 * 这个 APK 只是「测试床」，真正交付的 hook 模块在 :module 里，与本 Demo 解耦。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CaptureToPickerDemo"
        // requestCode 区分六种调起来源，回调时据此打印来源标签
        private const val REQ_CAMERA_IMPLICIT = 1001
        private const val REQ_CAMERA_EXPLICIT = 1002
        private const val REQ_PICKER_IMPLICIT = 1003
        private const val REQ_PICKER_EXPLICIT = 1004
        private const val REQ_GET_CONTENT = 1005
        private const val REQ_PICK_IMAGES = 1006
    }

    // Compose 界面状态：出站 Intent dump / 入站 result dump / 结果图 / 状态提示
    private val uiState = mutableStateOf(UiState())

    /** 相机 EXTRA_OUTPUT 用的临时文件 Uri（模拟真实相机调用方的回写目标）。 */
    private var cameraOutputUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 测试专用广播接收器：让 adb 能在不碰屏幕/不抢前台的情况下触发调起链路，
        // 供自动化端到端验证使用（Demo 是测试靶子，exported 可接受）。
        // action: com.richkobe.capturetophotopicker.demo.TRIGGER，extra "which" = A/B/C/D
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra("which")) {
                    "A" -> launchCameraImplicit()
                    "B" -> launchCameraExplicit()
                    "C" -> launchPickerImplicit()
                    "D" -> launchPickerExplicit()
                    "E" -> launchGetContent()
                    "F" -> launchPickImages()
                }
            }
        }
        // RECEIVER_EXPORTED 与三参 registerReceiver 为 API 33+，低版本回退两参重载
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                receiver,
                android.content.IntentFilter("com.richkobe.capturetophotopicker.demo.TRIGGER"),
                RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                receiver,
                android.content.IntentFilter("com.richkobe.capturetophotopicker.demo.TRIGGER"),
            )
        }

        setContent {
            MaterialTheme {
                DemoScreen(
                    state = uiState.value,
                    onCameraImplicit = ::launchCameraImplicit,
                    onCameraExplicit = ::launchCameraExplicit,
                    onPickerImplicit = ::launchPickerImplicit,
                    onPickerExplicit = ::launchPickerExplicit,
                    onGetContent = ::launchGetContent,
                    onPickImages = ::launchPickImages,
                )
            }
        }

        // 测试入口：am start --es trigger B 直接触发对应链路（前台启动，不受 BAL 限制，
        // 供自动化端到端验证；Demo 是测试靶子，此入口可接受）
        when (intent?.getStringExtra("trigger")) {
            "A" -> window.decorView.postDelayed(::launchCameraImplicit, 600)
            "B" -> window.decorView.postDelayed(::launchCameraExplicit, 600)
            "C" -> window.decorView.postDelayed(::launchPickerImplicit, 600)
            "D" -> window.decorView.postDelayed(::launchPickerExplicit, 600)
            "E" -> window.decorView.postDelayed(::launchGetContent, 600)
            "F" -> window.decorView.postDelayed(::launchPickImages, 600)
        }
    }

    // ============================ 相机（源链路） ============================

    /** A. 隐式：标准 ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT。 */
    private fun launchCameraImplicit() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, obtainCameraOutputUri())
        }
        fire(intent, REQ_CAMERA_IMPLICIT, "A. 相机·隐式 ACTION_IMAGE_CAPTURE")
    }

    /** B. 显式：直接指名 com.oplus.camera 的 CapturePhotoActivity。 */
    private fun launchCameraExplicit() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.oplus.camera",
                "com.oplus.camera.component.CapturePhotoActivity",
            )
            // 真实调用方通常也会带 EXTRA_OUTPUT，保持一致以便对比
            putExtra(MediaStore.EXTRA_OUTPUT, obtainCameraOutputUri())
        }
        fire(intent, REQ_CAMERA_EXPLICIT, "B. 相机·显式 CapturePhotoActivity")
    }

    // ============================ PhotoPicker（目标链路） ============================

    /** C. 隐式：MediaStore.ACTION_PICK_IMAGES（PhotoPicker 公开 API）。 */
    private fun launchPickerImplicit() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = "image/*"
        }
        fire(intent, REQ_PICKER_IMPLICIT, "C. PhotoPicker·隐式 ACTION_PICK_IMAGES")
    }

    /** D. 显式：直接指名 com.android.photopicker 的 MainActivity。 */
    private fun launchPickerExplicit() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.android.photopicker",
                "com.android.photopicker.MainActivity",
            )
        }
        fire(intent, REQ_PICKER_EXPLICIT, "D. PhotoPicker·显式 MainActivity")
    }

    // ============================ 标准图片选择（A 类测试） ============================

    // E. ACTION_GET_CONTENT + image 类型（最通用的标准图片选择，A 类 App 的典型调用）。
    // 注意：注释里绝不能出现 image/* 字样——Kotlin 块注释可嵌套，/* 会开启新注释层导致永不闭合
    private fun launchGetContent() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        fire(intent, REQ_GET_CONTENT, "E. GET_CONTENT image/*")
    }

    /** F. ACTION_PICK + images（老式相册选择，部分 App 仍用）。 */
    private fun launchPickImages() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            data = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            type = "image/*"
        }
        fire(intent, REQ_PICK_IMAGES, "F. PICK images")
    }

    // ============================ 统一调起 + 容错 ============================

    /**
     * 统一的 startActivityForResult 出口：先 dump 出站 Intent 到界面和 logcat，
     * 再用 try/catch 兜住 ActivityNotFound（目标 App 不存在 / 无对应 Activity），
     * 绝不让 Demo 崩溃。
     */
    private fun fire(intent: Intent, requestCode: Int, label: String) {
        val outDump = "$label\n" + IntentDump.dump(intent)
        Log.i(TAG, "OUT >>>\n$outDump")
        uiState.value = uiState.value.copy(
            status = "正在调起：$label",
            outDump = outDump,
            inDump = "",
            bitmap = null,
        )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            // 目标 Activity 未安装/被禁用：提示但不崩溃
            val msg = "调起失败（目标不存在）：${e.message}"
            Log.w(TAG, msg, e)
            uiState.value = uiState.value.copy(status = msg)
        } catch (e: SecurityException) {
            val msg = "调起被拒（权限/exported）：${e.message}"
            Log.w(TAG, msg, e)
            uiState.value = uiState.value.copy(status = msg)
        } catch (e: Exception) {
            val msg = "调起异常：${e.javaClass.simpleName} ${e.message}"
            Log.e(TAG, msg, e)
            uiState.value = uiState.value.copy(status = msg)
        }
    }

    /**
     * 入站回调：dump 完整 result Intent，并尝试把返回的图片解码成 Bitmap 显示。
     * 相机链路可能把图放在 "data" extra，也可能已写入 EXTRA_OUTPUT；
     * PhotoPicker 链路把 content:// Uri 放在 data / clipData。三种都尝试。
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val label = when (requestCode) {
            REQ_CAMERA_IMPLICIT -> "A. 相机·隐式"
            REQ_CAMERA_EXPLICIT -> "B. 相机·显式"
            REQ_PICKER_IMPLICIT -> "C. PhotoPicker·隐式"
            REQ_PICKER_EXPLICIT -> "D. PhotoPicker·显式"
            REQ_GET_CONTENT -> "E. GET_CONTENT image/*"
            REQ_PICK_IMAGES -> "F. PICK images"
            else -> "未知($requestCode)"
        }
        val inDump = "$label\n  resultCode = ${IntentDump.resultCodeName(resultCode)}\n" +
            IntentDump.dump(data)
        Log.i(TAG, "IN <<<\n$inDump")

        // 尝试多种途径拿到结果图（任一成功即显示），全部容错
        val bmp = decodeResult(data, requestCode)
        uiState.value = uiState.value.copy(
            status = "返回：$label / ${IntentDump.resultCodeName(resultCode)}",
            inDump = inDump,
            bitmap = bmp,
        )
    }

    /** 从 result Intent 里尽力解码出图片，覆盖相机与 PhotoPicker 两种数据形态。 */
    private fun decodeResult(data: Intent?, requestCode: Int): Bitmap? {
        // 途径 1：inline Bitmap 放在 "data" extra（相机 ACTION_IMAGE_CAPTURE 常见）
        try {
            val inline = data?.getParcelableExtra<Bitmap>("data")
            if (inline != null) return inline
        } catch (_: Exception) {
        }
        // 途径 2：data.getData() 的 content:// Uri（PhotoPicker 单选常见）
        try {
            val uri = data?.data
            if (uri != null) return decodeUri(uri)
        } catch (_: Exception) {
        }
        // 途径 3：clipData 第一项（PhotoPicker 多选常见）
        try {
            val clip = data?.clipData
            if (clip != null && clip.itemCount > 0) {
                val uri = clip.getItemAt(0)?.uri
                if (uri != null) return decodeUri(uri)
            }
        } catch (_: Exception) {
        }
        // 途径 4：相机链路可能已把图写进 EXTRA_OUTPUT，回头读那个 Uri
        if (requestCode == REQ_CAMERA_IMPLICIT || requestCode == REQ_CAMERA_EXPLICIT) {
            try {
                cameraOutputUri?.let { return decodeUri(it) }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 把一个 Uri 解码成缩放到合理尺寸的 Bitmap（分版本处理，做降采样防 OOM）。 */
    private fun decodeUri(uri: Uri): Bitmap? = runCatching {
        // 先只读尺寸，避免直接 decode 大图 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxSide = 2048
        while ((bounds.outWidth / sample) > maxSide || (bounds.outHeight / sample) > maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** 懒创建相机输出文件并通过 FileProvider 暴露为 content:// Uri。 */
    private fun obtainCameraOutputUri(): Uri {
        cameraOutputUri?.let { return it }
        val dir = File(cacheDir, "camera_out").apply { if (!exists()) mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraOutputUri = uri
        return uri
    }
}

/** Compose 界面的不可变状态载体。 */
private data class UiState(
    val status: String = "点按钮开始测试",
    val outDump: String = "",
    val inDump: String = "",
    val bitmap: Bitmap? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoScreen(
    state: UiState,
    onCameraImplicit: () -> Unit,
    onCameraExplicit: () -> Unit,
    onPickerImplicit: () -> Unit,
    onPickerExplicit: () -> Unit,
    onGetContent: () -> Unit,
    onPickImages: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("我没带身份证 · Demo 测试床") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("① 相机拍照（源链路）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = onCameraImplicit, modifier = Modifier.fillMaxWidth()) {
                Text("A · 隐式 ACTION_IMAGE_CAPTURE")
            }
            Button(onClick = onCameraExplicit, modifier = Modifier.fillMaxWidth()) {
                Text("B · 显式 CapturePhotoActivity")
            }

            Text("② PhotoPicker 选图（目标链路）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = onPickerImplicit, modifier = Modifier.fillMaxWidth()) {
                Text("C · 隐式 ACTION_PICK_IMAGES")
            }
            Button(onClick = onPickerExplicit, modifier = Modifier.fillMaxWidth()) {
                Text("D · 显式 photopicker.MainActivity")
            }

            Text("③ 标准图片选择（A 类·应被系统级重定向）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = onGetContent, modifier = Modifier.fillMaxWidth()) {
                Text("E · GET_CONTENT image/*")
            }
            Button(onClick = onPickImages, modifier = Modifier.fillMaxWidth()) {
                Text("F · PICK images")
            }

            HorizontalDivider()
            Text("状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.status, color = MaterialTheme.colorScheme.primary)

            state.bitmap?.let { bmp ->
                Text("结果图", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "result",
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentScale = ContentScale.Fit,
                )
            }

            if (state.outDump.isNotEmpty()) {
                DumpCard("出站 Intent（startActivityForResult 参数）", state.outDump)
            }
            if (state.inDump.isNotEmpty()) {
                DumpCard("入站 result Intent（onActivityResult）", state.inDump)
            }
        }
    }
}

/** 单色底纹的等宽 dump 卡片，展示 Intent 序列化文本。 */
@Composable
private fun DumpCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Unspecified,
            )
        }
    }
}
