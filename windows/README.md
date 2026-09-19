# 小鹿播放增强器 · Windows 版

WPF（.NET 8）+ LibVLCSharp（VLC 3）桌面播放器，与 Android 版同名同功能。

## 功能

- 本地视频播放（文件选择框）
- 网络 URL（mp4 / m3u8 / mpd），可自定义 Referer / User-Agent / Cookie
- 百度网盘在线播放：应用内嵌百度官方登录页（WebView2）一键登录、自动保存会话（手动粘贴 Cookie 备用）→ 目录浏览 / 全部视频扫描 → 自动探测最高可用画质（1080p/720p/480p）→ 播放中可切换清晰度且保留进度
- 网盘列目录支持 100 条一页的分页续取；顶部可切换“当前目录”和“全部视频”。“全部视频”会递归扫描子目录并集中显示视频，避免根目录只有少量文件夹时看起来像“只加载了两条”。
- 异常响应会写入本机诊断日志，便于排查“只显示少量条目”等问题
- 0.25x–3x 倍速（可记忆）、±10 秒、进度条、音量
- 音轨 / 字幕切换 + 外挂字幕文件（srt/ass/ssa/vtt）
- 画面比例（自动/16:9/4:3/铺满/原始）、全屏（F/双击）、窗口置顶
- 播放记录 + 断点续播（%APPDATA%\XiaoluPlayer）
- 快捷键：空格暂停/播放，←/→ 快退/快进 10s，↑/↓ 音量，F 全屏，Esc 退出全屏
- 鼠标滑动即显示顶部/底部控制条（含进度条），3 秒无操作自动隐藏；因视频原生窗口可能吞掉
  WPF 鼠标消息，播放器另用光标轮询兜底同样的显示与单击暂停行为

## 架构要点

百度返回的 m3u8 在部分网络栈（VLC/PS）会被 TLS 指纹拦截，因此网盘播放走
应用内本地 HLS 代理：`HlsProxy` 用系统 curl.exe（已验证可达）拉取播放列表
与分片，VLC 只连接 `http://127.0.0.1:17890/`，彻底绕开指纹问题。
分片**边下边转发**：代理把 curl 的输出直接喂给 VLC，同时用同一次下载顺手把整段写到临时目录
（`%TEMP%\xlhls_*`），播放器回头要同一段时直接从磁盘秒回，看完的段会被清掉。
注意：不能"先把整段下完再回给播放器"——非会员一段几百 KB 到几十秒不等，
那样 VLC 缓冲会被饿死，表现就是一直加载中/无响应。

预取从"收到第 i 段请求"那一刻就发起，并且**提前两段**：只提前一段永远慢一拍，因为命中缓存时
交付是瞬间的，播放器紧接着就要下下段（实测会命中/未命中交替出现）。另外开播前先等前 3 段落盘
（`HlsProxy.Warmup`，最多等 3 秒）再让 VLC 起播，否则第一帧就要等网络，起步那几秒必然掉帧。
实测 41 秒连续播放：稳态 0 次丢帧（`buffer too late` 只剩起播 1 秒内的 3 次），11 段里 9 段命中缓存。

取播放地址时按 1080p→720p→480p 逐档探测，**命中一档即止**（每档都要向百度发一次
`/api/streaming` 探测，串行探完三档在风控时会明显变慢）。**但探测通过不代表能播**：非会员的
1080p 会给 adToken 却把 m3u8 拒成 `error_code 31062`，停在探测结果上就会永远"加载中"，
所以取播放列表时按档位从高往低逐档试到能用为止（`FetchPlaylistDowngrade`，日志 `playlist downgraded to ...`）。
三档都失败时改走直链兜底链：
`api/download` → `pcs 302` → `api/filemetas` → `api/playerinfo`，逐个用 Range 请求校验
地址可用后交给 VLC 直接播放。探测失败的结果按「路径+档位」缓存 3 分钟，避免连点画质
反复打百度接口。全过程写入 `diag.log`（`streaming probe ...`、`dlink ok via ...`、
`play playlist fetched in ...ms`）。libvlc 自身的 error/warning 也接进同一份日志
（`vlc[Warning] ...`，Cookie 值会被截断），这样"取流失败"和"解码/输出失败"能分开判断。

## 构建

需要 .NET 8 SDK（首次构建会自动从 NuGet 还原 LibVLCSharp 与 WebView2 包）：

```
cd windows\XiaoluPlayer
dotnet build -c Release
```

运行：`bin\Release\net8.0-windows\XiaoluPlayer.exe`

> 应用内登录依赖 WebView2 运行组件（Win10 / Win11 一般自带）。
> 若某台机器缺失该组件，登录窗口会提示，改用网盘窗口中的“手动粘贴 Cookie”备用方式登录即可。

自测模式（无窗口验证探针 + 代理 + 播放 / 目录 / 全部视频扫描）：

```
XiaoluPlayer.exe --selftest --mode baidu --path "<网盘路径>" \
  --cookies-file "<cookie文件>" --log selftest.log --secs 20

XiaoluPlayer.exe --selftest --mode list --dir / --log selftest.log

XiaoluPlayer.exe --selftest --mode allvideos --max-scans 500 \
  --max-videos 2000 --max-files 50000 --log selftest.log

XiaoluPlayer.exe --selftest --mode sort --log selftest.log

XiaoluPlayer.exe --selftest --mode window --path "<网盘路径>" --secs 10 --log selftest.log
```

成功标志：日志出现 `RESULT: SUCCESS`。`allvideos` 模式只输出扫描数量，不输出文件路径和 Cookie 值。
`--cookies-file` 可省略，省略时 `baidu` 模式直接使用应用已保存的登录会话；自测模式只读配置，
不会回写 `%APPDATA%\XiaoluPlayer\config.json`。

`window` 模式会真的打开播放窗口，然后按阶段验证用户报过的那几件事：连续播放不卡死 →
注入一次真实空格确认位置停住 → 再注入一次确认真的恢复 → 等控制条自动隐藏 → 用 `SetCursorPos`
真实移动光标确认控制条出现。这里 `--secs` 是"先连续播多少秒"，之后才进入按键/鼠标阶段，
整跑约 `secs + 15` 秒。`sort` 模式纯离线，只检查网盘目录名的自然排序，不读 Cookie 也不发请求。

> 在 git-bash 里跑要加 `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`，否则
> `--path /我的资源/...` 会被改写成 `C:/Program Files/Git/我的资源/...`，看着像接口挂了其实是路径错了。
> 本仓库自带 SDK：在 `windows\XiaoluPlayer` 下执行 `..\..\.dotnet\dotnet build -c Release`（PATH 里的 dotnet 可能没有 SDK）。

## 排查：登录成功但网盘只显示很少条目

1. 打开网盘窗口后，底部会显示 `共 N 项 · 会话Cookie: ... · 日志: ...`。
2. 如果你的根目录里只有文件夹，百度官方接口只会返回这些顶层条目。点顶部“全部视频”，软件会递归扫描子目录并集中显示所有视频；底部状态会显示扫描进度、目录数和视频数。
3. 如果 `会话Cookie` 缺少 `BDUSS`、`BAIDUID` 等，说明应用内登录会话没有抓全；请退出登录后重新用“应用内登录”，等待状态变为“网盘会话已同步”。
4. 如果 Cookie 看起来完整且“全部视频”也异常，请把以下日志内容发给开发者（日志不记录 Cookie 值，只记录 Cookie 名称和接口响应）：

```
%LOCALAPPDATA%\XiaoluPlayer\diag.log
```

重点看 `list raw body`、`list ok dir=... count=...`、`list errno=...` 这几行。

## 合规声明

仅用于播放你已合法获得访问权限的视频，不含也不应被用于绕过任何鉴权。
Cookie 仅保存在本机 %APPDATA%，不会上传到任何地方。
