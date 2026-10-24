# 我没带身份证 · CaptureToPhotoPicker

> 一个 Xposed 模块：把 App 调起**相机拍照**的请求重定向到**系统相册选择器（PhotoPicker）**，选中的图片按"相机式结果"直接装填回去——调用方无感知，就像刚拍完照一样。支持 **LSPosed（Modern Xposed API 102）** 与 **LSPatch / NPatch（免 root）**。

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
- 在「作用域」页**点应用行打勾 = 启用**，再点一次 = **取消，立即停用**；
- **LSPosed 模式**：打勾时自动写入框架真实作用域（系统弹「允许作用域」授权框，点允许即加入）；
- **LSPatch / NPatch 本地模式**：**不请求作用域**（补丁框架不允许运行时改作用域，请求必失败），打勾只写入生效名单，注入范围由补丁管理器在打补丁时决定；
- 启用/取消都**不需要重启目标应用**（底层是跨进程实时名单，宿主经模块导出的 ContentProvider 读取）；唯一例外：LSPosed 下首次加入作用域后需重启该应用一次完成注入，这是 Xposed 框架的物理限制；
- 列表里每个应用只显示一个状态徽标：**已生效** / **待生效**（勾了但还没注入——LSPosed 下待授权、本地模式下待打补丁）/ 无徽标（未勾选）。

### 免 root 框架支持（LSPatch / NPatch）
本模块是 standard Modern Xposed（libxposed，`targetApiVersion=102`）模块，[JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch)（canary 活跃维护）与同源下游 [7723mod/NPatch](https://github.com/7723mod/NPatch) 原生支持加载（其 `ModuleLoader` 认 `META-INF/xposed/java_init.list` + `module.prop` 的 `targetApiVersion>=101`），两种模式：

- **内置（集成/embed）模式**：打补丁时把本模块一起嵌进目标 APK——框架把作用域固定为宿主，模块**自动识别宿主并自动生效**，零配置。
  识别方式：读宿主 manifest 的补丁配置 meta-data（NPatch 键 `npatch`、LSPatch 键 `lspatch`，值是 base64 的 config JSON，其中 `useManager=false` 即内置模式）。
  ⚠️ 不能用 `appInfo.sourceDir` 开 zip 找内嵌模块——签名绕过模式下框架会把 `sourceDir` 重定向到 cache 里的 `origin.apk`（原始未打补丁包），那样检测必然失败、模块静默不生效；
- **本地（经理/local）模式**：模块作为普通 App 单独安装，**在本模块「作用域」页直接点应用打勾即可**（写入生效名单，实时启停）；应用还没打补丁的，列表会显示「待生效」，按提示到 LSPatch/NPatch 管理器给它勾选本模块后重新打补丁。

模块会自动检测当前处于哪种模式并显示在「状态」页（LSPosed 模式 / 免 root · 内置模式 / 免 root · 本地模式），检测**不依赖框架服务是否连接**——本地模式下 UI 进程本来就拿不到服务，那是常态而非异常。

## 界面

Google Material 3（动态取色 / edge-to-edge），顶部标题栏（模块名「我没带身份证」+ 版本号 + GitHub 直达图标），两个 Tab：

- **状态**：**当前运行模式**（LSPosed 模式 / 免 root · 内置模式 / 免 root · 本地模式，检测不依赖服务连接）、框架信息、**「第一次用？按这三步走」分步指引（可折叠）**、启用计数、工作方式说明、常见问题 FAQ；
- **作用域**：应用列表（搜索 / 排序：名称·包名·安装时间·更新时间 + 升降序 + 已选置顶 / 筛选：全部·用户·系统），**点应用行 = 启用/取消（弹确认框）**，没有多余开关。

## 安装

### LSPosed
1. 安装 `capturetophotopicker-<版本>-android-arm64.apk`（模块本体）；
2. 在 LSPosed 管理器中**启用本模块**（装完或更新后重启一次手机）；
3. 打开模块 → 「作用域」页点目标应用 → 系统弹「允许作用域」授权框 → 点允许 → **重启该应用一次**完成注入。之后启停都不用再重启。

### LSPatch / NPatch（免 root）
- **内置模式（推荐，零配置）**：打补丁时把本模块 APK 一起嵌入目标应用 → 安装补丁包 → 自动生效。命令行示例（NPatch v1.0.9 CLI）：
  ```bash
  java -jar jar-v1.0.9-833-release.jar -m capturetophotopicker-<版本>-android-arm64.apk -l 2 -npa 目标应用.apk
  ```
- **本地模式**：模块单独安装 → 在补丁管理器里给目标应用勾选本模块 → 重新打补丁安装 → 之后在模块「作用域」页点应用打勾即可实时启停（此模式不会弹授权框、也不需要框架连接）。

⚠️ NPatch 请用 **v1.0.9 及以上**：1.0.8 及更早版本有上游 bug（打补丁后的应用启动即崩 `Writable dex file ... is not allowed`，见其 issue #153/#155，与本模块无关）。

`capturetophotopicker-demo-<版本>-android-arm64.apk` 是**测试靶子**（A–F 六个按钮调起相机/PhotoPicker/标准选图链路并 dump 出站/入站 Intent，支持广播自动化触发），用于验证与取证，非必需。

⚠️ **请勿将微信/QQ 等风控敏感应用设为目标**——重定向行为可能被其风控检测。

## 构建

```bash
./gradlew :module:assembleRelease :app:assembleRelease
```

技术栈：YukiHook 1.5.0（`yukihook-runtime-libxposed` 底座）+ libxposed api/service 102.0.0 + Kotlin 2.4.21 + AGP 9.4.1 + Jetpack Compose Material 3，compileSdk 37 / minSdk 26。

## 验证状态（如实说明）

**已实机验证**（OnePlus PKR110 / ColorOS / Android 16）：
- 构建产物 `META-INF/xposed/` 三件套正确（`targetApiVersion=102`、`staticScope=false`），这是 LSPatch/NPatch `ModuleLoader` 的识别依据；
- **NPatch 1.0.9 内置(embed)模式端到端重定向生效**：用 NPatch CLI 复刻内置补丁并覆盖安装后，触发 demo 链路 A（隐式 `ACTION_IMAGE_CAPTURE`）与链路 B（显式指名 `com.oplus.camera/.component.CapturePhotoActivity`），前台 Activity 均切到 `com.android.photopicker/.MainActivity`。链路 B 显式指名相机组件，是 hook 确实生效的决定性证据；
- LSPosed 装机后框架服务连通（`YukiHook.Module.scope` 成功读到真实作用域）；模块 UI 正确识别并显示「LSPosed 模式 / LSPosed 2.2.1 · API 102」；
- 生效名单的跨进程 ContentProvider 已注册且可查询（`content query --uri content://com.richkobe.capturetophotopicker.config/enabled` 正常返回、无 SecurityException）。

**尚未实机验证**（以实机表现为准）：
- LSPosed 下 `requestScope` 的「允许作用域」授权弹框交互；
- 实时开关「关闭后立即恢复原生相机」（机制已就位，未做前后对照实测）；
- 选图结果回填到真实第三方应用（如剪映/豆包）的表现——目前只在自带 demo 靶子上验过；
- **LSPatch**（只实测了同源的 NPatch）与**本地(local)模式**的完整链路。

## 许可

Apache-2.0。UI 结构借鉴 InstallerX-Revived / Guise_Reborn 的架构思路（二者为 GPL-3.0，本项目未复制其代码，仅自写同构实现）。
