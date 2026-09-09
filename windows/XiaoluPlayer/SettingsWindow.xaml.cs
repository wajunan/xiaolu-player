using System.Windows;
using System.Windows.Controls;

namespace XiaoluPlayer;

public partial class SettingsWindow : Window
{
    public SettingsWindow()
    {
        InitializeComponent();
        foreach (ComboBoxItem item in SpeedBox.Items)
        {
            if (item.Content as string == Store.Config.defaultSpeed.ToString("0.##") + "x")
            {
                SpeedBox.SelectedItem = item;
                break;
            }
        }
        if (SpeedBox.SelectedIndex < 0) SpeedBox.SelectedIndex = 2;
        RememberBox.IsChecked = Store.Config.rememberSpeed;
        ResumeBox.IsChecked = Store.Config.autoResume;
    }

    void Save_Click(object sender, RoutedEventArgs e)
    {
        string s = (SpeedBox.SelectedItem as ComboBoxItem)?.Content as string ?? "1.0x";
        if (float.TryParse(s.TrimEnd('x'), out float v)) Store.Config.defaultSpeed = v;
        Store.Config.rememberSpeed = RememberBox.IsChecked == true;
        Store.Config.autoResume = ResumeBox.IsChecked == true;
        Store.SaveConfig();
        if (!Store.Config.rememberSpeed) PlayerState.Speed = Store.Config.defaultSpeed;
        DialogResult = true;
    }

    void Clear_Click(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show(this, "确定清空全部播放记录吗？", "小鹿播放增强器",
            MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        Store.Records().Clear();
        try
        {
            string p = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "XiaoluPlayer", "records.json");
            if (System.IO.File.Exists(p)) System.IO.File.Delete(p);
        }
        catch { }
    }

    void Close_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
