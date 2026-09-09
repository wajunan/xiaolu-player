using System.Windows;

namespace XiaoluPlayer;

public partial class OpenUrlWindow : Window
{
    public string MediaUrl = "";
    public string MediaTitle = "";
    public string CustomUa = "";
    public string CustomReferer = "";
    public string CustomCookies = "";

    public OpenUrlWindow()
    {
        InitializeComponent();
        UrlBox.Focus();
    }

    void Ok_Click(object sender, RoutedEventArgs e)
    {
        string url = UrlBox.Text.Trim();
        if (url.Length == 0)
        {
            MessageBox.Show(this, "请粘贴视频地址", "小鹿播放增强器");
            return;
        }
        MediaUrl = url;
        MediaTitle = TitleBox.Text.Trim();
        if (MediaTitle.Length == 0)
        {
            try { MediaTitle = new Uri(url).Segments[^1]; }
            catch { MediaTitle = url; }
        }
        CustomUa = UaBox.Text.Trim();
        CustomReferer = RefererBox.Text.Trim();
        CustomCookies = CookieBox.Text.Trim();
        DialogResult = true;
    }

    void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
