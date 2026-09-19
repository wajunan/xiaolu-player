using System.Windows;

namespace XiaoluPlayer;

public partial class App : Application
{
    public static LibVLCSharp.Shared.LibVLC? LibVLC;
    static bool selftest;
    static bool selftestWindow;

    protected override void OnStartup(StartupEventArgs e)
    {
        foreach (var a in e.Args)
        {
            if (a != "--selftest") continue;
            // 必须先 Init：否则 OnExit 会把内存里的空配置写回磁盘，抹掉已保存的登录 Cookie
            selftest = true;
            Store.Init();
            // 窗口自测要用真实 Application 生命周期跑，不能在这里同步返回
            if (SelfTest.WantsWindowRun(e.Args)) { selftestWindow = true; break; }
            int code = SelfTest.Run(e.Args);
            Shutdown(code);
            return;
        }
        Store.Init();
        base.OnStartup(e);
        if (Store.Config.rememberSpeed && Store.Config.lastSpeed > 0)
            PlayerState.Speed = Store.Config.lastSpeed;
        else
            PlayerState.Speed = Store.Config.defaultSpeed;
        EnsureLibVLC();
        if (selftestWindow)
        {
            SelfTest.StartWindowRun(this, e.Args);
            return;
        }
        var main = new MainWindow();
        MainWindow = main;
        main.Show();
    }

    /// <summary>创建（一次）带诊断日志路由的 LibVLC 实例；自测与正式播放共用同一套配置。</summary>
    public static void EnsureLibVLC()
    {
        if (LibVLC != null) return;
        LibVLCSharp.Shared.Core.Initialize();
        LibVLC = new LibVLCSharp.Shared.LibVLC();
        // 把 libvlc 的报错接进诊断日志：GUI 播放失败时能看到到底是取流失败还是解码/输出失败
        LibVLC.Log += (sender, ev) =>
        {
            try
            {
                if (ev.Level != LibVLCSharp.Shared.LogLevel.Error &&
                    ev.Level != LibVLCSharp.Shared.LogLevel.Warning) return;
                string m = (ev.Message ?? "").Trim();
                if (m.Length == 0) return;
                int cut = m.IndexOf("Cookie", StringComparison.OrdinalIgnoreCase);
                if (cut >= 0) m = m.Substring(0, cut) + "[...]";
                Diag.Log("vlc[" + ev.Level + "] " + (m.Length > 300 ? m.Substring(0, 300) : m));
            }
            catch { }
        };
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            if (!selftest)
            {
                if (Store.Config.rememberSpeed) Store.Config.lastSpeed = PlayerState.Speed;
                Store.SaveConfig();
            }
            LibVLC?.Dispose();
        }
        catch { }
        base.OnExit(e);
    }
}
