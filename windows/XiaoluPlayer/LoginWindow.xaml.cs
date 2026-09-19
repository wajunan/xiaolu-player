using System.IO;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;

namespace XiaoluPlayer;

public partial class LoginWindow : Window
{
    const string PanUrl = "https://pan.baidu.com/";
    const string DiskHome = "https://pan.baidu.com/disk/home";
    // 列表接口除 pan.baidu.com 外还依赖 .baidu.com / 网盘子域下的会话 Cookie。
    // 按优先级抓取，先拿到 pan.baidu.com 的 Cookie，后补其它域下同名 Cookie。
    static readonly string[] CookieScopes =
    {
        "https://pan.baidu.com/",
        "https://pcs.baidu.com/",
        "https://vd.baidu.com/",
        "https://www.baidu.com/",
        "https://passport.baidu.com/",
    };
    readonly DispatcherTimer poll = new() { Interval = TimeSpan.FromSeconds(1) };
    bool checking;
    bool settled;
    DateTime loginAt = DateTime.MinValue;

    public LoginWindow()
    {
        InitializeComponent();
        poll.Tick += Poll_Tick;
    }

    async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        try
        {
            string udf = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "XiaoluPlayer", "EdgeWebView");
            var env = await CoreWebView2Environment.CreateAsync(null, udf);
            await Web.EnsureCoreWebView2Async(env);
            Web.CoreWebView2.Navigate(PanUrl);
            StatusText.Text = "请在页面内完成登录（支持扫码 / 账号 / 短信等百度官方方式）";
            poll.Start();
        }
        catch (Exception ex)
        {
            StatusText.Text = "内嵌浏览器初始化失败（可能缺少 WebView2 运行组件），请关闭本窗口改用“手动粘贴 Cookie”备用方式登录。";
            MessageBox.Show(this, "内嵌浏览器初始化失败：\n" + ex.Message +
                "\n\n可关闭本窗口，改用网盘窗口中的“手动粘贴 Cookie”备用方式登录。", "小鹿播放增强器");
        }
    }

    void Window_Closed(object? sender, EventArgs e)
    {
        poll.Stop();
    }

    async void Poll_Tick(object? sender, EventArgs e)
    {
        if (checking || Web.CoreWebView2 == null) return;
        checking = true;
        try
        {
            var dict = await CaptureCookiesAsync();
            if (dict.TryGetValue("BDUSS", out string? bduss) && !string.IsNullOrEmpty(bduss))
            {
                if (loginAt == DateTime.MinValue) loginAt = DateTime.Now;

                // 检测到登录后先跳转到网盘首页，触发 .baidu.com 会话 Cookie 落地
                if (!settled)
                {
                    settled = true;
                    try { Web.CoreWebView2.Navigate(DiskHome); } catch { }
                }

                bool hasPanCookies = dict.ContainsKey("BAIDUID") || dict.ContainsKey("STOKEN") || dict.ContainsKey("PANPSC");
                bool settledFor = (DateTime.Now - loginAt).TotalSeconds >= 5 && hasPanCookies;
                StatusText.Text = settledFor
                    ? "已检测到登录成功 ✓，网盘会话已同步，可进入网盘"
                    : "已检测到登录，正在同步网盘会话 Cookie…";
                DoneButton.IsEnabled = settledFor;
            }
        }
        catch { }
        finally { checking = false; }
    }

    async Task<Dictionary<string, string>> CaptureCookiesAsync()
    {
        var dict = new Dictionary<string, string>();
        if (Web.CoreWebView2 == null) return dict;
        foreach (var scope in CookieScopes)
        {
            try
            {
                var list = await Web.CoreWebView2.CookieManager.GetCookiesAsync(scope);
                foreach (var c in list)
                {
                    if (string.IsNullOrEmpty(c.Name) || string.IsNullOrEmpty(c.Value)) continue;
                    // pan.baidu.com 作用域优先，后面的 www/passport 等只补空，不能覆盖网盘域 Cookie。
                    if (!dict.ContainsKey(c.Name)) dict[c.Name] = c.Value;
                }
            }
            catch { }
        }
        Diag.Log("login cookies captured: " + string.Join(", ", dict.Keys));
        return dict;
    }

    async void Done_Click(object sender, RoutedEventArgs e)
    {
        var dict = await CaptureCookiesAsync();
        if (!dict.TryGetValue("BDUSS", out string? bduss) || string.IsNullOrEmpty(bduss))
        {
            MessageBox.Show(this, "尚未检测到登录，请先在上方页面完成登录。", "小鹿播放增强器");
            return;
        }
        string missing = "";
        if (!dict.ContainsKey("BAIDUID")) missing += " BAIDUID";
        if (!dict.ContainsKey("STOKEN") && !dict.ContainsKey("PANPSC")) missing += " STOKEN/PANPSC";
        if (missing.Length > 0) Diag.Log("login cookies incomplete:" + missing);
        string ck = string.Join("; ", dict.Select(kv => kv.Key + "=" + kv.Value));
        if (ck.Length < 20)
        {
            MessageBox.Show(this, "登录会话异常，请重试。", "小鹿播放增强器");
            return;
        }
        Store.Config.cookies = ck;
        Store.SaveConfig();
        DialogResult = true;
    }

    void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
