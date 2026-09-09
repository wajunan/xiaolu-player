using System.Diagnostics;
using System.Text;

namespace XiaoluPlayer;

static class Curl
{
    public static (int code, string output) Get(string url, string cookies, string ua, int timeoutSec = 25)
    {
        var psi = new ProcessStartInfo("curl.exe",
            "-s -m " + timeoutSec + " " + Q(url) +
            " -H " + Q("User-Agent: " + ua) +
            " -H " + Q("Referer: https://pan.baidu.com/") +
            " -H " + Q("Cookie: " + cookies))
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8
        };
        try
        {
            using var p = Process.Start(psi);
            if (p == null) return (-1, "");
            string output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(timeoutSec * 1000 + 5000);
            return (p.HasExited ? p.ExitCode : -2, output);
        }
        catch (Exception e)
        {
            return (-3, e.Message);
        }
    }

    public static int RangeCheck(string url, string cookies, string ua)
    {
        var psi = new ProcessStartInfo("curl.exe",
            "-s -o NUL -w %{http_code} -m 15 -r 0-1023 " + Q(url) +
            " -H " + Q("User-Agent: " + ua) +
            " -H " + Q("Referer: https://pan.baidu.com/") +
            (cookies.Length > 0 ? " -H " + Q("Cookie: " + cookies) : ""))
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8
        };
        try
        {
            using var p = Process.Start(psi);
            if (p == null) return -1;
            string output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(20000);
            if (int.TryParse(output.Trim(), out int code)) return code;
            return -1;
        }
        catch
        {
            return -1;
        }
    }

    static string Q(string s) => "\"" + s.Replace("\"", "") + "\"";
}
