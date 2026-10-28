# 轻跳 / LIGHT SKIP

Android 开屏广告自动跳过与应用内弹窗关闭工具。

> 少等一秒，直接进入。

## v0.3.8 能力

- 开屏广告：识别「跳过」文字、资源 ID 和常见位置，支持点击与手势兜底。
- 应用内弹窗：执行手动学习规则或导入的李跳跳规则。
- 学习模式：选择 App 后自动打开；可直接点击广告上的 X / 关闭 / 跳过记录；一次最多 5 个。
- 二次弹窗：目标 App 保持前台时，持续监听内容变化；广告晚几秒出现也能扫描。
- 规则导入：从剪贴板导入李跳跳 `AllRules.json` 或单个 App 的 `popup_rules`。
- 小米 / Redmi：增加 HyperOS/MIUI 事件与窗口兼容层。
- 诊断与反馈：事件链路、扫描结果、点击结果、HarmonyOS/EMUI 信息和一键分享。
- 后台状态：区分 Android 电池白名单、厂商后台限制、AppOps 和待机分组，避免误报“受限”。
- 无网络、无广告 SDK、无截图、无 OCR、无前台常驻服务。
- 轻量低功耗：空闲只订阅状态、窗口和点击事件；完整窗口与内容事件仅在短会话或学习时开启。

### v0.3.8 开屏 CTA 误点修复

- 拒绝把“查看详情”“跳转第三方”“点击按钮”“立即购买”“下载应用”等广告 CTA 当成跳过按钮。
- 即使该节点带有 `skip` 类资源 ID，只要文案是 CTA，也不会点击。
- MEIZU 21 志愿汇实测：开屏进入 MainActivity，计数 `58 → 59`，未再点击“查看详情/跳转第三方”。
- 解决“学习一个关闭按钮后再次进入 App 乱点”的实际触发点。

### v0.3.7 防误点与学习节点兜底

- 修复 `轻跳 · 开屏跳过` 被系统设置页误识别为广告并点击的问题。
- 移除过宽的“文本包含跳过”规则，改为明确的 `点击跳过`、`广告3s跳过`、`点击关闭广告` 等格式。
- 系统设置、System UI、桌面加入不可守护名单，即使被勾选也不会自动扫描。
- `event.source` 根节点兜底同时接入学习模式，HyperOS 事件源可用时学习也能拿到节点。
- 学习点击后仍无窗口时，提示“系统未暴露窗口”，不再误导用户重复点 X。
- MEIZU 21 实测：系统无障碍页跳过计数保持不变（`43 → 43`），测试开屏仍正常跳过（`43 → 44`）。

### v0.3.6 事件源窗口兜底

- 当 `rootInActiveWindow` 和 `windows` 都拿不到目标窗口时，从无障碍事件 `event.source` 向上回溯目标窗口根节点。
- 回溯出的窗口树会作为备用 root 参与开屏扫描，并继续按目标包名过滤。
- 同一窗口按 `windowId` 去重，避免重复遍历。
- 0.3.5 日志已确认 `scans=1 roots=0`；0.3.6 针对这一层新增兜底。
- MEIZU 21 回归：测试开屏仍正常跳过，计数 `41 → 42`。
- **Redmi/HyperOS 仍无真机，不能声明该日志中的窗口隔离已被实机解决。**

### v0.3.5 系统性识别链修复

- 输入法和系统 UI 的窗口事件不再覆盖目标 App，也不会取消正在进行的开屏扫描。
- 修复 HyperOS/部分系统在弹出输入法后出现“扫描中断”的全局问题。
- 扩展开屏文案识别：`点击跳过`、`广告 3s 跳过`、`点击关闭广告` 等短文案。
- 保留 0.3.4 的 `roots=N [包名]` 诊断，可区分窗口树不可见和没有跳过候选。
- MEIZU 21 实测：打开搜索输入法后服务保持连接；测试开屏跳过计数 `39 → 40`。
- **Redmi/HyperOS 仍无真机复测，不能声明该机型已修复。**

### v0.3.4 系统设置确认与诊断增强

- 点击底部「设置」只切页，不会打开系统无障碍设置。
- 首页/设置页的「无障碍服务」入口改为先弹确认框；取消后停留在轻跳。
- 确认框明确说明：只有用户确认后才会打开系统页面。
- 开屏扫描诊断新增 `roots=N [包名]`，可区分“没有窗口树”和“有窗口但没有跳过候选”。
- `diagnosis` 在 `no candidate roots=0` 时会提示 MIUI/HyperOS 窗口树被隔离。
- MEIZU 21 实测：设置页切换不跳转；无障碍入口先显示确认框，取消后仍在 MainActivity。

### v0.3.3 后台任务开关与鸿蒙兼容

- 设置页新增「隐藏后台任务」开关，默认关闭。
- 默认保留最近任务，不再强制 `excludeFromRecents` 或每次离开都 `finishAndRemoveTask()`。
- 开启隐藏后，任务仍会从最近任务移除；同时提示 HarmonyOS 可能因此冻结无障碍服务。
- 修复设置页顶部状态卡片可点击但没有动作的问题。
- 需要说明：小窗可用、后台不可用是系统冻结后台进程的表现；轻跳无法绕过 OEM 的后台冻结策略，只能避免自己主动移除任务。
- MEIZU 21 实测：关闭隐藏时最近任务保留；开启隐藏时任务移除，但无障碍服务仍保持连接。
- **没有 HarmonyOS 真机，不能声明鸿蒙后台问题已经实机修复。**

### v0.3.1 志愿汇修复

- 开屏会话同包冷却从 2 秒降到 500ms，修复快速关闭再打开时根本不启动扫描。
- 全屏广告容器没有文字节点时，只在广告容器内尝试左上/右上多点位，不再只固定右上角。
- 兜底点击分时重试：约 1.2s、2.5s、4s、6s；广告容器消失后才结束并计数。
- 诊断新增 `last splash scan` / `last splash candidate`。
- 实测志愿汇：`skip_count 18 → 19`，诊断记录 `ACTION_CLICK 4S | 跳过`，随后进入 MainActivity。

### v0.3.2 学习模式与低功耗收尾

- 移除“必须先跳过开屏/必须先武装”的隐藏门槛；选择 App 后即可记录。
- 广告 SDK 或独立窗口的点击也按“用户选中的目标 App”保存。
- 接受 X、×、关闭、关闭广告、跳过、跳过 N 等按钮文字，也接受 viewId/desc。
- 只拒绝整张广告卡片/全屏容器这类明显不是按钮的区域。
- 实测不等待开屏直接点击测试弹窗 X：`learning_saved_count=1`，规则成功写入。
- 内容防抖 240ms → 60ms，活动通知间隔 100ms → 50ms，最小扫描间隔 250ms → 120ms。
- 离开 App 后重开绕过同包冷却；快速重开测试 `38 → 39 → 40`，第二次未被挡住。
- 修正服务关闭时点击守护卡片/开关会反复跳系统设置的问题；现在只提示，不强制跳转。
- 修正已允许厂商“后台高耗电”却仍显示“受限”的问题；未进白名单但系统未限制时显示“未受限”。
- 清理旧检测窗口、震动、关闭广告、统计开关等无入口字段，以及 61 个未使用资源。
- 空闲无障碍标志缩减为 `flagReportViewIds`；窗口细节读取只保留在短会话内。
- 高频 `TYPE_WINDOWS_CHANGED` 诊断详情按 300ms 采样，保留结论但减少重复窗口树读取。

## 规则导入

### 全部规则

1. 复制李跳跳规则库的 `AllRules.json` 到剪贴板。
2. 打开轻跳 → 设置 → 导入自定义规则。
3. 轻跳按 `packageName.hashCode()` 匹配本机已安装 App。
4. 只保存已安装 App 的规则，并自动加入守护清单。

公开规则格式示例：

```json
[
  {
    "1994036591": "{\"popup_rules\":[{\"id\":\"广告\",\"action\":\"跳过\"}]}"
  }
]
```

### 单个 App 规则

粘贴：

```json
{"popup_rules":[{"id":"发现新版本","action":"iv_cancel"}]}
```

轻跳会让你选择规则所属 App。

### 已兼容的规则字段

- `id`：触发文本 / `viewId` / `contentDescription`
- `action`：要点击的文本 / `viewId` / `contentDescription`
- `GLOBAL_ACTION_BACK`
- `&`：同一节点内同时包含多个片段
- `|`：任一分支匹配
- `=`：精确匹配
- `times` / `delay_popup`：保存但不作为弹窗主要逻辑；弹窗场景默认点击一次

`.json` 中少量规则带尾逗号，轻跳会做宽松解析，避免整条丢弃。

## 下载

- v0.3.8 Release：https://github.com/lieovoa-hue/LightSkip/releases/tag/v0.3.8
- Release APK：`dist/LightSkip-0.3.8.apk`
- 带测试开屏、双 X 弹窗、延迟弹窗和规则测试入口的 Debug APK：
  `dist/LightSkip-0.3.8-debug-with-demo.apk`

## 体积与权限

本机构建实测：

- Release APK：`145,625` 字节，约 `142 KB`
- Debug APK：`1,023,358` 字节，约 `999 KB`
- 无 `uses-permission`
- 无 `INTERNET`
- 无 Room、WorkManager、网络库、第三方统计 SDK

## 鸿蒙 / HarmonyOS 说明

需要先区分两类系统：

### HarmonyOS 2 / 3 / 4

底层仍兼容 Android APK，轻跳可以安装和运行。但在华为/荣耀设备上可能遇到：

- 无障碍服务显示开启但未真正绑定；
- 后台/自启动限制导致服务被冻结；
- 系统只投递部分窗口事件；
- 广告窗口被系统窗口隔离，节点不可读；
- 点击或手势被系统限制。

轻跳已加入 HarmonyOS/EMUI 信息识别、事件链路计数和诊断结论。

如果反馈是“小窗可用、普通后台不可用”：

1. 关闭设置页的「隐藏后台任务」，保留最近任务。
2. 在 HarmonyOS 应用启动管理中允许轻跳自启动、关联启动和后台活动。
3. 在最近任务中锁定轻跳；部分鸿蒙版本仍需保持小窗或前台，才能持续接收无障碍事件。
4. 打开诊断与反馈，分享 `hide background task`、事件计数和 `background` 状态。

这是 HarmonyOS 后台冻结问题，轻跳无法通过普通 Android 权限绕过，不能承诺不保留任务也能在后台工作。

### HarmonyOS NEXT / 5+

不再兼容 Android APK。此时轻跳无法安装或无法运行，这不是规则问题，也不是无障碍权限问题；需要单独开发 HarmonyOS 原生版本。

### 如何判断卡在哪一步

用户打开目标 App 出现广告后，回到轻跳：

1. 设置 → 诊断与反馈。
2. 查看 `diagnosis`。
3. 点击「分享诊断」，把文本发给开发者。

诊断会区分：

- 服务未连接；
- 服务已连接但没有任何无障碍事件；
- 有事件但没有目标窗口事件；
- 进入扫描但没有候选；
- 识别到候选但点击/手势失败；
- 未发现明确故障。

常用 adb 辅助命令：

```powershell
adb shell getprop | findstr /i "harmony emui hw_sc"
adb shell dumpsys accessibility
adb shell dumpsys activity top
adb logcat -d | findstr /i "lightskip Accessibility"
```

当前没有 HarmonyOS 真机，**不能声称鸿蒙已实机验证**。需要至少一台 HarmonyOS 2/3/4 设备完成上述报告，才能判断具体原因。

## 小米 / Redmi 兼容

针对 HyperOS/MIUI 增加：

- 短会话期间使用全事件类型；
- 空闲时收缩为状态、窗口和点击事件，完整内容事件只在短会话内开启；
- 同时判断 `event.packageName` 与 `rootInActiveWindow.packageName`；
- 允许扫描 MIUI 系统广告窗口包；
- Android 12+ 无障碍配置声明 `isAccessibilityTool`；
- 诊断中记录 root 窗口和所有窗口包名。

仍需 Redmi K70 / HyperOS 真机复测 QQ 音乐、百度地图、B 站。

## 低功耗设计

- 没有规则、没有会话、没有目标前台时，处理很快返回。
- 有规则的 App 前台时，内容事件用于延迟弹窗检测。
- 弹窗内容探针最多每 2 秒一次，每次最多 2 次扫描。
- 空闲无障碍标志仅保留 `flagReportViewIds`，不读取非重要视图和 WebView 增强节点。
- 开屏兼容模式扫描计划最多 8 次、点击尝试最多 5 次；只扫描目标 App 与 MIUI 系统广告窗口。
- 节点上限 400、深度 14。
- 诊断窗口详情按 300ms 采样，避免同一次启动重复读取窗口树。
- 无轮询、无 WakeLock、无开机广播、无前台常驻服务。

MEIZU 21 / Android 16 实测：

- 连续两次开屏跳过成功；
- AllRules 剪贴板导入成功；
- 2 秒后出现的同一 Activity 弹窗自动关闭成功；
- 志愿汇真实开屏跳过成功：`skip_count 5 → 6`；
- 学习模式连续点击两个 X：`learning_saved_count=2`，两条规则均写入；
- 已学习弹窗自动关闭成功，跳过计数 `+1`；
- 连续快速打开测试开屏 3 次：`skip_count 2 → 5`；
- 服务关闭时点击首页卡片/开关，页面保持 MainActivity，不跳系统设置；
- MEIZU 21 已允许“后台高耗电”但未加入 Doze 白名单：首页正确显示“未受限”；
- 「隐藏后台任务」关闭时：离开应用后最近任务仍在，无障碍服务保持连接；
- 「隐藏后台任务」开启时：离开应用后任务从最近任务移除，无障碍服务保持连接；
- 保留最近任务时连续打开测试开屏 2 次，跳过计数 `22 → 24`；
- 点击底部「设置」停留在 MainActivity；点击无障碍入口先显示确认框，取消后不跳转；
- 打开搜索输入法后无障碍服务仍连接，测试开屏跳过计数 `39 → 40`；
- event.source 备用窗口兜底加入后，测试开屏跳过计数 `41 → 42`；
- 系统无障碍页不再误点“轻跳 · 开屏跳过”，跳过计数保持 `43 → 43`；测试开屏 `43 → 44`；
- 志愿汇开屏不再点击“查看详情/跳转第三方”，实际进入 MainActivity，计数 `58 → 59`；
- 空闲 CPU 采样 `0%`；
- Release 服务空闲 PSS 约 `25 MB`（`25,600 KB`）；
- 会话结束后事件类型收缩回低频集合。

## 安装与使用

1. 安装 `dist/LightSkip-0.3.8.apk`。
2. 打开轻跳，进入系统无障碍设置开启服务。
3. Android 13+ 如果提示「受限设置」，先在应用信息里允许受限设置。
4. 在应用页选择守护 App。
5. 需要应用内弹窗时，学习一次或导入规则，并把「应用内弹窗」设为规则模式。
6. 回到首页确认「守护中」。

OEM 重装后可能出现「已开启未连接」，进入系统无障碍设置关闭一次再开启。

## 技术结构

```text
app/src/main/java/com/lightskip/app/
├── MainActivity.kt
├── OnboardingActivity.kt
├── data/
│   ├── AppRepository.kt
│   ├── Prefs.kt
│   ├── PopupMode.kt
│   ├── RuleLearningStore.kt
│   ├── ImportedRuleStore.kt        李跳跳规则兼容
│   └── LocalStats.kt
├── service/
│   ├── SkipAccessibilityService.kt 事件入口 / OEM 兼容 / 学习状态机
│   ├── SkipSessionController.kt    开屏会话
│   ├── PopupSessionController.kt   学习/导入规则执行
│   ├── NodeScanner.kt              有界节点遍历
│   ├── RuleEngine.kt               开屏规则
│   └── DiagnosticsStore.kt         诊断与分享报告
└── ui/
```

## 构建

```powershell
$env:JAVA_HOME = '<JDK 17>'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
.\gradlew.bat assembleDebug assembleRelease lintDebug --console=plain
```

构建产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
- `dist/LightSkip-0.3.8.apk`

验证结果：`assembleDebug`、`assembleRelease`、`lintDebug` 均通过；Lint
`0 errors / 105 warnings / 0 unused resources`。

## 已知限制

- 不能保证 100% 跳过。
- 纯图片、Canvas、SurfaceView、`FLAG_SECURE` 页面可能没有可访问节点。
- WebView 节点是否暴露取决于 App 和系统。
- 规则导入只覆盖本机已安装 App。
- 无 HarmonyOS、Xiaomi 17、Redmi K70 真机，当前不能声明这些设备已通过。
- 无障碍 API 跳广告存在平台政策风险，侧载或自有渠道更现实。

## 设计文档

- `design/mockup.png`
- `design/mockup.html`
- `design/mockup.css`
- `docs/design-proposal.md`
- `docs/v0.2-proposal.md`
- `docs/v0.3-litiao-compat.md`
