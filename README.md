# 轻跳 / LIGHT SKIP

Android 开屏广告自动跳过工具。在打开应用时自动跳过开屏广告，轻跳在本机识别「跳过」按钮并自动点击。

> 少等一秒，直接进入。

## 当前状态

已完成可构建的 Android MVP：

- 欢迎 → 无障碍授权 → 应用推荐 → 首页
- 首页守护状态、今日跳过、平均响应、今日会话、上次拦截
- 应用守护清单、筛选、搜索、独立开关（包含系统浏览器等预装应用）
- 应用页提供「全选 / 清空」快捷操作
- 支持右上、右下两种常见跳过按钮位置
- 设置：检测窗口、尝试次数、关闭广告、轻震、本地统计、后台稳定运行、清除统计、隐私说明
- `AccessibilityService` 短会话：最长 6 秒、最多 3 次尝试
- 本地文本规则、节点扫描、点击/手势兜底
- 无 `INTERNET` 权限、无 Room、无 WorkManager、无网络库
- 深色模式、自定义 Toggle、可访问性标签

**已在 Android 真机上完成验证。** 内置测试页、微博开屏、系统浏览器开屏均验证成功；B 站测试时未复现广告，已加入右下角规则。构建、资源链接、R8 和 `lintVitalRelease` 已通过；实际跳过效果需要在手机上验证。

## 实现差异

- 首页第二张数据卡从设计稿的「今日耗电」改为「今日会话」。耗电无法在不引入额外权限和复杂测量的情况下可靠统计，不能用假数据冒充。
- 首页的跳过次数、平均响应、今日会话、上次拦截均来自本机真实统计。
- 「约省下 X 秒」按每次 1.5 秒估算，界面标注「估算」。
- Release 包不包含「测试开屏」入口；Debug 包包含。
- 设置页新增「后台稳定运行」入口；最近任务卡片通过 `excludeFromRecents` 隐藏。
- 应用页新增「全选 / 清空」；列表改为 `ListView` 复用行，离开主界面自动释放 UI。

## APK

构建产物：

- Debug（含「测试开屏」入口）：`app/build/outputs/apk/debug/app-debug.apk`
- Release（R8 + 资源压缩）：`app/build/outputs/apk/release/app-release.apk`
- 便于安装的副本：`dist/LightSkip-0.1.apk`

实测体积（本机构建）：

- Debug：约 `0.95 MB`
- Release：约 `115 KB`

Release 目前使用 debug 签名，方便本地安装测试。正式分发前请替换成自己的 release keystore。

## 安装与使用

1. 把 `dist/LightSkip-0.1.apk` 传到手机并安装。需要允许「安装未知来源应用」。
2. 打开轻跳，按引导进入无障碍授权页。
3. 在系统设置中开启：
   `设置 › 无障碍 › 已下载的应用 › 轻跳`
4. Android 13+ 如果提示「受限设置」，先在应用信息里允许受限设置，再开启无障碍。
5. 在应用页勾选要守护的 App。
6. 回到首页确认显示「守护中」。
7. 打开目标 App，轻跳会在开屏阶段尝试点击「跳过」。

### 测试开屏

Debug 包内置一个假开屏页面：

1. 安装 `app-debug.apk`。
2. 开启无障碍服务。
3. 进入「设置」→「测试开屏」。
4. 页面右上角有「跳过 5」，轻跳应在 1 秒内自动点击并返回。

Release 包不包含测试页。

## 构建

环境：

- JDK 17（配置 `JAVA_HOME`）
- Android SDK（配置 `ANDROID_HOME` 或 `local.properties`）
- Gradle 8.9（项目内 wrapper）
- AGP 8.7.3
- Kotlin 2.0.21

项目路径含中文，`gradle.properties` 已启用：

```properties
android.overridePathCheck=true
```

构建命令：

```powershell
$env:JAVA_HOME = '<你的 JDK 17 路径>'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
.\gradlew.bat assembleDebug assembleRelease --console=plain
```

## 技术结构

```text
app/src/main/java/com/lightskip/app/
├── MainActivity.kt                 主界面、首页/应用/设置
├── OnboardingActivity.kt           欢迎、授权、应用推荐
├── data/
│   ├── AppEntry.kt
│   ├── AppRepository.kt            桌面可见应用、推荐清单
│   ├── Prefs.kt                    设置与守护包名
│   └── LocalStats.kt               本地统计
├── service/
│   ├── SkipAccessibilityService.kt 无障碍服务入口
│   ├── SkipSessionController.kt    6 秒短会话状态机
│   ├── NodeScanner.kt              有界节点遍历
│   └── RuleEngine.kt               文本归一化与匹配
├── ui/
│   ├── LogoView.kt
│   ├── ToggleView.kt
│   └── OrbitView.kt
└── util/
    ├── AccessibilityUtils.kt
    ├── SystemBarUtils.kt
    └── UiUtils.kt
```

核心流程：

```text
目标 App 进入前台
  → 确认包名在守护清单
  → 启动 6 秒会话
  → 窗口内容变化 / 定时扫描
  → 找到「跳过」候选
  → ACTION_CLICK
  → 失败则 dispatchGesture 兜底
  → 成功或超时立即结束
```

规则默认匹配：

```text
跳过
跳过广告
跳过 5
跳过5s
5秒跳过
跳过广告 5
```

「关闭广告」默认关闭，需要在设置里手动开启。无文字 `×` 不识别。

## 轻量与隐私

- 不申请 `INTERNET`
- 不申请 `QUERY_ALL_PACKAGES`
- 不使用悬浮窗
- 不使用前台常驻服务
- 不注册开机广播
- 不持有 WakeLock
- 不截图、不做 OCR
- 不保存界面文本或画面
- 统计只保存在本机 `SharedPreferences`

APK 权限只有：

```text
android.permission.VIBRATE
```

## 后台运行、省电与最近任务

轻跳没有前台常驻服务，也不会显示常驻通知。核心是无障碍服务，由 Android 系统托管：

- 只要用户在系统设置里保持无障碍服务开启，系统会在进程被回收后重新绑定。
- 不轮询、不持有 WakeLock、不注册开机广播；只在目标 App 进入前台后的短会话里工作。
- 首页/设置界面不是服务；离开应用后 UI 会被释放，只保留无障碍服务。
- 已通过 `android:excludeFromRecents="true"` 隐藏最近任务卡片。桌面图标仍然保留，方便随时打开和卸载。
- 设置页新增「后台稳定运行」入口，打开系统电池优化列表。轻跳不申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，需要用户手动把轻跳设为「不限制」。

OEM 额外设置：

- 小米：省电策略 → 无限制；自启动 → 允许
- 华为：应用启动管理 → 手动管理 → 允许自启动 / 关联启动 / 后台活动
- OPPO / vivo：允许自启动与后台运行
- 一加 / realme：电池 → 不限制；允许自启动

如果系统仍然回收服务，优先检查无障碍服务是否被系统关闭，以及 OEM 的「后台冻结 / 深度睡眠」设置。

「隐藏后台页面」隐藏的是最近任务卡片，不是桌面图标，也不是从系统设置里隐身。隐藏桌面图标会让应用难以找到和卸载，不采用。

实际耗电和内存必须在真机上用 `adb shell dumpsys meminfo com.lightskip.app` 和 Battery Historian 测量；当前没有设备，不能给出真实数值。

## 性能优化

- 应用列表改为 `ListView` + `BaseAdapter` 复用行，不再一次性创建 113 行 View。
- 离开主界面时 `onStop` 调用 `finishAndRemoveTask()` 释放 UI，只保留无障碍服务；重新打开从首页开始。
- 同一应用会话结束后 8 秒内不重复启动；只有前台包名变化时才开启新会话。
- 实测（Android 真机，Debug 包）：
  - 离开 UI 后 `Views = 0`、`Activities = 0`
  - 后台 PSS 约 `59.5 MB`（会随系统和机型变化）
  - 空闲 CPU `0%`
  - Release APK 约 `115 KB`
- 仍然没有前台服务、常驻通知、轮询、WakeLock 或开机广播。

应用清单越大，打开任意被守护应用都会启动一次短会话。建议只保留真正有开屏广告的 20–30 个应用；「全选」适合临时批量开启，不建议长期全选。

## 已知限制

- 不能保证 100% 跳过。
- 纯图片、Canvas、WebView、`FLAG_SECURE` 页面可能没有可读文本。
- 自定义绘制且没有无障碍标签的按钮无法识别。
- 跳过按钮在倒计时结束前隐藏的广告，只能在 6 秒窗口内等待。
- 部分 App 的正常内容里也有「跳过」，位置启发式只能降低误触。
- OEM 省电策略可能延迟或终止服务。
- 无障碍 API 跳广告存在 Google Play 政策风险，侧载或自有渠道更现实。

## 验收清单

- [x] Debug / Release 构建通过
- [x] R8 + 资源压缩通过
- [x] Release APK 可使用 v2 签名验证
- [x] 权限只有 `VIBRATE`
- [x] 无 `INTERNET` 权限
- [x] 5 个页面与完整引导流程
- [x] 6 秒 / 3 次短会话
- [x] 文本规则与节点扫描
- [x] 本地统计与清除
- [x] 真机验证跳过成功率（测试页 / 微博 / 系统浏览器）
- [ ] 真机验证后台存活 24 小时
- [x] 真机测量后台内存（约 59.5 MB PSS）
- [ ] 长时间耗电测量
- [ ] 确认最近任务卡片已隐藏
- [ ] 真机验证冷启动
- [ ] 目标 App 误触回归测试

## 设计稿

- `design/mockup.png`
- `design/mockup.html`
- `design/mockup.css`
- `docs/design-proposal.md`
