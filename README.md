# 小鹿播放增强器（PanPlayer）

一个用 Kotlin + Jetpack Compose + Media3/ExoPlayer 编写的 Android 原生视频播放器。

**设计前提：只播放你已经合法获得访问权限的视频。**
本应用不包含任何破解会员、绕过付费、破解 DRM、伪造授权或窃取账号的能力，也不应被用于播放无权限的内容。

- 合法使用：播放你拥有权限的本地视频、直接可访问的视频 URL / m3u8 直播流 / mpd，使用自带功能（倍速、手势、画质切换、播放记录、画中画）。
- 网盘视频：仅支持“把已登录网盘 App 生成的可播放直链 / 已下载的视频文件”交给本播放器播放。**网盘客户端生成的直链通常带时间戳、签名、IP 与 UA 校验，可能很快失效，且不能用于绕过会员限制。**

---

## 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.10.01，Material3） |
| 播放 | AndroidX Media3 1.5.1（ExoPlayer） |
| 导航 | Navigation Compose |
| 存储 | DataStore Preferences（设置）、SharedPreferences（播放记录，纯手写序列化） |
| 构建 | AGP 8.7.3、Gradle 8.9（wrapper） |
| SDK | minSdk 26（画中画/自适应图标）、targetSdk 35、compileSdk 35 |

---

## 目录结构

```
app/src/main/java/com/panplayer/app/
├── MainActivity.kt          # 入口、路由、分享解析、自动画中画
├── VideoPlayerApp.kt        # Application，初始化 AppServices
├── data/
│   ├── AppServices.kt       # 全局单例出口
│   ├── AppPreferences.kt    # 设置（DataStore）
│   ├── PlaybackRepository.kt# 播放记录
│   ├── LocalVideoRepository.kt # MediaStore 视频扫描
│   └── model/Models.kt      # PlaybackRecord / VideoItem
├── player/
│   ├── PlayerManager.kt     # 全局共享 ExoPlayer、请求头、类型推断
│   ├── PlaybackService.kt   # MediaSessionService（锁屏/通知）
│   ├── PlayerViewModel.kt   # 播放状态机、轨道选择、错误描述
│   └── SubtitleUtil.kt      # 外部字幕 MIME 推断
├── ui/
│   ├── Routes.kt            # 路由（player 参数 Base64 编码）
│   ├── home/HomeScreen.kt   # 首页 + 最近播放
│   ├── home/LocalVideosScreen.kt
│   ├── open/OpenVideoScreen.kt  # 打开 URL/分享解析
│   ├── settings/SettingsScreen.kt
│   ├── player/PlayerScreen.kt   # 播放器容器
│   ├── player/PlayerControls.kt # 上/中/下三栏控制
│   ├── player/Gestures.kt   # 点按/拖拽手势层
│   ├── player/TrackSheets.kt    # 倍速/画质/音轨/字幕/比例
│   └── theme/               # Material3 主题
└── util/
    ├── Ext.kt               # 沉浸式/分享解析/标题猜测
    └── Format.kt            # 时间/大小/相对时间/码率格式化
```

---

## 七个阶段（每个阶段可独立运行与测试）

### 阶段 1：本地播放（骨架）

文件：`MainActivity.kt`、`VideoPlayerApp.kt`、`data/AppServices.kt`、`data/LocalVideoRepository.kt`、
`ui/home/HomeScreen.kt`、`ui/home/LocalVideosScreen.kt`、`ui/Routes.kt`、`ui/theme/*`。

- 首页「本地视频」→ 读取 MediaStore，列出系统视频 → 点击播放。
- 首次运行请授予「所有文件访问」（系统设置里手动开）和通知权限。

测试：
1. 真机放入一个 mp4，回到首页点「本地视频」应看到它。
2. 点击它应能播放、出声、能旋转横屏。

### 阶段 2：倍速

文件：`player/PlayerViewModel.kt`（`setSpeed`）、`ui/player/PlayerControls.kt`（底部倍速按钮）、
`ui/player/TrackSheets.kt`（`SpeedSheet`）、`data/AppPreferences.kt`（记忆倍速）。

- 底部左侧 `1.0x` 点击弹「倍速」面板：9 档预设 + 自定义滑条 0.25x–3x。
- 设置里可开启「记住倍速」，每次开播自动应用。

测试：播放中切 2x，应明显加速；切回 1x 正常。勾选记住倍速后退出重进应自动带速。

### 阶段 3：手势

文件：`ui/player/Gestures.kt`、`ui/player/PlayerScreen.kt`（手势层接线）。

- 单击：显示/隐藏控制栏。
- 双击：播放/暂停。
- 左侧竖滑：亮度；右侧竖滑：音量；水平横滑：快进/快退（目标在屏幕中间弹出）。
- 双指捏合：视频缩放（1x–3x）。

测试：在控制栏隐藏状态下滑动验证三种拖拽与捏合；控制栏显示时按钮/滑条仍可正常操作。

### 阶段 4：画质 / 音轨 / 字幕 / 比例

文件：`player/PlayerViewModel.kt`（`refreshTracks`/`selectFormat`/`selectAuto`/`addExternalSubtitle`）、
`ui/player/TrackSheets.kt`（`TrackSheet`/`AspectSheet`）、`player/SubtitleUtil.kt`。

- 顶部「画质/音轨/字幕」按钮按需弹面板；多清晰度流可锁定清晰度或恢复「自动」。
- 字幕面板支持从 SAF 选择外部字幕文件（srt/ass/ssa/vtt 等，打开文件时会请求读写权限）。
- 底部「适应屏幕/原始比例/16:9/填充屏幕」切换；比例选择会记忆。

测试：播放一个带多码率的 m3u8，切到最低画质应能手动锁码；选择本地 srt 应显示字幕。

### 阶段 5：播放记录

文件：`data/PlaybackRepository.kt`、`data/model/Models.kt`、`ui/home/HomeScreen.kt`（最近播放）。

- 每 3 秒（播放中）静默落盘一次；暂停/结束/离开时再落一次。
- 首页「最近播放」按时间倒序展示，显示进度，点击续播。
- 设置里有「自动续播」开关。

测试：播放到一半退出，回首页看到记录，点击应从上次位置继续。

### 阶段 6：画中画 / 后台 / 锁屏控制

文件：`MainActivity.kt`（`onUserLeaveHint` 自动 PiP、`enterPictureInPictureMode`）、
`player/PlaybackService.kt`（MediaSessionService）、`AndroidManifest.xml`（前台服务 + PiP 特性）。

- 回到桌面或按 Home → 自动进入画中画（设置里可关）。
- 锁屏/通知栏出现媒体控制；可从媒体通知暂停/继续。
- 音量键/耳机插拔：自动暂停（`setHandleAudioBecomingNoisy`）。

测试：播放中按 Home，应弹出小窗并继续出声；锁屏后可用通知控制；拔耳机自动暂停。

### 阶段 7：网盘视频（研究结论 + 已实现能力）

#### 已实现（全部合法）

- **打开分享链接**：浏览器里用本 App 打开 `ACTION_VIEW` 的直链，或分享文本/文件给它，自动解析并播放。
- **手动输入 URL**：「打开视频」页直接粘贴直链 / m3u8 / mpd。
- **自定义请求头**：某些网盘直链要求 `Referer`/`User-Agent`/`Cookie`，可在打开页填写，播放请求会携带。
- **HLS / DASH / SS 流**：按扩展名（`.m3u8`/`.mpd`/`.ism*`）自动选择正确的 MediaSource，`C.CONTENT_TYPE_HLS` 由 `DefaultMediaSourceFactory` 自动识别。
- **多清晰度切换**：m3u8 多码率会在「画质」面板里列出并可锁定。
- **编码兼容**：`DefaultRenderersFactory.setEnableDecoderFallback(true)` + `EXTENSION_RENDERER_MODE_PREFER`，硬解失败自动软解回退。
- **大文件 / 分段**：ExoPlayer 默认基于 Range 请求 + 流式读取，不下载到本地。
- **后台/画中画**：网络流同样支持。

#### 不实现 / 说明（合规边界）

| 能力 | 结论 |
|---|---|
| 会员视频直链 | 需要会员凭证，直链带签名与时效，**属于付费墙保护**，本播放器不会伪造凭证获取，也不保证其有效期 |
| DRM（Widevine 等） | ExoPlayer 原生支持 Widevine，但许可证必须由官方服务签发。对 DRM 报错本应用只提示「该内容为 DRM 保护」，不做任何绕过 |
| 账号密码 / Cookie 窃取 | 不提供任何“登录网盘”或“获取 cookie”的功能；Cookie 需用户自行从网盘官方 App 生成并主动填入 |
| 网盘 API 对接（上传/下载/秒传） | 不在范围内；本播放器只做“播放器”，不做网盘客户端 |
| 转码 / 秒传 / 格式转换 | 不实现 |

#### 常见问题

- **直链失效（403 / 404）**：网盘直链通常只对“生成时的 IP + UA + 时间戳”有效，改 IP 或过期即失效。请在网盘 App 里重新生成再播放。
- **需要 Referer**：在「打开视频」页勾选/填写对应 Referer 与 UA，本 App 会在所有分片请求上携带（`setDefaultRequestProperties`）。
- **m3u8 加载失败**：部分网盘返回的 m3u8 里相对路径需要携带 Cookie，若你的 m3u8 依赖 Cookie 才能拉片，填入 Cookie 即可。
- **字幕时间轴错位**：外部字幕按文件自带时间轴播放；如有偏移需求可自行在 srt 里调整（播放器未内置偏移设置）。

---

## 运行与测试

前置条件：
1. Android Studio（最新稳定版，自带 JDK 17 与 Android SDK）。本项目没有提交 `gradle-wrapper.jar`（二进制无法手工生成），
   Android Studio 打开项目时会提示并自动补齐 wrapper；如需命令行构建，先执行一次 `gradle wrapper --gradle-version 8.9`。
2. 真机（画中画、MediaStore、前台服务都需要真机验证，模拟器部分功能不可用）。
3. targetSdk 35 + Android 14 上首次扫描本地视频，请在系统设置里给本应用开启「所有文件访问」；通知权限启动时会请求。

构建：
```
# 命令行（首次会下载依赖，较慢）
./gradlew :app:assembleDebug

# 安装到已连接设备
./gradlew :app:installDebug
```

自测清单：
- [ ] 本地 mp4 能播放/旋转/出声
- [ ] 倍速 0.5x–3x 正常，自定义倍速生效
- [ ] 三种拖拽手势与捏合缩放正常，控制栏隐藏时手势可用
- [ ] 多码率 m3u8 能锁画质 / 切音轨 / 外部 srt 字幕
- [ ] 播放到一半退出，首页出现记录并可续播
- [ ] 按 Home 进画中画并继续出声；锁屏通知可控制
- [ ] 用浏览器分享一个直链给本 App 能直接播放

---

## 许可与说明

- 所有代码仅作学习与自用。请遵守所在地区法律与网盘服务条款。
- 使用本播放器播放任何内容前，请确认你对相应内容拥有合法访问权限。
- 本项目不内置任何账号、Cookie、密钥，也不会访问任何网盘官方 API。
