# 我没带身份证 · CaptureToPhotoPicker

> 一个 Xposed 模块：把 App 调起**相机拍照**的请求重定向到**系统相册选择器（PhotoPicker）**，选中的图片按"相机式结果"直接装填回去——调用方无感知，就像刚拍完照一样。支持 **LSPosed（root，Modern Xposed API 102）** 与 **LSPatch / NPatch（免 root）**。

## 它解决什么问题

很多 App 拍照只给一条路：调起系统相机（`com.oplus.camera/.component.CapturePhotoActivity` 或标准 `ACTION_IMAGE_CAPTURE`）。但你可能更想**从相册里挑一张现成的图**喂给它——比如证件照、截图、存好的素材。

本模块在中间做了一层透明转换：

```
App 调起相机 ──(出站拦截)──▶ 系统 PhotoPicker 选图
                                    │
App 拿到"相机结果" ◀─(入站翻译)── 选中的图片
```

## 核心机制

### 出站重定向
Hook `Activity#startActivityForResult`（两个重载都覆盖）：命中相机 Intent（显式 oplus 相机组件 或 `android.media.action.IMAGE_CAPTURE`）时，就地改写为 `android.provider.action.PICK_IMAGES`（系统 PhotoPicker），并暂存原 `EXTRA_OUTPUT`。

### 入站结果翻译（关键）
"移花接木"类工具只换 Intent 会失败，因为两条链路返回的数据形态根本不同。本模块对 `RESULT_OK` 的结果**同时补齐三种读取姿势**：

1. **EXTRA_OUTPUT 字节回写**——把选中图字节拷回调调用方原来指定的输出 Uri；
2. **`"data"` Bitmap 注入**——降采样（长边 ≤512px，防 Binder ~1MB 事务溢出）后塞进结果 Intent；
3. **data / clipData Uri**——保留 PhotoPicker 原生的 `content://media/picker/...` Uri 并附读权限。

调用方无论用哪种姿势读结果，都像刚拍完照。

### 作用域：点一下就是一个开关
- 在「作用域」页**点应用行打勾 = 启用**：LSPosed 下自动写入框架真实作用域（框架弹「允许作用域」授权框）并同时启用；再点一次 = **取消，立即停用**；
- 启用/取消都**不需要重启目标应用**（底层是跨进程实时名单）；唯一例外：首次加入作用域后需重启该应用一次完成注入，这是 Xposed 框架的物理限制；
- 没有多余的开关——一个应用只有一个状态：启用/未启用。

### 免 root 框架支持（LSPatch / NPatch）
本模块是 standard Modern Xposed（libxposed，`targetApiVersion=102`）模块，[JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch)（canary 活跃维护）与同源下游 [7723mod/NPatch](https://github.com/7723mod/NPatch) 原生支持加载，两种模式：

- **内置（集成/embed）模式**：打补丁时把本模块一起嵌进目标 APK——框架把作用域固定为宿主，模块**自动识别宿主并自动生效**，零配置（模块检测到自身位于宿主补丁包 `assets/lspatch/modules/` 内即直接启用，无需勾选）；
- **本地（经理/local）模式**：模块作为普通 App 单独安装，**在本模块「作用域」页直接点应用打勾即可**（勾选写入实时生效名单）；应用还没打补丁的，按模块内引导到 LSPatch/NPatch 管理器给它添加本模块后重新打补丁。

## 界面

Google Material 3（动态取色 / edge-to-edge），顶部标题栏（模块名「我没带身份证」+ 版本号 + GitHub 直达图标），两个 Tab：

- **状态**：模块激活状态（`observeFrameworkService` 异步监听）、**「第一次用？按这三步走」分步指引（可折叠）**、启用计数、工作方式说明、常见问题 FAQ；
- **作用域**：应用列表（搜索 / 排序：名称·包名·安装时间·更新时间 + 升降序 + 已选置顶 / 筛选：全部·用户·系统），**点应用行 = 启用/取消（弹确认框）**，没有多余开关。

## 安装

### LSPosed（root）
1. 安装 `capturetophotopicker-<版本>-android-arm64.apk`（模块本体）；
2. 在 LSPosed 管理器中**启用本模块**；
3. 打开模块 → 按「状态」页四步指引操作：作用域页点目标应用 → 系统弹「允许作用域」→ 允许 → **重启该应用一次** → 拨动 Switch 启用。

### LSPatch / NPatch（免 root）
- **内置模式（推荐）**：打补丁时把本模块 APK 一起嵌入目标应用 → 安装补丁包 → 自动生效，无需配置；
- **本地模式**：模块单独安装 → 在补丁管理器里给目标应用勾选本模块 → 重新打补丁安装 → 模块内拨 Switch。

`capturetophotopicker-demo-<版本>-android-arm64.apk` 是**测试靶子**（六个按钮调起相机/PhotoPicker/标准选图三类链路并 dump 出站/入站 Intent），用于验证与取证，非必需。

⚠️ **请勿将微信/QQ 等风控敏感应用设为目标**——重定向行为可能被其风控检测。

## 构建

```bash
./gradlew :module:assembleRelease :app:assembleRelease
```

技术栈：YukiHook 1.5.0（`yukihook-runtime-libxposed` 底座）+ libxposed api/service 102.0.0 + Kotlin 2.4.21 + AGP 9.4.1 + Jetpack Compose Material 3，compileSdk 37 / minSdk 26。

## 验证状态（如实说明）

- 已验证：构建产物 `META-INF/xposed/` 三件套正确（LSPatch `ModuleLoader` 识别依据）；LSPosed 装机后框架服务连通（`YukiHook.Module.scope` 成功读到真实作用域）；hooknext 抓到出站 Intent 记录；
- **未完整实机验证**：`requestScope` 授权弹框交互、实时开关"关后立即恢复原生"、端到端重定向装填、LSPatch/NPatch 实机加载与内置模式自动生效（LSPatch/NPatch 支持结论来自其源码调研，非实机）。发布供尝鲜，上述路径以实机表现为准。

## 许可

Apache-2.0。UI 结构借鉴 InstallerX-Revived / Guise_Reborn 的架构思路（二者为 GPL-3.0，本项目未复制其代码，仅自写同构实现）。
