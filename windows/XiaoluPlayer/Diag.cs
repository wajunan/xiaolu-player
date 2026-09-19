using System.IO;

namespace XiaoluPlayer;

static class Diag
{
    static string Dir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "XiaoluPlayer");

    public static string LogPath => Path.Combine(Dir, "diag.log");

    public static void Log(string message)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.AppendAllText(LogPath, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + "  " + message + Environment.NewLine);
        }
        catch
        {
        }
    }
}
