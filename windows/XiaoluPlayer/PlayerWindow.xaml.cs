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
    bool dragging;
    bool started;
    bool refreshed;
    bool fullscreen;
    bool useProxy;
    string proxySession = "";
    long lastSavedPos;

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
        KeyDown += OnKey;
        Loaded += (s, e) => StartPlayback();
    }

    void StartPlayback()
    {
        try
        {
            var lib = App.LibVLC ?? throw new Exception("播放器未初始化");
            mp = new MediaPlayer(lib);
            mp.Playing += (s, e) => Dispatcher.Invoke(() =>
            {
                PlayBtn.Content = "⏸";
                if (!started)
                {
                    started = true;
                    if (req.StartMs > 0) { try { mp!.Time = req.StartMs; } catch { } }
                    RefreshTracks();
                }
                ResetHideTimer();
            });
            mp.Paused += (s, e) => Dispatcher.Invoke(() => { PlayBtn.Content = "▶"; ShowBars(); });
            mp.EndReached += (s, e) => Dispatcher.Invoke(() => { PlayBtn.Content = "▶"; ShowBars(); SaveRecord(); });
            mp.EncounteredError += (s, e) => Dispatcher.Invoke(OnError);
            VideoView.MediaPlayer = mp;
            PlayMedia(req.Uri);
            uiTimer.Start();
            saveTimer.Start();
        }
        catch (Exception ex) { ShowError("启动播放失败：" + ex.Message); }
    }

    List<string> MediaOptions()
    {
        var opts = new List<string> { ":network-caching=2500" };
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
        if (req.BaiduPath.Length > 0)
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
        string type = req.StreamType.Length > 0 ? req.StreamType : "M3U8_AUTO_480";
        Task.Run(() =>
        {
            try
            {
                var pl = BaiduClient.FetchPlaylist(req.Cookies, req.BaiduPath, type);
                var h = BaiduClient.StreamHeaders(req.Cookies);
                Dispatcher.Invoke(() =>
                {
                    try
                    {
                        if (proxySession.Length > 0) HlsProxy.Release(proxySession);
                        string local = HlsProxy.Serve(pl.segs, req.Cookies, h["User-Agent"], h["Referer"]);
                        proxySession = local.Substring(local.IndexOf("session=") + 8);
                        var media = new Media(App.LibVLC!, local, FromType.FromLocation, MediaOptions().ToArray());
                        mp!.Play(media);
                        media.Dispose();
                        ErrorText.Visibility = Visibility.Collapsed;
                    }
                    catch (Exception ex) { ShowError("播放失败：" + ex.Message); }
                });
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() => ShowError("获取播放地址失败：" + ex.Message));
            }
        });
    }

    void OnError()
    {
        if (!refreshed && req.FsId >= 0 && req.BaiduPath.Length > 0 && Store.Config.cookies.Length > 0)
        {
            refreshed = true;
            long pos = 0;
            try { pos = mp?.Time ?? 0; } catch { }
            Task.Run(() =>
            {
                try
                {
                    if (req.BaiduPath.Length > 0)
                    {
                        Dispatcher.Invoke(() =>
                        {
                            req.Uri = "";
                            req.StartMs = pos;
                            started = false;
                            ErrorText.Visibility = Visibility.Collapsed;
                            PlayViaProxy();
                        });
                        return;
                    }
                    var link = BaiduClient.GetStreamLink(Store.Config.cookies, req.BaiduPath);
                    Dispatcher.Invoke(() =>
                    {
                        req.Uri = link.Url;
                        req.Qualities = link.Qualities;
                        req.StreamType = link.CurrentType;
                        ErrorText.Visibility = Visibility.Collapsed;
                        PlayMedia(link.Url);
                        try { if (mp != null && pos > 0) mp.Time = pos; } catch { }
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() => ShowError("播放失败：" + ex.Message));
                }
            });
            return;
        }
        ShowError("播放失败：无法打开该视频");
    }

    void ShowError(string msg)
    {
        ErrorText.Text = msg;
        ErrorText.Visibility = Visibility.Visible;
        PlayBtn.Content = "▶";
        ShowBars();
    }

    void UiTick(object? sender, EventArgs e)
    {
        if (mp == null) return;
        try
        {
            long len = mp.Length, t = mp.Time;
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
        if (mp != null)
        {
            try { if (mp.State == VLCState.Playing) { hideTimer.Start(); return; } } catch { }
        }
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
        if (e.ClickCount >= 2) { ToggleFullscreen(); return; }
        PlayPause_Click(sender, e);
    }

    void PlayPause_Click(object sender, RoutedEventArgs e)
    {
        if (mp == null) return;
        try
        {
            if (mp.State == VLCState.Playing) mp.Pause();
            else mp.Play();
        }
        catch { }
        ResetHideTimer();
    }

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

    void OnKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && fullscreen) { ToggleFullscreen(); e.Handled = true; }
        else if (e.Key == Key.Space) { PlayPause_Click(sender, e); e.Handled = true; }
        else if (e.Key == Key.F) { ToggleFullscreen(); e.Handled = true; }
        else if (e.Key == Key.Left) { Back10_Click(sender, e); e.Handled = true; }
        else if (e.Key == Key.Right) { Fwd10_Click(sender, e); e.Handled = true; }
        else if (e.Key == Key.Up && mp != null) { try { mp.Volume = Math.Min(200, mp.Volume + 10); VolumeSlider.Value = mp.Volume; } catch { } e.Handled = true; }
        else if (e.Key == Key.Down && mp != null) { try { mp.Volume = Math.Max(0, mp.Volume - 10); VolumeSlider.Value = mp.Volume; } catch { } e.Handled = true; }
    }

    void SaveRecord()
    {
        if (mp == null) return;
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
            uiTimer.Stop(); hideTimer.Stop(); saveTimer.Stop();
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
