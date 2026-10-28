# Changelog

## v0.3.6 - 2026-10-28

### 修复

- 当 HyperOS/MIUI 的 `rootInActiveWindow` 与 `windows` 都为空时，改用无障碍事件 `event.source` 向上查找目标窗口根节点。
- 备用 root 会参与开屏扫描，并按 `windowId` 与现有 root 去重。
- 保留目标包名校验，不扫描事件源中的系统/其他应用节点。

### 背景

- 0.3.5 日志已确认输入法取消问题修复：`scans` 从 `0` 变为 `1`。
- 但最新日志仍是 `roots=0 []`，说明 HyperOS 没有把目标窗口放进标准窗口列表；0.3.6 增加事件源兜底。

### 验证

- MEIZU 21：加入 root hint 后测试开屏仍成功，跳过计数 `41 → 42`。
- `assembleDebug`、`assembleRelease`、`lintDebug` 通过。
- Lint：`0 errors / 105 warnings / 0 unused resources`。

### 限制

- 没有 Redmi/HyperOS 真机，不能声明 window root hint 已在该设备验证。
- 如果 0.3.6 仍显示 `roots=0`，说明事件源也拿不到目标节点，系统确实隔离了窗口树。

## v0.3.5 - 2026-10-28

### 修复

- 输入法、系统 UI 的窗口事件不再被当成目标 App 前台。
- 这类瞬时窗口事件不再取消当前开屏扫描会话。
- 输入法内容变化只在目标会话仍存在时转交给目标 App 扫描。
- 扩展短文案识别：`点击跳过`、`广告 3s 跳过`、`点击关闭广告` 等。

### 诊断结论

- 日志中的 `125` 次扫描、`0` 候选说明问题是全局识别链，不是 QQ 音乐单点。
- Redmi `25060RK16C / dali` 是 HyperOS/Android 16，不是 HarmonyOS。
- 0.3.5 修复了输入法/系统窗口中断扫描这一系统性问题，但仍需 HyperOS 真机复测。

### 验证

- MEIZU 21：输入法弹出并输入文字后，无障碍服务保持连接。
- MEIZU 21：测试开屏跳过计数 `39 → 40`。
- `assembleDebug`、`assembleRelease`、`lintDebug` 通过。
- Lint：`0 errors / 105 warnings / 0 unused resources`。

### 限制

- 没有 Redmi/HyperOS 真机，不能声明该日志中的窗口树问题已在对应设备修复。
- 如果新日志显示 `roots=0`，仍属于系统窗口隔离；如果 roots 有目标包名但仍无候选，则继续补广告规则。

## v0.3.4 - 2026-10-28

### 修复

- 点击底部「设置」只切换应用内页面，不会打开系统无障碍设置。
- 首页和设置页的无障碍入口改为确认对话框；取消后停留在轻跳。
- 确认框明确提示只有确认后才会打开系统设置，避免误触和误判为自动跳转。

### 诊断增强

- 开屏扫描记录新增 `roots=N [包名]`。
- `diagnosis` 可区分“没有拿到目标窗口树”和“拿到窗口但没有跳过候选”。
- 对 `no candidate roots=0` 增加 MIUI/HyperOS 窗口隔离提示。

### 验证

- MEIZU 21：点击底部「设置」停留 MainActivity。
- MEIZU 21：点击无障碍入口先显示确认框；取消后停留 MainActivity。
- MEIZU 21：安装包版本 `0.3.4` / versionCode `7`。
- `assembleDebug`、`assembleRelease`、`lintDebug` 通过。
- Lint：`0 errors / 105 warnings / 0 unused resources`。

### 关于 Redmi/HyperOS 日志

- 日志设备 `Xiaomi Redmi 25060RK16C / dali` 为 HyperOS/Android 16，不是 HarmonyOS。
- 服务已连接且后台无限制；QQ 音乐前台时 root 仍为 `com.miui.home`、窗口为空，属于窗口树不可见/时机问题。
- 该问题不是后台冻结；需要升级后重新采集 `roots` 诊断，不能仅凭 0.3.2 日志判断为鸿蒙故障。

## v0.3.3 - 2026-10-28

### 新增

- 设置页新增「隐藏后台任务」开关，默认关闭。
- 默认保留最近任务，避免应用主动从最近任务移除后，被 HarmonyOS/部分 OEM 判定为可冻结后台。
- 开启隐藏时给出 HarmonyOS 冻结无障碍服务的风险提示。

### 修复

- 移除 Manifest 中两个 Activity 的强制 `excludeFromRecents`。
- 移除 `MainActivity.onStop()` 无条件调用 `finishAndRemoveTask()` 的行为。
- 修复设置页顶部状态卡片可点击但没有动作的问题。
- 诊断报告新增 `hide background task` 状态。

### 检查结论

- “小窗挂着能跳、普通后台不能跳”说明系统在小窗时仍给应用前台/可见状态；进入普通后台后冻结了进程或无障碍事件。
- 本次改动只能避免轻跳主动隐藏任务，不能绕过 HarmonyOS 的后台冻结策略。
- 仍建议用户关闭「隐藏后台任务」，并在系统设置中允许自启动、后台活动，必要时锁定最近任务。

### 验证

- MEIZU 21：默认关闭隐藏时，离开应用后最近任务仍在，无障碍服务保持连接。
- MEIZU 21：开启隐藏后，任务从最近任务移除，无障碍服务仍保持连接，应用进程仍存活。
- MEIZU 21：保留任务状态下连续打开测试开屏 2 次，跳过计数 `22 → 24`。
- Release 服务空闲 CPU `0%`，PSS `25,600 KB`。
- `assembleDebug`、`assembleRelease`、`lintDebug` 通过。
- Lint：`0 errors / 105 warnings / 0 unused resources`。

### 限制

- 没有 HarmonyOS 真机，不能声明鸿蒙后台问题已实机修复。
- 如果系统仍冻结后台进程，只能依赖 OEM 白名单、最近任务锁定或小窗模式。

## v0.3.2 - 2026-10-28

### 修复

- 学习模式选择 App 后即可记录，不再依赖“先跳过开屏”的隐藏门槛。
- 支持记录 X、×、关闭、关闭广告、跳过、跳过 N，以及对应 viewId/contentDescription。
- 广告 SDK 或独立广告窗口中的点击，也会保存到用户选择的目标 App。
- 弹窗关闭或页面切换期间增加 1.5 秒收尾宽限，避免点击事件丢失。
- 无障碍服务关闭时，首页卡片和开关只提示，不再自动跳转系统设置。
- 修正已允许厂商“后台高耗电”却仍显示“受限”的问题；现在会综合电池白名单、系统后台限制、AppOps 和待机分组判断。
- 修正快速关闭再打开同一 App 时可能沿用 500ms 冷却而漏跳的问题。
- 修正开屏会话包名判断与系统广告窗口兜底逻辑。

### 优化

- 空闲无障碍标志缩减为 `flagReportViewIds`；完整窗口、非重要视图和内容事件只在短会话/学习时开启。
- 内容变化防抖 60ms、活动通知间隔 50ms、最小扫描间隔 120ms。
- 高频窗口变化诊断详情按 300ms 采样，减少重复窗口树读取。
- 移除旧检测窗口、震动、关闭广告、统计开关等无入口配置。
- 移除未使用的统计字段、工具方法、颜色和 61 个无用资源。
- 保持无网络、无第三方 SDK、无 WakeLock、无前台常驻服务。

### 验证

- 学习模式连续记录两个 X：`learning_saved_count=2`。
- 已学习弹窗自动关闭成功。
- 连续快速打开测试开屏 3 次：`skip_count 2 → 5`。
- 志愿汇真实开屏跳过成功：`skip_count 5 → 6`。
- 服务关闭时页面保持 MainActivity，不跳系统设置。
- MEIZU 21 已允许厂商“后台高耗电”、未加入 Doze 白名单时，首页显示“未受限”。
- Release 版空闲 CPU `0%`，服务空闲 PSS `23,316 KB`。
- `assembleDebug`、`assembleRelease`、`lintDebug` 通过。
- Lint：`0 errors / 104 warnings / 0 unused resources`。

### 限制

- HarmonyOS、Xiaomi 17、Redmi K70 仍无真机复测，不能声明已通过。
- 纯图片、Canvas、SurfaceView、`FLAG_SECURE` 页面可能没有可访问节点。
