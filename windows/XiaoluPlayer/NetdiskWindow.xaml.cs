using System.Windows;
using System.Windows.Input;

namespace XiaoluPlayer;

public partial class NetdiskWindow : Window
{
    string dir = "/";
    bool videoMode;
    readonly Stack<string> history = new();

    public NetdiskWindow()
    {
        InitializeComponent();
        if (Store.Config.cookies.Length > 0) ShowBrowser();
        else ShowLogin();
    }

    void ShowLogin()
    {
        LoginPanel.Visibility = Visibility.Visible;
        BrowserPanel.Visibility = Visibility.Collapsed;
    }

    void ShowBrowser()
    {
        LoginPanel.Visibility = Visibility.Collapsed;
        BrowserPanel.Visibility = Visibility.Visible;
        LoadDir("/");
    }

    void Back_Click(object sender, RoutedEventArgs e) => Close();

    void InAppLogin_Click(object sender, RoutedEventArgs e)
    {
        var w = new LoginWindow { Owner = this };
        if (w.ShowDialog() == true) ShowBrowser();
    }

    void Login_Click(object sender, RoutedEventArgs e)
    {
        string ck = CookieBox.Text.Trim();
        if (ck.Length < 20)
        {
            MessageBox.Show(this, "请粘贴完整的 Cookie", "小鹿播放增强器");
            return;
        }
        Store.Config.cookies = ck;
        Store.SaveConfig();
        ShowBrowser();
    }

    void Logout_Click(object sender, RoutedEventArgs e)
    {
        Store.Config.cookies = "";
        Store.SaveConfig();
        ShowLogin();
    }

    void Refresh_Click(object sender, RoutedEventArgs e)
    {
        if (videoMode) LoadAllVideos();
        else LoadDir(dir);
    }

    void CurrentDir_Click(object sender, RoutedEventArgs e)
    {
        videoMode = false;
        LoadDir(dir);
    }

    void AllVideos_Click(object sender, RoutedEventArgs e)
    {
        videoMode = true;
        LoadAllVideos();
    }

    void LoadDir(string target)
    {
        videoMode = false;
        StatusText.Text = "加载中…";
        Cursor = Cursors.Wait;
        string cookies = Store.Config.cookies;
        Task.Run(() =>
        {
            try
            {
                var files = BaiduClient.List(cookies, target);
                files.Sort((a, b) => a.IsDir != b.IsDir ? (a.IsDir ? -1 : 1)
                    : BaiduClient.NaturalCompare(a.Name, b.Name));
                Dispatcher.Invoke(() =>
                {
                    dir = target;
                    PathText.Text = target == "/" ? "我的网盘" : target.Substring(target.LastIndexOf('/') + 1);
                    var items = new List<FileView>();
                    foreach (var f in files)
                    {
                        bool playable = f.IsDir || BaiduClient.IsVideoName(f.Name) || f.Category == 1;
                        items.Add(new FileView
                        {
                            File = f,
                            Icon = f.IsDir ? "📁" : (playable ? "🎬" : "📄"),
                            Name = f.Name,
                            Sub = f.IsDir ? "文件夹" : FormatBytes(f.Size),
                            Badge = (playable && !f.IsDir) ? "▶" : ""
                        });
                    }
                    FileList.ItemsSource = items;
                    var names = cookies
                        .Split(';', StringSplitOptions.RemoveEmptyEntries)
                        .Select(p => p.Trim().Split('=')[0])
                        .Where(n => n is "BDUSS" or "STOKEN" or "BAIDUID" or "PANPSC" or "BDUSS_LG")
                        .ToList();
                    bool onlyFolders = items.Count > 0 && items.All(f => f.File != null && f.File.IsDir);
                    string hint = target == "/" && onlyFolders ? " · 根目录只有文件夹，可点“全部视频”" : "";
                    StatusText.Text = $"共 {items.Count} 项 · 会话Cookie: {(names.Count > 0 ? string.Join(", ", names) : "无(登录不完整)")}{hint} · 日志: {Diag.LogPath}";
                    Cursor = Cursors.Arrow;
                });
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() =>
                {
                    StatusText.Text = "";
                    Cursor = Cursors.Arrow;
                    Diag.Log("list failed: " + ex.Message);
                    MessageBox.Show(this, "加载失败：\n" + ex.Message + "\n\n诊断日志：" + Diag.LogPath, "小鹿播放增强器");
                });
            }
        });
    }

    void LoadAllVideos()
    {
        videoMode = true;
        StatusText.Text = "扫描全部视频中…";
        Cursor = Cursors.Wait;
        string cookies = Store.Config.cookies;
        Task.Run(() =>
        {
            try
            {
                var res = BaiduClient.ListAllVideos(cookies, "/", 500, 2000, 50000, r =>
                {
                    Dispatcher.Invoke(() =>
                    {
                        StatusText.Text = $"扫描中… 目录 {r.Scans}/500 · 文件 {r.FilesSeen} · 视频 {r.Videos.Count}";
                    });
                });
                Dispatcher.Invoke(() =>
                {
                    PathText.Text = "全部视频";
                    var items = new List<FileView>();
                    foreach (var f in res.Videos)
                    {
                        items.Add(new FileView
                        {
                            File = f,
                            Icon = "🎬",
                            Name = f.Name,
                            Sub = FormatBytes(f.Size),
                            Badge = "▶"
                        });
                    }
                    FileList.ItemsSource = items;
                    var names = cookies
                        .Split(';', StringSplitOptions.RemoveEmptyEntries)
                        .Select(p => p.Trim().Split('=')[0])
                        .Where(n => n is "BDUSS" or "STOKEN" or "BAIDUID" or "PANPSC" or "BDUSS_LG")
                        .ToList();
                    string hint = res.Capped ? " · 已达扫描上限" : "";
                    StatusText.Text = $"全部视频 {items.Count} 项 · 扫描目录 {res.Scans} · 子目录 {res.DirsFound}{hint} · 会话Cookie: {(names.Count > 0 ? string.Join(", ", names) : "无(登录不完整)")} · 日志: {Diag.LogPath}";
                    Cursor = Cursors.Arrow;
                });
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() =>
                {
                    StatusText.Text = "";
                    Cursor = Cursors.Arrow;
                    Diag.Log("all videos failed: " + ex.Message);
                    MessageBox.Show(this, "扫描全部视频失败：\n" + ex.Message + "\n\n诊断日志：" + Diag.LogPath, "小鹿播放增强器");
                });
            }
        });
    }

    void File_Selected(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (FileList.SelectedItem is not FileView v) return;
        FileList.SelectedItem = null;
        var f = v.File;
        if (f == null) return;
        if (f.IsDir) { history.Push(dir); LoadDir(f.Path); return; }
        if (!BaiduClient.IsVideoName(f.Name) && f.Category != 1) return;
        PlayFile(f);
    }

    void PlayFile(PanFile f)
    {
        StatusText.Text = "获取播放地址…";
        Cursor = Cursors.Wait;
        string cookies = Store.Config.cookies;
        Task.Run(() =>
        {
            try
            {
                var link = BaiduClient.GetStreamLink(cookies, f.Path, f.FsId);
                Dispatcher.Invoke(() =>
                {
                    StatusText.Text = "";
                    Cursor = Cursors.Arrow;
                    var h = BaiduClient.StreamHeaders(cookies);
                    var rec = Store.Find("baidu:" + f.Path);
                    new PlayerWindow(new PlayRequest
                    {
                        Uri = link.Url, Title = f.Name,
                        UserAgent = h["User-Agent"], Referer = h["Referer"], Cookies = h["Cookie"],
                        FsId = f.FsId, BaiduPath = f.Path,
                        Qualities = link.Qualities, StreamType = link.CurrentType,
                        StartMs = (Store.Config.autoResume && rec != null) ? rec.pos * 1000 : 0
                    }).Show();
                });
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() =>
                {
                    StatusText.Text = "";
                    Cursor = Cursors.Arrow;
                    MessageBox.Show(this, "获取播放地址失败：\n" + ex.Message, "小鹿播放增强器");
                });
            }
        });
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.Key == Key.Back && history.Count > 0 && BrowserPanel.Visibility == Visibility.Visible)
        {
            LoadDir(history.Pop());
            e.Handled = true;
        }
        base.OnKeyDown(e);
    }

    public static string FormatBytes(long bytes)
    {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return (bytes / 1024.0).ToString("0.0") + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1048576.0).ToString("0.0") + " MB";
        return (bytes / 1073741824.0).ToString("0.00") + " GB";
    }
}

class FileView
{
    public PanFile? File { get; set; }
    public string Icon { get; set; } = "";
    public string Name { get; set; } = "";
    public string Sub { get; set; } = "";
    public string Badge { get; set; } = "";
}
