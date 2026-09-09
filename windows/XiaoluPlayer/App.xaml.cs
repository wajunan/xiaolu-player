using System.Windows;

namespace XiaoluPlayer;

public partial class App : Application
{
    public static LibVLCSharp.Shared.LibVLC? LibVLC;

    protected override void OnStartup(StartupEventArgs e)
    {
        foreach (var a in e.Args)
        {
            if (a == "--selftest")
            {
                int code = SelfTest.Run(e.Args);
                Shutdown(code);
                return;
            }
        }
        Store.Init();
        base.OnStartup(e);
        if (Store.Config.rememberSpeed && Store.Config.lastSpeed > 0)
            PlayerState.Speed = Store.Config.lastSpeed;
        else
            PlayerState.Speed = Store.Config.defaultSpeed;
        LibVLCSharp.Shared.Core.Initialize();
        LibVLC = new LibVLCSharp.Shared.LibVLC();
        var main = new MainWindow();
        MainWindow = main;
        main.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            if (Store.Config.rememberSpeed) Store.Config.lastSpeed = PlayerState.Speed;
            Store.SaveConfig();
            LibVLC?.Dispose();
        }
        catch { }
        base.OnExit(e);
    }
}
