using Microsoft.Win32;
using System.Windows;
using System.Windows.Input;

namespace XiaoluPlayer;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        RefreshRecents();
        Activated += (s, e) => RefreshRecents();
    }

    public void RefreshRecents()
    {
        var list = new List<RecordView>();
        foreach (var r in Store.Records())
        {
            list.Add(new RecordView
            {
                Record = r,
                Title = r.title,
                Frac = r.dur > 0 ? Math.Min(1.0, (double)r.pos / r.dur) : 0,
                Sub = FormatTime(r.pos) + " / " + FormatTime(r.dur) + " · " + Relative(r.ts)
            });
        }
        RecordsList.ItemsSource = list;
        if (list.Count == 0) StatusText.Text = "还没有播放记录";
        else StatusText.Text = "";
    }

    void Records_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (RecordsList.SelectedItem is not RecordView v) return;
        RecordsList.SelectedItem = null;
        PlayRecord(v.Record);
    }

    async void PlayRecord(PlaybackRecord r)
    {
        try
        {
            if (r.fsId >= 0 && r.path.Length > 0 && Store.Config.cookies.Length > 0)
            {
                StatusText.Text = "获取播放地址…";
                Cursor = Cursors.Wait;
                var link = await Task.Run(() => BaiduClient.GetStreamLink(Store.Config.cookies, r.path));
                Cursor = Cursors.Arrow;
                StatusText.Text = "";
                var h = BaiduClient.StreamHeaders(Store.Config.cookies);
                new PlayerWindow(new PlayRequest
                {
                    Uri = link.Url, Title = r.title,
                    UserAgent = h["User-Agent"], Referer = h["Referer"], Cookies = h["Cookie"],
                    FsId = r.fsId, BaiduPath = r.path,
                    Qualities = link.Qualities, StreamType = link.CurrentType,
                    StartMs = Store.Config.autoResume ? r.pos * 1000 : 0
                }).Show();
            }
            else
            {
                long start = Store.Config.autoResume ? r.pos * 1000 : 0;
                new PlayerWindow(new PlayRequest { Uri = r.uri, Title = r.title, StartMs = start }).Show();
            }
        }
        catch (Exception ex)
        {
            Cursor = Cursors.Arrow;
            StatusText.Text = "";
            MessageBox.Show(this, "获取播放地址失败：\n" + ex.Message, "小鹿播放增强器");
        }
    }

    void Local_Click(object sender, MouseButtonEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "选择本地视频文件",
            Filter = "视频文件|*.mp4;*.mkv;*.avi;*.mov;*.wmv;*.flv;*.ts;*.m2ts;*.rmvb;*.webm;*.m4v;*.3gp;*.mpg;*.mpeg;*.ogv|所有文件|*.*"
        };
        if (dlg.ShowDialog(this) != true) return;
        string uri = new Uri(dlg.FileName).AbsoluteUri;
        var rec = Store.Find(uri);
        long start = (Store.Config.autoResume && rec != null) ? rec.pos * 1000 : 0;
        new PlayerWindow(new PlayRequest { Uri = uri, Title = System.IO.Path.GetFileName(dlg.FileName), StartMs = start }).Show();
    }

    void Url_Click(object sender, MouseButtonEventArgs e)
    {
        var w = new OpenUrlWindow { Owner = this };
        if (w.ShowDialog() != true) return;
        new PlayerWindow(new PlayRequest
        {
            Uri = w.MediaUrl, Title = w.MediaTitle,
            UserAgent = w.CustomUa, Referer = w.CustomReferer, Cookies = w.CustomCookies
        }).Show();
    }

    void Netdisk_Click(object sender, MouseButtonEventArgs e)
    {
        new NetdiskWindow { Owner = this }.Show();
    }

    void Settings_Click(object sender, MouseButtonEventArgs e)
    {
        new SettingsWindow { Owner = this }.ShowDialog();
    }

    public static string FormatTime(long seconds)
    {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return h + ":" + m.ToString("D2") + ":" + s.ToString("D2");
        return m + ":" + s.ToString("D2");
    }

    public static string Relative(long unixTs)
    {
        long d = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - unixTs;
        if (d < 60) return "刚刚";
        if (d < 3600) return (d / 60) + " 分钟前";
        if (d < 86400) return (d / 3600) + " 小时前";
        return (d / 86400) + " 天前";
    }
}

class RecordView
{
    public PlaybackRecord? Record;
    public string Title = "";
    public double Frac;
    public string Sub = "";
}
