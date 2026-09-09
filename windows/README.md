# 小鹿播放增强器 · Windows 版

WPF（.NET 8）+ LibVLCSharp（VLC 3）桌面播放器，与 Android 版同名同功能。

## 功能

- 本地视频播放（文件选择框）
- 网络 URL（mp4 / m3u8 / mpd），可自定义 Referer / User-Agent / Cookie
- 百度网盘在线播放：Cookie 粘贴登录 → 目录浏览 → 自动探测最高可用画质（1080p/720p/480p）→ 播放中可切换清晰度且保留进度
- 0.25x–3x 倍速（可记忆）、±10 秒、进度条、音量
- 音轨 / 字幕切换 + 外挂字幕文件（srt/ass/ssa/vtt）
- 画面比例（自动/16:9/4:3/铺满/原始）、全屏（F/双击）、窗口置顶
- 播放记录 + 断点续播（%APPDATA%\XiaoluPlayer）
- 快捷键：空格暂停/播放，←/→ 快退/快进 10s，↑/↓ 音量，F 全屏，Esc 退出全屏

## 架构要点

百度返回的 m3u8 在部分网络栈（VLC/PS）会被 TLS 指纹拦截，因此网盘播放走
应用内本地 HLS 代理：`HlsProxy` 用系统 curl.exe（已验证可达）拉取播放列表
与分片，VLC 只连接 `http://127.0.0.1:17890/`，彻底绕开指纹问题。

## 构建

需要 .NET 8 SDK：

```
cd windows\XiaoluPlayer
dotnet build -c Release
```

运行：`bin\Release\net8.0-windows\XiaoluPlayer.exe`

自测模式（无窗口验证探针 + 代理 + 播放）：

```
XiaoluPlayer.exe --selftest --mode baidu --path "<网盘路径>" \
  --cookies-file "<cookie文件>" --log selftest.log --secs 20
```

成功标志：日志出现 `RESULT: SUCCESS`。

## 合规声明

仅用于播放你已合法获得访问权限的视频，不含也不应被用于绕过任何鉴权。
Cookie 仅保存在本机 %APPDATA%，不会上传到任何地方。
