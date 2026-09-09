using System.Diagnostics;
using System.Windows;
using System.Windows.Input;

namespace XiaoluPlayer;

public partial class NetdiskWindow : Window
{
    string dir = "/";
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

    void OpenLogin_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("https://pan.baidu.com/") { UseShellExecute = true }); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "小鹿播放增强器"); }
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

    void Refresh_Click(object sender, RoutedEventArgs e) => LoadDir(dir);

    void LoadDir(string target)
    {
        StatusText.Text = "加载中…";
        Cursor = Cursors.Wait;
        string cookies = Store.Config.cookies;
        Task.Run(() =>
        {
            try
            {
                var files = BaiduClient.List(cookies, target);
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
                    StatusText.Text = "";
                    Cursor = Cursors.Arrow;
                });
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() =>
                {
                    StatusText.Text = "";
                    Cursor = Cursors.Arrow;
                    MessageBox.Show(this, "加载失败：\n" + ex.Message, "小鹿播放增强器");
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
                var link = BaiduClient.GetStreamLink(cookies, f.Path);
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
    public PanFile? File;
    public string Icon = "";
    public string Name = "";
    public string Sub = "";
    public string Badge = "";
}
