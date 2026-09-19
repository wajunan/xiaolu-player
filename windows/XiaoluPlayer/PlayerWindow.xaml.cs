using LibVLCSharp.Shared;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;

namespace XiaoluPlayer;

public partial class PlayerWindow : Window
{
    readonly PlayRequest req;
    MediaPlayer? mp;
    readonly DispatcherTimer uiTimer = new() { Interval = TimeSpan.FromMilliseconds(300) };
    readonly DispatcherTimer hideTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    readonly DispatcherTimer saveTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    readonly DispatcherTimer inputTimer = new() { Interval = TimeSpan.FromMilliseconds(80) };
    Point lastPointer = new(double.NaN, double.NaN);
    bool lastButtonDown;
    int pendingPressAt;
    int wpfClickAt = -100000;
    bool dragging;
    bool started;
    bool refreshed;
    bool fullscreen;
    bool useProxy;
    bool userWantsPlay = true;
    string proxySession = "";
    int playAttempt;
    long lastSavedPos;
    long lastTimeMs;

    // 供无界面自测判断窗口是否真的在播
    public bool PlaybackStarted => started;
    public long LastTimeMs => lastTimeMs;
    public bool BarsShown => TopBar.Visibility == Visibility.Visible;
    public System.Windows.Point CenterOnScreen() => PointToScreen(new Point(ActualWidth / 2, ActualHeight / 2));

    /// <summary>自测窗口不该往播放记录里写数据。</summary>
    public static bool RecordingEnabled = true;

    public PlayerWindow(PlayRequest request)
    {
        req = request;
        InitializeComponent();
        TitleText.Text = req.Title;
        Title = req.Title + " - 小鹿播放增强器";
        foreach (var s in new[] { "0.25x", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x" })
            SpeedBox.Items.Add(s);
        SpeedBox.SelectedItem = PlayerState.Speed.ToString("0.##") + "x";
        if (SpeedBox.SelectedIndex < 0) SpeedBox.SelectedIndex = 3;
        foreach (var a in new[] { "自动", "16:9", "4:3", "铺满", "原始" })
            AspectBox.Items.Add(a);
        AspectBox.SelectedIndex = 0;
        AudioBox.Items.Add("音轨");
        SubtitleBox.Items.Add("字幕");
        AudioBox.SelectedIndex = 0;
        SubtitleBox.SelectedIndex = 0;
        if (req.Qualities.Count > 0)
        {
            QualityBox.Visibility = Visibility.Visible;
            foreach (var q in req.Qualities) QualityBox.Items.Add(q.Label);
            int idx = Math.Max(0, req.Qualities.FindIndex(q => q.Type == req.StreamType));
            QualityBox.SelectedIndex = idx;
        }
        uiTimer.Tick += UiTick;
        hideTimer.Tick += (s, e) => { hideTimer.Stop(); HideBars(); };
        saveTimer.Tick += (s, e) => SaveRecord();
        Closed += (s, e) => Cleanup();
        Loaded += (s, e) => StartPlayback();
    }

    void StartPlayback()
    {
        try
        {
            var lib = App.LibVLC ?? throw new Exception("播放器未初始化");
            mp = new MediaPlayer(lib);
            // libvlc 的事件在它自己的线程上触发，这里必须用 BeginInvoke：Invoke 会阻塞该线程，
            // 而 UI 线程可能正卡在 mp.Time/mp.Length 上，互相等待就变成"加载中"且界面失去响应。
            mp.Playing += (s, e) => Dispatcher.BeginInvoke(() =>
            {
                userWantsPlay = true;
                PlayBtn.Content = "⏸";
                LoadingText.Visibility = Visibility.Collapsed;
                if (!started)
                {
                    started = true;
                    Diag.Log("playing len=" + mp!.Length + " startMs=" + req.StartMs);
                    if (req.StartMs > 0) { try { mp.Time = req.StartMs; } catch { } }
                    RefreshTracks();
                }
                ResetHideTimer();
            });
            mp.Paused += (s, e) => Dispatcher.BeginInvoke(() => { userWantsPlay = false; PlayBtn.Content = "▶"; ShowBars(); });
            mp.EndReached += (s, e) => Dispatcher.BeginInvoke(() => { userWantsPlay = false; PlayBtn.Content = "▶"; ShowBars(); SaveRecord(); });
            mp.EncounteredError += (s, e) => Dispatcher.BeginInvoke(OnError);
            VideoView.MediaPlayer = mp;
            PlayMedia(req.Uri);
            uiTimer.Start();
            saveTimer.Start();
            inputTimer.Tick += InputTick;
            inputTimer.Start();
            InstallKeyboardHook();
        }
        catch (Exception ex) { ShowError("启动播放失败：" + ex.Message); }
    }

    List<string> MediaOptions()
    {
        var opts = new List<string> { ":network-caching=" + (useProxy ? "6000" : "4000") };
        if (useProxy) return opts;
        if (req.UserAgent.Length > 0) opts.Add(":http-user-agent=" + req.UserAgent);
        if (req.Referer.Length > 0) opts.Add(":http-referrer=" + req.Referer);
        if (req.Cookies.Length > 0) opts.Add(":http-cookies=" + req.Cookies);
        return opts;
    }

    void PlayMedia(string url)
    {
        if (mp == null || App.LibVLC == null) return;
        started = false;
        userWantsPlay = true;
        LoadingText.Visibility = Visibility.Visible;
        if (req.BaiduPath.Length > 0 && url.Contains("type=M3U8_AUTO_"))
        {
            useProxy = true;
            PlayViaProxy();
            return;
        }
        useProxy = false;
        var media = new Media(App.LibVLC, url, FromType.FromLocation, MediaOptions().ToArray());
        mp.Play(media);
        media.Dispose();
    }

    void PlayViaProxy()
    {
        if (mp == null || App.LibVLC == null) return;
        if (req.Cookies.Length == 0 && Store.Config.cookies.Length > 0)
            req.Cookies = Store.Config.cookies;
        if (req.Cookies.Length == 0) { ShowError("需要先登录百度网盘"); return; }
        LoadingText.Visibility = Visibility.Visible;
        int attempt = ++playAttempt;
        Task.Run(() =>
        {
            long t0 = Environment.TickCount64;
            try
            {
                Diag.Log("play start path=" + req.BaiduPath + " type=" + req.StreamType);
                var pl = BaiduClient.FetchPlaylistDowngrade(req.Cookies, req.BaiduPath, req.StreamType, req.Qualities);
                var h = BaiduClient.StreamHeaders(req.Cookies);
                Diag.Log("play playlist fetched in " + (Environment.TickCount64 - t0) + "ms segs=" + pl.segs.Count);
                // 连点画质时旧任务不能把新任务刚建的会话拆掉
                if (attempt != Volatile.Read(ref playAttempt)) { Diag.Log("play attempt " + attempt + " superseded"); return; }
                if (proxySession.Length > 0) HlsProxy.Release(proxySession);
                string local = HlsProxy.Serve(pl.segs, req.Cookies, h["User-Agent"], h["Referer"]);
                proxySession = local.Substring(local.IndexOf("session=") + 8);
                long tw = Environment.TickCount64;
                HlsProxy.Warmup(proxySession, 3);
                Diag.Log("play warmup in " + (Environment.TickCount64 - tw) + "ms");
                Dispatcher.BeginInvoke(() =>
                {
                    try
                    {
                        if (attempt != playAttempt) return;
                        if (pl.type != req.StreamType)
                        {
                            req.StreamType = pl.type;
                            int idx = req.Qualities.FindIndex(q => q.Type == pl.type);
                            if (idx >= 0) QualityBox.SelectedIndex = idx;
                        }
                        var media = new Media(App.LibVLC!, local, FromType.FromLocation, MediaOptions().ToArray());
                        mp!.Play(media);
                        media.Dispose();
                        userWantsPlay = true;
                        ErrorText.Visibility = Visibility.Collapsed;
                    }
                    catch (Exception ex) { ShowError("播放失败：" + ex.Message); }
                });
            }
            catch (Exception ex)
            {
                Diag.Log("play FAIL after " + (Environment.TickCount64 - t0) + "ms: " + ex.Message);
                Dispatcher.Invoke(() => ShowError("获取播放地址失败：" + ex.Message));
            }
        });
    }

    void OnError()
    {
        if (!refreshed && req.BaiduPath.Length > 0 && Store.Config.cookies.Length > 0)
        {
            refreshed = true;
            long pos = 0;
            try { pos = mp?.Time ?? 0; } catch { }
            Diag.Log("player error, retry once at " + pos + "ms path=" + req.BaiduPath);
            req.StartMs = pos;
            started = false;
            ErrorText.Visibility = Visibility.Collapsed;
            PlayViaProxy();
            return;
        }
        ShowError("播放失败：无法打开该视频");
    }

    void ShowError(string msg)
    {
        ErrorText.Text = msg;
        ErrorText.Visibility = Visibility.Visible;
        LoadingText.Visibility = Visibility.Collapsed;
        userWantsPlay = false;
        PlayBtn.Content = "▶";
        ShowBars();
    }

    void UiTick(object? sender, EventArgs e)
    {
        if (mp == null) return;
        try
        {
            long len = mp.Length, t = mp.Time;
            lastTimeMs = t;
            if (!dragging && len > 0) SeekSlider.Value = t * 1000.0 / len;
            TimeText.Text = MainWindow.FormatTime(t / 1000) + " / " + MainWindow.FormatTime(len / 1000);
        }
        catch { }
    }

    void RefreshTracks()
    {
        if (mp == null) return;
        try
        {
            var curA = AudioBox.SelectedItem;
            AudioBox.Items.Clear();
            int ai = 0;
            foreach (var tr in mp.AudioTrackDescription)
            {
                string label = "音轨 " + tr.Id + (string.IsNullOrEmpty(tr.Name) ? "" : " " + tr.Name);
                AudioBox.Items.Add(new TrackItem { Id = tr.Id, Label = label });
                if (mp.AudioTrack == tr.Id) ai = AudioBox.Items.Count - 1;
            }
            if (AudioBox.Items.Count == 0) AudioBox.Items.Add("音轨");
            AudioBox.SelectedIndex = ai;

            SubtitleBox.Items.Clear();
            SubtitleBox.Items.Add(new TrackItem { Id = -1, Label = "关闭" });
            int si = 0;
            foreach (var tr in mp.SpuDescription)
            {
                string label = "字幕 " + tr.Id + (string.IsNullOrEmpty(tr.Name) ? "" : " " + tr.Name);
                SubtitleBox.Items.Add(new TrackItem { Id = tr.Id, Label = label });
                if (mp.Spu == tr.Id) si = SubtitleBox.Items.Count - 1;
            }
            SubtitleBox.SelectedIndex = si;
        }
        catch { }
    }

    void ApplySpeed()
    {
        if (mp == null) return;
        try { mp.SetRate(PlayerState.Speed); } catch { }
    }

    void ResetHideTimer()
    {
        hideTimer.Stop();
        if (mp != null && userWantsPlay) { hideTimer.Start(); return; }
        ShowBars();
    }

    void HideBars()
    {
        TopBar.Visibility = Visibility.Collapsed;
        BottomBar.Visibility = Visibility.Collapsed;
    }

    void ShowBars()
    {
        TopBar.Visibility = Visibility.Visible;
        BottomBar.Visibility = Visibility.Visible;
    }

    void Overlay_MouseMove(object sender, MouseEventArgs e)
    {
        ShowBars();
        ResetHideTimer();
    }

    void Video_Click(object sender, MouseButtonEventArgs e)
    {
        wpfClickAt = Environment.TickCount;
        Overlay.Focus();
        if (e.ClickCount >= 2) { ToggleFullscreen(); return; }
        TogglePlayPause();
    }

    // 视频原生窗口可能吞掉鼠标消息，轮询光标位置兜底"滑动显示进度条"
    void InputTick(object sender, EventArgs e)
    {
        if (!IsActive) return;
        if (!GetCursorPos(out var p)) return;
        Point local;
        try { local = PointFromScreen(new Point(p.x, p.y)); } catch { return; }
        if (local.X < 0 || local.Y < 0 || local.X > ActualWidth || local.Y > ActualHeight) return;
        bool moved = double.IsNaN(lastPointer.X) ||
            Math.Abs(local.X - lastPointer.X) > 1.5 || Math.Abs(local.Y - lastPointer.Y) > 1.5;
        if (moved)
        {
            lastPointer = local;
            ShowBars();
            ResetHideTimer();
        }

        bool down = (GetAsyncKeyState(VK_LBUTTON) & 0x8000) != 0;
        if (down && !lastButtonDown && pendingPressAt == 0 &&
            local.Y > TopBar.ActualHeight && local.Y < ActualHeight - BottomBar.ActualHeight)
            pendingPressAt = Environment.TickCount;
        lastButtonDown = down;
        if (pendingPressAt == 0) return;
        if (Environment.TickCount - pendingPressAt < 160) return;
        // WPF 自己收到过这次点击就不要重复触发
        if (wpfClickAt < pendingPressAt) TogglePlayPause();
        wpfClickAt = Environment.TickCount;
        pendingPressAt = 0;
    }

    const int VK_LBUTTON = 0x01;

    struct POINT { public int x; public int y; }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern bool GetCursorPos(out POINT p);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern short GetAsyncKeyState(int vKey);

    void PlayPause_Click(object sender, RoutedEventArgs e) => TogglePlayPause();

    void Back_Click(object sender, RoutedEventArgs e) => Close();

    void Back10_Click(object sender, RoutedEventArgs e)
    {
        if (mp == null) return;
        try { mp.Time = Math.Max(0, mp.Time - 10000); } catch { }
    }

    void Fwd10_Click(object sender, RoutedEventArgs e)
    {
        if (mp == null) return;
        try { mp.Time = mp.Time + 10000; } catch { }
    }

    void Seek_Down(object sender, MouseButtonEventArgs e) => dragging = true;

    void Seek_Up(object sender, MouseButtonEventArgs e)
    {
        dragging = false;
        if (mp == null) return;
        try { if (mp.Length > 0) mp.Time = (long)(SeekSlider.Value / 1000.0 * mp.Length); } catch { }
    }

    void Volume_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (mp == null) return;
        try { mp.Volume = (int)e.NewValue; } catch { }
    }

    void Speed_Changed(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (SpeedBox.SelectedItem is not string s) return;
        if (float.TryParse(s.TrimEnd('x'), out float v) && v > 0)
        {
            PlayerState.Speed = v;
            ApplySpeed();
        }
    }

    void Quality_Changed(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (QualityBox.SelectedIndex < 0 || req.Qualities.Count == 0 || !started) return;
        var q = req.Qualities[QualityBox.SelectedIndex];
        if (q.Type == req.StreamType) return;
        if (req.BaiduPath.Length == 0 || Store.Config.cookies.Length == 0) return;
        long pos = 0;
        try { pos = mp?.Time ?? 0; } catch { }
        req.StreamType = q.Type;
        req.StartMs = pos;
        started = false;
        if (req.BaiduPath.Length > 0)
        {
            PlayViaProxy();
            return;
        }
        Task.Run(() =>
        {
            try
            {
                string url = BaiduClient.StreamingUrlFor(Store.Config.cookies, req.BaiduPath, q.Type);
                Dispatcher.Invoke(() => { req.Uri = url; PlayMedia(url); try { if (mp != null && pos > 0) mp.Time = pos; } catch { } });
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() => ShowError("切换清晰度失败：" + ex.Message));
            }
        });
    }

    void Audio_Changed(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (mp == null || AudioBox.SelectedItem is not TrackItem t) return;
        try { mp.SetAudioTrack(t.Id); } catch { }
    }

    void Subtitle_Changed(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (mp == null || SubtitleBox.SelectedItem is not TrackItem t) return;
        try { mp.SetSpu(t.Id); } catch { }
    }

    void SubFile_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Title = "选择外部字幕文件",
            Filter = "字幕文件|*.srt;*.ass;*.ssa;*.vtt;*.sub|所有文件|*.*"
        };
        if (dlg.ShowDialog(this) != true || mp == null) return;
        try
        {
            if (mp.AddSlave(MediaSlaveType.Subtitle, new Uri(dlg.FileName).AbsoluteUri, true))
                RefreshTracks();
        }
        catch (Exception ex) { ShowError("加载字幕失败：" + ex.Message); }
    }

    void Aspect_Changed(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (mp == null || AspectBox.SelectedItem is not string s) return;
        try
        {
            mp.AspectRatio = s switch
            {
                "16:9" => "16:9",
                "4:3" => "4:3",
                "铺满" => "16:10",
                "原始" => "1:1",
                _ => null,
            };
        }
        catch { }
    }

    void Topmost_Changed(object sender, RoutedEventArgs e)
    {
        Topmost = TopmostBox.IsChecked == true;
    }

    void Fullscreen_Click(object sender, RoutedEventArgs e) => ToggleFullscreen();

    void ToggleFullscreen()
    {
        fullscreen = !fullscreen;
        if (fullscreen)
        {
            WindowStyle = WindowStyle.None;
            WindowState = WindowState.Maximized;
        }
        else
        {
            WindowStyle = WindowStyle.SingleBorderWindow;
            WindowState = WindowState.Normal;
        }
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        // 钩子已处理过则跳过，避免双重触发；钩子不可用时这里兜底
        if ((Environment.TickCount - hookHandledAt) < 300) { e.Handled = true; return; }
        if (SpeedBox.IsDropDownOpen || QualityBox.IsDropDownOpen || AudioBox.IsDropDownOpen ||
            SubtitleBox.IsDropDownOpen || AspectBox.IsDropDownOpen) return;
        Overlay.Focus();
        HandleKey(e.Key, e);
    }

    void HandleKey(Key key, KeyEventArgs? e)
    {
        if (key == Key.Space) TogglePlayPause();
        else if (key == Key.F) ToggleFullscreen();
        else if (key == Key.Escape && fullscreen) ToggleFullscreen();
        else if (key == Key.Left) { try { if (mp != null) mp.Time = Math.Max(0, mp.Time - 10000); } catch { } }
        else if (key == Key.Right) { try { if (mp != null) mp.Time = mp.Time + 10000; } catch { } }
        else if (key == Key.Up && mp != null) { try { mp.Volume = Math.Min(200, mp.Volume + 10); VolumeSlider.Value = mp.Volume; } catch { } }
        else if (key == Key.Down && mp != null) { try { mp.Volume = Math.Max(0, mp.Volume - 10); VolumeSlider.Value = mp.Volume; } catch { } }
        else return;
        if (e != null) e.Handled = true;
    }

    void TogglePlayPause()
    {
        if (mp == null) return;
        try
        {
            // libvlc 的 State 在 Pause() 后会短暂仍报 Playing，用它判断会导致"再按空格不恢复"
            bool playing = userWantsPlay;
            Diag.Log("toggle play/pause -> " + (playing ? "pause" : "resume"));
            if (playing) { userWantsPlay = false; mp.Pause(); }
            else { userWantsPlay = true; mp.Play(); }
        }
        catch (Exception ex) { Diag.Log("toggle failed: " + ex.Message); }
        ResetHideTimer();
    }

    const int WH_KEYBOARD_LL = 13;
    const int WM_KEYDOWN = 0x0100;
    const int WM_SYSKEYDOWN = 0x0104;
    static IntPtr kbdHook = IntPtr.Zero;
    static AppLowLevelKeyboardProc? kbdProc;
    static int kbdHookRefs;
    int hookHandledAt = -10000;

    static void InstallKeyboardHook()
    {
        lock (typeof(PlayerWindow))
        {
            kbdHookRefs++;
            if (kbdHook != IntPtr.Zero) return;
            try
            {
                kbdProc = HookCallback;
                kbdHook = SetWindowsHookEx(WH_KEYBOARD_LL, kbdProc, IntPtr.Zero, 0);
            }
            catch (Exception ex) { Diag.Log("kbd hook failed: " + ex.Message); }
        }
    }

    static void UninstallKeyboardHook()
    {
        lock (typeof(PlayerWindow))
        {
            if (kbdHookRefs > 0) kbdHookRefs--;
            if (kbdHookRefs > 0 || kbdHook == IntPtr.Zero) return;
            try { UnhookWindowsHookEx(kbdHook); } catch { }
            kbdHook = IntPtr.Zero;
            kbdProc = null;
        }
    }

    static IntPtr HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        const int VK_SPACE = 0x20, VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28;
        if (nCode >= 0)
        {
            var win = System.Windows.Application.Current?.Windows.OfType<PlayerWindow>().FirstOrDefault(w => w.IsActive);
            int msg = (int)wParam;
            if (win != null && (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN))
            {
                int vk = System.Runtime.InteropServices.Marshal.ReadInt32(lParam);
                Key key = vk switch
                {
                    VK_SPACE => Key.Space,
                    VK_LEFT => Key.Left,
                    VK_UP => Key.Up,
                    VK_RIGHT => Key.Right,
                    VK_DOWN => Key.Down,
                    _ => Key.None,
                };
                if (key != Key.None)
                {
                    win.hookHandledAt = Environment.TickCount;
                    win.Dispatcher.BeginInvoke(() => win.HandleKey(key, null));
                    if (key == Key.Space) return IntPtr.Zero; // 吞掉，避免触发聚焦按钮
                }
            }
        }
        return CallNextHookEx(kbdHook, nCode, wParam, lParam);
    }

    delegate IntPtr AppLowLevelKeyboardProc(int nCode, IntPtr wParam, IntPtr lParam);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern IntPtr SetWindowsHookEx(int idHook, AppLowLevelKeyboardProc lpfn, IntPtr hMod, uint dwThreadId);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    [return: System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.Bool)]
    static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    void SaveRecord()
    {
        if (mp == null || !RecordingEnabled) return;
        try
        {
            long len = mp.Length, t = mp.Time;
            if (len <= 0 || t < 0) return;
            lastSavedPos = t / 1000;
            string key = req.BaiduPath.Length > 0 ? "baidu:" + req.BaiduPath : req.Uri;
            Store.SaveRecord(key, req.Title, lastSavedPos, len / 1000, req.FsId, req.BaiduPath);
        }
        catch { }
    }

    void Cleanup()
    {
        try
        {
            UninstallKeyboardHook();
            uiTimer.Stop(); hideTimer.Stop(); saveTimer.Stop(); inputTimer.Stop();
            SaveRecord();
            if (proxySession.Length > 0) HlsProxy.Release(proxySession);
            proxySession = "";
            if (VideoView.MediaPlayer != null)
            {
                VideoView.MediaPlayer.Stop();
                VideoView.MediaPlayer.Dispose();
                VideoView.MediaPlayer = null;
            }
            mp?.Dispose();
            mp = null;
        }
        catch { }
    }
}

class TrackItem
{
    public int Id;
    public string Label = "";
    public override string ToString() => Label;
}
