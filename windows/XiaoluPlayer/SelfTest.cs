using LibVLCSharp.Shared;
using System.IO;

namespace XiaoluPlayer;

static class SelfTest
{
    static string logFile = Path.Combine(Path.GetTempPath(), "xiaolu_selftest.log");

    static void Log(string line)
    {
        try { File.AppendAllText(logFile, DateTime.Now.ToString("HH:mm:ss") + " " + line + Environment.NewLine); } catch { }
    }

    public static bool WantsWindowRun(string[] args)
    {
        bool st = false;
        for (int i = 0; i < args.Length; i++) if (args[i] == "--selftest") st = true;
        return st && Arg(args, "--mode") == "window";
    }

    // 窗口播放必须走真正的 Application 生命周期：同一个 AppDomain 里不能再 new Application()
    public static void StartWindowRun(System.Windows.Application app, string[] args)
    {
        logFile = Arg(args, "--log") ?? logFile;
        int secs = int.TryParse(Arg(args, "--secs"), out int s) ? s : 15;
        string path = Arg(args, "--path") ?? "";
        string cookies = Store.Config.cookies ?? "";
        PlayerWindow.RecordingEnabled = false;
        if (path.Length == 0 || cookies.Length == 0)
        {
            Log("RESULT: FAIL missing path/cookies");
            Environment.Exit(2);
            return;
        }
        PlayRequest req;
        try
        {
            var link = BaiduClient.GetStreamLink(cookies, path);
            var h = BaiduClient.StreamHeaders(cookies);
            req = new PlayRequest
            {
                Uri = link.Url,
                Title = "window selftest",
                Cookies = cookies,
                UserAgent = h["User-Agent"],
                Referer = h["Referer"],
                BaiduPath = path,
                Qualities = link.Qualities,
                StreamType = link.CurrentType,
            };
            Log("window probe chosen=" + BaiduClient.LabelOf(req.StreamType));
        }
        catch (Exception e)
        {
            Log("RESULT: FAIL probe " + e.Message);
            Environment.Exit(3);
            return;
        }
        var win = new PlayerWindow(req);
        int ticks = 0, capTicks = secs * 6 + 180;
        int phase = 0, phaseTicks = 0, stuck = 0, step = 0;
        long phaseBase = 0, lastTickTime = -1;
        System.Windows.Point center = new(0, 0);
        string fail = "";
        var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        void Next(int p, string what)
        {
            Log("window phase " + p + ": " + what);
            phase = p; phaseTicks = 0; step = 0; phaseBase = win.LastTimeMs;
        }
        timer.Tick += (s, e) =>
        {
            ticks++; phaseTicks++;
            long t = win.LastTimeMs;
            Log("window tick phase=" + phase + " started=" + win.PlaybackStarted + " time=" + t);
            bool active = (bool)win.Dispatcher.Invoke(() => win.IsActive);
            switch (phase)
            {
                case 0:
                    if (win.PlaybackStarted && t == lastTickTime) stuck++; else stuck = 0;
                    if (t >= secs * 1000L)
                    {
                        Next(1, "空格暂停");
                        InjectKey(VK_SPACE);
                    }
                    else if (stuck >= 30) { fail = "播放中途卡死 time=" + t; phase = 99; }
                    break;
                case 1:
                    // 按完空格后位置应停住；残留 <500ms 视为已暂停
                    if (phaseTicks < 6) break;
                    if (t - phaseBase <= 500) { Next(2, "空格恢复"); InjectKey(VK_SPACE); }
                    else if (phaseTicks >= 12) { fail = "空格无法暂停 active=" + active; phase = 99; }
                    break;
                case 2:
                    if (phaseTicks < 6) break;
                    if (t - phaseBase >= 1000)
                    {
                        center = win.Dispatcher.Invoke(() => win.CenterOnScreen());
                        SetCursorPos((int)center.X, (int)center.Y);
                        Next(3, "等控制条自动隐藏");
                    }
                    else if (phaseTicks >= 12) { fail = "空格无法恢复播放 active=" + active; phase = 99; }
                    break;
                case 3:
                    if (!win.BarsShown) { Next(4, "鼠标滑动显示控制条"); }
                    else if (phaseTicks >= 14) { fail = "控制条不会自动隐藏 active=" + active; phase = 99; }
                    break;
                case 4:
                    if (step == 0 && phaseTicks >= 2)
                    {
                        step = 1;
                        // InputTick 要 IsActive 才认光标移动，这里再激活一次
                        win.Dispatcher.Invoke(() => win.Activate());
                        SetCursorPos((int)center.X + 40, (int)center.Y + 20);
                    }
                    if (win.BarsShown) Next(5, "完成");
                    else if (phaseTicks >= 10) { fail = "鼠标滑动不显示控制条 active=" + active; phase = 99; }
                    break;
                case 5: phase = 99; break;
                default:
                    timer.Stop();
                    Log(fail.Length == 0 ? "RESULT: SUCCESS window playback+输入验证 played=" + t + "ms"
                                         : "RESULT: FAIL " + fail + " time=" + t);
                    app.Shutdown(fail.Length == 0 ? 0 : 4);
                    return;
            }
            lastTickTime = t;
            if (ticks >= capTicks)
            {
                timer.Stop();
                Log("RESULT: FAIL window timeout phase=" + phase + " started=" + win.PlaybackStarted + " time=" + t);
                app.Shutdown(4);
            }
        };
        timer.Start();
        win.Show();
    }

    const int VK_SPACE = 0x20;
    const uint KEYEVENTF_KEYUP = 2;

    static void InjectKey(int vk)
    {
        try
        {
            keybd_event((byte)vk, 0, 0, UIntPtr.Zero);
            keybd_event((byte)vk, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        }
        catch (Exception e) { Log("inject key failed: " + e.Message); }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern bool SetCursorPos(int x, int y);

    public static int Run(string[] args)
    {
        logFile = Arg(args, "--log") ?? logFile;
        string mode = Arg(args, "--mode") ?? "baidu";
        int secs = int.TryParse(Arg(args, "--secs"), out int s) ? s : 15;
        try
        {
            if (mode == "sort")
            {
                // 网盘目录靠 NaturalCompare 排序：章节名里的数字要按数值比，否则 "10第十节" 会排在 "2第二节" 前面
                var got = new List<string>
                {
                    "10第十节", "2第二节", "1第一节", "a.mkv", "B.mkv", "影像01.mp4", "第2-9节", "第2-10节"
                };
                got.Sort(BaiduClient.NaturalCompare);
                string want = "1第一节 | 2第二节 | 10第十节 | a.mkv | B.mkv | 影像01.mp4 | 第2-9节 | 第2-10节";
                string actual = string.Join(" | ", got);
                Log("sorted: " + actual);
                if (actual == want)
                {
                    Log("RESULT: SUCCESS mode=sort");
                    return 0;
                }
                Log("RESULT: FAIL mode=sort want: " + want);
                return 3;
            }
            if (mode == "list")
            {
                Store.Init();
                string cookiesFile = Arg(args, "--cookies-file") ?? "";
                string cookies = cookiesFile.Length > 0 && File.Exists(cookiesFile)
                    ? File.ReadAllText(cookiesFile).Trim()
                    : Store.Config.cookies;
                string dir = Arg(args, "--dir") ?? "/";
                if (cookies.Length == 0)
                {
                    Log("RESULT: FAIL no cookies");
                    return 2;
                }
                try
                {
                    var list = BaiduClient.List(cookies, dir);
                    Log("RESULT: SUCCESS mode=list count=" + list.Count);
                    return 0;
                }
                catch (BaiduApiException e)
                {
                    Log("RESULT: FAIL list errno=" + e.Errno);
                    return 3;
                }
                catch (Exception e)
                {
                    Log("RESULT: FAIL list exception=" + e.GetType().Name);
                    return 5;
                }
            }
            if (mode == "scan")
            {
                Store.Init();
                string cookiesFile = Arg(args, "--cookies-file") ?? "";
                string cookies = cookiesFile.Length > 0 && File.Exists(cookiesFile)
                    ? File.ReadAllText(cookiesFile).Trim()
                    : Store.Config.cookies;
                string dir = Arg(args, "--dir") ?? "/";
                int maxDirs = int.TryParse(Arg(args, "--max-dirs"), out int md) ? md : 200;
                int maxFiles = int.TryParse(Arg(args, "--max-files"), out int mf) ? mf : 20000;
                if (cookies.Length == 0)
                {
                    Log("RESULT: FAIL no cookies");
                    return 2;
                }
                var stats = new ScanStats();
                try
                {
                    bool capped = ScanBaidu(cookies, dir, stats, new HashSet<string>(), 0, maxDirs, maxFiles);
                    Log($"RESULT: SUCCESS mode=scan rootItems={stats.rootItems} dirs={stats.dirs} files={stats.files} videos={stats.videos} capped={capped}");
                    return 0;
                }
                catch (BaiduApiException e)
                {
                    Log("RESULT: FAIL scan errno=" + e.Errno);
                    return 3;
                }
                catch (Exception e)
                {
                    Log("RESULT: FAIL scan exception=" + e.GetType().Name);
                    return 5;
                }
            }
            if (mode == "allvideos")
            {
                Store.Init();
                string cookiesFile = Arg(args, "--cookies-file") ?? "";
                string cookies = cookiesFile.Length > 0 && File.Exists(cookiesFile)
                    ? File.ReadAllText(cookiesFile).Trim()
                    : Store.Config.cookies;
                string dir = Arg(args, "--dir") ?? "/";
                int maxScans = int.TryParse(Arg(args, "--max-scans"), out int ms) ? ms : 500;
                int maxVideos = int.TryParse(Arg(args, "--max-videos"), out int mv) ? mv : 2000;
                int maxFiles = int.TryParse(Arg(args, "--max-files"), out int mf) ? mf : 50000;
                if (cookies.Length == 0)
                {
                    Log("RESULT: FAIL no cookies");
                    return 2;
                }
                try
                {
                    var res = BaiduClient.ListAllVideos(cookies, dir, maxScans, maxVideos, maxFiles, null);
                    Log($"RESULT: SUCCESS mode=allvideos videos={res.Videos.Count} scans={res.Scans} dirs={res.DirsFound} files={res.FilesSeen} capped={res.Capped}");
                    return 0;
                }
                catch (BaiduApiException e)
                {
                    Log("RESULT: FAIL allvideos errno=" + e.Errno);
                    return 3;
                }
                catch (Exception e)
                {
                    Log("RESULT: FAIL allvideos exception=" + e.GetType().Name);
                    return 5;
                }
            }
            string url = "";
            var opts = new List<string> { ":network-caching=2500", ":http-verbose" };
            if (mode == "baidu")
            {
                Store.Init();
                string path = Arg(args, "--path") ?? "";
                string ckFile = Arg(args, "--cookies-file") ?? "";
                string cookies = ckFile.Length > 0 && File.Exists(ckFile)
                    ? File.ReadAllText(ckFile).Trim()
                    : (Store.Config.cookies ?? "");
                if (path.Length == 0 || cookies.Length == 0)
                {
                    Log("RESULT: FAIL missing path/cookies");
                    return 2;
                }
                string local = "";
                try
                {
                    var link = BaiduClient.GetStreamLink(cookies, path);
                    if (link.CurrentType.Length == 0)
                    {
                        Log("probe ok via dlink fallback");
                        url = link.Url;
                        opts.Clear();
                        opts.Add(":network-caching=4000");
                        opts.Add(":http-user-agent=" + BaiduClient.StreamHeaders(cookies)["User-Agent"]);
                        opts.Add(":http-referrer=" + BaiduClient.StreamHeaders(cookies)["Referer"]);
                        opts.Add(":http-cookies=" + cookies);
                    }
                    else
                    {
                        Log("probe ok chosen=" + BaiduClient.LabelOf(link.CurrentType) +
                            " avail=" + string.Join(",", link.Qualities.ConvertAll(q => q.Label)));
                        var h = BaiduClient.StreamHeaders(cookies);
                        var pl = BaiduClient.FetchPlaylistDowngrade(cookies, path, link.CurrentType, link.Qualities);
                        Log("playlist type=" + pl.type + " segs=" + pl.segs.Count + " dur0=" + pl.segs[0].Dur);
                        local = HlsProxy.Serve(pl.segs, cookies, h["User-Agent"], h["Referer"]);
                        Log("proxy=" + local);
                        url = local;
                        opts.Clear();
                        opts.Add(":network-caching=2500");
                    }
                }
                catch (BaiduApiException e)
                {
                    Log("RESULT: FAIL probe errno=" + e.Errno + " " + e.Message);
                    return 3;
                }
            }
            else
            {
                url = Arg(args, "--url") ?? "";
                if (url.Length == 0) { Log("RESULT: FAIL missing url"); return 2; }
                if (Arg(args, "--ua") is string ua && ua.Length > 0) opts.Add(":http-user-agent=" + ua);
                if (Arg(args, "--referer") is string rf && rf.Length > 0) opts.Add(":http-referrer=" + rf);
                if (Arg(args, "--cookies") is string ck && ck.Length > 0) opts.Add(":http-cookies=" + ck);
            }
            Log("url=" + (url.Length > 150 ? url.Substring(0, 150) : url));
            Core.Initialize();
            using var lib = new LibVLC("--no-video", "--aout=dummy", "--intf=dummy", "--no-stats", "--no-osd");
            lib.Log += (sender, e) =>
            {
                string m = (e.Message ?? "").Trim();
                if (m.Length == 0) return;
                if (m.IndexOf("http", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("cookie", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("access", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("option", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("hls", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("playlist", StringComparison.OrdinalIgnoreCase) < 0 &&
                    e.Level != LibVLCSharp.Shared.LogLevel.Error && e.Level != LibVLCSharp.Shared.LogLevel.Warning) return;
                Log("vlc[" + e.Level + "] " + (m.Length > 400 ? m.Substring(0, 400) : m));
            };
            using var media = new Media(lib, url, FromType.FromLocation, opts.ToArray());
            using var mp = new MediaPlayer(media);
            mp.Play();
            long maxTime = 0;
            bool played = false;
            for (int i = 0; i < secs * 2; i++)
            {
                Thread.Sleep(500);
                try
                {
                    long t = mp.Time;
                    if (t > maxTime) maxTime = t;
                    if (mp.State == VLCState.Playing) played = true;
                    Log("tick state=" + mp.State + " time=" + t + " len=" + mp.Length);
                    if (played && maxTime > 2000 && i > 6) break;
                }
                catch (Exception e) { Log("tick err=" + e.Message); }
            }
            mp.Stop();
            if (played && maxTime > 2000)
            {
                Log("RESULT: SUCCESS maxTime=" + maxTime);
                return 0;
            }
            Log("RESULT: FAIL played=" + played + " maxTime=" + maxTime);
            return 4;
        }
        catch (Exception e)
        {
            Log("RESULT: FAIL exception " + e.GetType().Name + ": " + e.Message);
            return 5;
        }
    }

    class ScanStats
    {
        public int rootItems;
        public int dirs;
        public int files;
        public int videos;
    }

    static bool ScanBaidu(string cookies, string dir, ScanStats stats, HashSet<string> visited, int depth, int maxDirs, int maxFiles)
    {
        if (depth > 12) return true;
        bool capped = false;
        var list = BaiduClient.List(cookies, dir);
        if (dir == "/") stats.rootItems = list.Count;
        foreach (var f in list)
        {
            if (f.IsDir)
            {
                stats.dirs++;
                if (stats.dirs >= maxDirs || visited.Contains(f.Path))
                {
                    capped = true;
                    continue;
                }
                visited.Add(f.Path);
                if (ScanBaidu(cookies, f.Path, stats, visited, depth + 1, maxDirs, maxFiles))
                {
                    capped = true;
                    if (stats.dirs >= maxDirs || stats.files >= maxFiles) break;
                }
            }
            else
            {
                stats.files++;
                if (BaiduClient.IsVideoName(f.Name) || f.Category == 1) stats.videos++;
                if (stats.files >= maxFiles) return true;
            }
        }
        return capped;
    }

    static string CurlCheck(string url, string cookies)
    {
        try
        {
            var (code, output) = Curl.Get(url, cookies,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36", 25);
            if (code != 0) return "curl exit=" + code;
            string head = output.Length > 160 ? output.Substring(0, 160) : output;
            return "len=" + output.Length + " head=" + head.Replace("\n", "\\n").Replace("\r", "");
        }
        catch (Exception e) { return "err=" + e.Message; }
    }

    static string? Arg(string[] args, string name)
    {
        for (int i = 0; i + 1 < args.Length; i++)
            if (args[i] == name) return args[i + 1];
        return null;
    }
}
