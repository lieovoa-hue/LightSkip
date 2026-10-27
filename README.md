# 轻跳 / LIGHT SKIP

Android 开屏广告自动跳过与应用内弹窗关闭工具。

> 少等一秒，直接进入。

## v0.2 能力

- 开屏广告：识别「跳过」文字、资源 ID 和右上/右下位置，支持点击与手势兜底。
- 应用内弹窗：只执行用户手动学习过的关闭按钮，不再自动乱点右上角 X。
- 学习模式：选择 App 后自动打开；跳过开屏后开始记录；一次最多记录 5 个关闭按钮。
- 多点学习：同一 App 有多个广告时，可连续点击每个广告的 X；重复按钮不会重复计数。
- 自动结束：返回轻跳、再次点「学习关闭按钮」或记录满 5 个后结束。
- 一键检查：无障碍连接、后台运行、守护应用、弹窗模式。
- 本机诊断：最近事件、候选节点、点击结果，可复制脱敏文本。
- 无网络、无广告 SDK、无截图、无 OCR。

## 学习流程

1. 进入「设置」→「学习关闭按钮」。
2. 选择要学习的 App。轻跳会自动打开该 App。
3. 轻跳先处理开屏广告；跳过成功或等待结束后进入记录状态。
4. 在第一个弹窗广告上点击一次小 X。
5. 如果还有第二个、第三个弹窗广告，继续分别点击它们的 X。
6. 返回轻跳，或再次点击「学习关闭按钮」结束学习。

当前每个 App 最多保存 5 条关闭规则。规则优先使用 `viewId`、`contentDescription`，没有元数据时使用相对区域 + 小型可点击节点兜底。

## 下载

- v0.2 Release：https://github.com/lieovoa-hue/LightSkip/releases/tag/v0.2
- Release APK：`dist/LightSkip-0.2.apk`
- 带测试开屏和双 X 测试弹窗的 Debug APK：`dist/LightSkip-0.2-debug-with-demo.apk`
- 旧版 v0.1：https://github.com/lieovoa-hue/LightSkip/releases/tag/v0.1

## 体积与权限

本机构建实测：

- Release APK：`127,281` 字节，约 `124 KB`
- Debug APK：`1,047,138` 字节，约 `1.0 MB`
- Release 使用 R8 + 资源压缩
- 无 `uses-permission`
- 无 `INTERNET`
- 无 Room、WorkManager、网络库、第三方统计 SDK

Release 目前使用 debug 签名，方便侧载测试。正式分发前应替换为自己的 release keystore。

## 安装与使用

1. 安装 `dist/LightSkip-0.2.apk`。
2. 打开轻跳，进入系统无障碍设置开启服务。
3. Android 13+ 如果提示「受限设置」，先在应用信息里允许受限设置。
4. 在应用页勾选要守护的 App。
5. 回到首页确认显示「守护中」。
6. 打开目标 App，轻跳会在短会话内尝试跳过开屏。
7. 需要关闭应用内弹窗时，先在设置里完成一次学习。

部分 OEM 在重装 APK 后会出现「设置里已开启，但服务未连接」。此时进入系统无障碍设置，把轻跳关闭一次再开启即可；MEIZU 21 上已复现并验证该恢复方式。

## 低频与省电设计

- 无障碍空闲时只订阅窗口状态、窗口变化和点击事件。
- `TYPE_WINDOW_CONTENT_CHANGED` 和目标窗口读取只在开屏/弹窗短会话内开启。
- 会话结束后立即恢复低频事件类型，并移除多窗口读取标志。
- 无障碍通知合并间隔为 `100ms`，避免动画和倒计时造成事件风暴。
- 节点扫描限制为最多 `400` 个节点、`14` 层。
- 只扫描目标 App 自己的窗口，不遍历系统 UI、输入法等窗口。
- 开屏兼容模式最多 8 次扫描，普通模式 5 次；内容变化最多触发 3/2 次。
- 内容变化防抖：开屏 `240ms`，弹窗 `300ms`。
- 守护包名使用进程内缓存，避免高频事件反复复制 100+ 个包名。
- 不轮询、不持有 WakeLock、不注册开机广播、不使用前台常驻服务。
- 离开 UI 后释放 Activity/View，只保留系统托管的无障碍服务。

真机采样（MEIZU 21，Android 16，Debug 包）：

- 会话结束后无障碍事件类型已收缩为 `VIEW_CLICKED / WINDOW_STATE_CHANGED / WINDOWS_CHANGED`
- 空闲 CPU 采样为 `0%`
- 后台 PSS 约 `52–57 MB`，随系统版本和机型波动
- 连续两次内置开屏均跳过成功
- 一次学习连续记录两个 X，`learning_saved_count=2`
- 学习后再次打开测试弹窗可自动关闭
- B 站自动拉起、跳过开屏、进入学习状态验证成功

## 后台运行与最近任务

轻跳没有前台常驻服务，也没有常驻通知。核心是无障碍服务，由 Android 系统托管。

- 无障碍服务保持开启时，系统会在进程回收后重新绑定。
- 最近任务卡片通过 `excludeFromRecents` 隐藏。
- 桌面图标保留，方便打开设置和卸载。
- OEM 省电策略可能暂停无障碍服务。

OEM 额外设置：

- 小米 / Redmi：省电策略 → 无限制；自启动 → 允许
- 华为：应用启动管理 → 手动管理 → 允许自启动 / 关联启动 / 后台活动
- OPPO / vivo：允许自启动与后台运行
- 一加 / realme：电池 → 不限制；允许自启动

## 技术结构

```text
app/src/main/java/com/lightskip/app/
├── MainActivity.kt                 首页 / 应用 / 设置
├── OnboardingActivity.kt           引导与授权
├── data/
│   ├── AppRepository.kt            可见应用
│   ├── Prefs.kt                    设置、学习状态、目标包缓存
│   ├── PopupMode.kt                弹窗模式
│   ├── RuleLearningStore.kt        多规则学习与相对区域
│   └── LocalStats.kt               本机统计
├── service/
│   ├── SkipAccessibilityService.kt 事件入口与学习状态机
│   ├── SkipSessionController.kt    开屏短会话
│   ├── PopupSessionController.kt   弹窗学习/执行
│   ├── NodeScanner.kt              有界节点遍历
│   ├── RuleEngine.kt               文本 / ID / 位置规则
│   └── DiagnosticsStore.kt         本机诊断
└── ui/                             列表、Logo、Toggle
```

核心流程：

```text
目标 App 进入前台
  → 开启 10 秒短会话（兼容模式）
  → 只在目标窗口内扫描
  → 找到「跳过」候选
  → ACTION_CLICK
  → 失败则 dispatchGesture
  → 成功或超时结束

学习模式
  → 选择 App 并自动打开
  → 等待开屏跳过
  → 进入记录状态
  → 用户逐个点击广告 X
  → 最多保存 5 条规则
  → 返回轻跳结束
```

## 构建

环境：

- JDK 17
- Android SDK 36
- Gradle 8.9（项目内 wrapper）
- AGP 8.7.3
- Kotlin 2.0.21

```powershell
$env:JAVA_HOME = '<你的 JDK 17 路径>'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
.\gradlew.bat assembleDebug assembleRelease lintDebug --console=plain
```

构建产物：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`
- 便于安装的副本：`dist/LightSkip-0.2.apk`

## 已知限制

- 不能保证 100% 跳过。
- 纯图片、Canvas、SurfaceView、`FLAG_SECURE` 页面可能没有可访问节点。
- 没有 `viewId` / `contentDescription` 的关闭按钮依赖相对区域和小型可点击节点兜底。
- 区域规则会先确认该区域存在小型可点击节点，不会盲点。
- OEM 省电策略、无障碍服务冲突、系统权限弹窗可能影响识别。
- Redmi K70 / HyperOS 3.0.307.0、QQ 音乐、百度地图仍需对应真机复测。
- 无 Xiaomi 17 实机，发热优化通过事件订阅、扫描预算和 MEIZU 21 采样验证。

## 测试记录

- `assembleDebug`：通过
- `assembleRelease`：通过
- `lintDebug`：`0 errors, 160 warnings`
- Release v2 签名校验：通过
- 无危险权限：通过
- 连续两次内置开屏：通过
- 多 X 学习（2 条规则）：通过
- 学习后自动关闭弹窗：通过
- 真实 App 自动拉起：通过
- 会话结束后低频事件收缩：通过

## 设计稿

- `design/mockup.png`
- `design/mockup.html`
- `design/mockup.css`
- `docs/design-proposal.md`
- `docs/v0.2-proposal.md`
