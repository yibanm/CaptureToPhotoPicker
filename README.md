# 我没带身份证 · CaptureToPhotoPicker

> 一个 LSPosed（Modern Xposed / API 102）模块：把 App 调起**相机拍照**的请求重定向到**系统相册选择器（PhotoPicker）**，选中的图片按"相机式结果"直接装填回去——调用方无感知，就像刚拍完照一样。

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

### 作用域：双层模型
- **注入层** = LSPosed 真实作用域：模块 UI 经 libxposed `XposedService`（`YukiHook.Module.scope`）直接读写框架真相源，选中应用时**由框架弹出「允许作用域」授权框**，无需 root 改库；
- **生效层** = 实时开关：存 libxposed `RemotePreferences`（跨进程），hook 每次触发实时读取——**开/关都不需要重启目标应用**（首次把新应用纳入作用域需重启一次完成注入，这是 Xposed 框架的物理限制）。

## 界面

Google Material 3（动态取色 / edge-to-edge），两个 Tab：

- **状态**：模块激活状态（`observeFrameworkService` 异步监听）、框架信息、实时开关计数；
- **作用域**：应用列表（搜索 / 排序：名称·包名·安装时间·更新时间 + 升降序 + 已选置顶 / 筛选：全部·用户·系统），点应用行弹确认框管理 LSPosed 真实作用域，行内 Switch 管实时生效开关。

## 安装

1. 需要已 Root 并安装 **LSPosed**（JingMatrix fork 或官方，框架 API ≥ 101，推荐 102）；
2. 安装 `module-release.apk`（模块本体）；
3. 在 LSPosed 管理器中**启用本模块**；
4. 打开模块 → 作用域页 → 点目标应用 → 系统弹「允许作用域」→ 允许 → **重启该应用一次**完成注入；
5. 在列表里拨动该应用的 Switch 启用实时重定向。

`app-release.apk` 是**测试靶子**（四个按钮分别以隐式/显式方式调起相机与 PhotoPicker，并 dump 出站/入站 Intent），用于验证与取证，非必需。

## 构建

```bash
./gradlew :module:assembleRelease :app:assembleRelease
```

技术栈：YukiHook 1.5.0（`yukihook-runtime-libxposed` 底座）+ libxposed api/service 102.0.0 + Kotlin 2.4.21 + AGP 9.4.1 + Jetpack Compose Material 3，compileSdk 37 / minSdk 26。

## 验证状态（如实说明）

- 已验证：构建产物 `META-INF/xposed/` 三件套正确；装机后框架服务连通（`YukiHook.Module.scope` 成功读到真实作用域）；hooknext 抓到出站 Intent 记录；
- **未完整实机验证**：`requestScope` 授权弹框交互、实时开关"关后立即恢复原生"、端到端重定向装填。发布供尝鲜，上述路径以你实机表现为准。

## 许可

Apache-2.0。UI 结构借鉴 InstallerX-Revived / Guise_Reborn 的架构思路（二者为 GPL-3.0，本项目未复制其代码，仅自写同构实现）。
