using System.Diagnostics;
using System.Text;

namespace XiaoluPlayer;

static class Curl
{
    public static (int code, string output) Get(string url, string cookies, string ua, int timeoutSec = 25)
    {
        var psi = new ProcessStartInfo("curl.exe")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8
        };
        psi.ArgumentList.Add("-s");
        psi.ArgumentList.Add("-m");
        psi.ArgumentList.Add(timeoutSec.ToString());
        psi.ArgumentList.Add(url);
        psi.ArgumentList.Add("-H");
        psi.ArgumentList.Add("User-Agent: " + ua);
        psi.ArgumentList.Add("-H");
        psi.ArgumentList.Add("Referer: https://pan.baidu.com/");
        if (cookies.Length > 0)
        {
            psi.ArgumentList.Add("-H");
            psi.ArgumentList.Add("Cookie: " + cookies);
        }
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

    // 不跟随 302，返回响应中的跳转地址（pcs.baidu.com 靠 Location 给真实下载主机）
    public static (int code, string redirectUrl) GetRedirect(string url, string cookies, string ua, int timeoutSec = 25)
    {
        var psi = new ProcessStartInfo("curl.exe")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8
        };
        psi.ArgumentList.Add("-s");
        psi.ArgumentList.Add("-o");
        psi.ArgumentList.Add("NUL");
        psi.ArgumentList.Add("-w");
        psi.ArgumentList.Add("%{http_code}|%{redirect_url}");
        psi.ArgumentList.Add("-m");
        psi.ArgumentList.Add(timeoutSec.ToString());
        psi.ArgumentList.Add(url);
        psi.ArgumentList.Add("-H");
        psi.ArgumentList.Add("User-Agent: " + ua);
        psi.ArgumentList.Add("-H");
        psi.ArgumentList.Add("Referer: https://pan.baidu.com/");
        if (cookies.Length > 0)
        {
            psi.ArgumentList.Add("-H");
            psi.ArgumentList.Add("Cookie: " + cookies);
        }
        try
        {
            using var p = Process.Start(psi);
            if (p == null) return (-1, "");
            string output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(timeoutSec * 1000 + 5000);
            if (!p.HasExited) return (-2, "");
            var pieces = output.Trim().Split('|', 2);
            int code = pieces.Length > 0 && int.TryParse(pieces[0], out int c) ? c : -1;
            return (code, pieces.Length > 1 ? pieces[1] : "");
        }
        catch
        {
            return (-3, "");
        }
    }

    public static int RangeCheck(string url, string cookies, string ua)
    {
        var psi = new ProcessStartInfo("curl.exe")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8
        };
        psi.ArgumentList.Add("-s");
        psi.ArgumentList.Add("-o");
        psi.ArgumentList.Add("NUL");
        psi.ArgumentList.Add("-w");
        psi.ArgumentList.Add("%{http_code}");
        psi.ArgumentList.Add("-m");
        psi.ArgumentList.Add("15");
        psi.ArgumentList.Add("-r");
        psi.ArgumentList.Add("0-1023");
        psi.ArgumentList.Add(url);
        psi.ArgumentList.Add("-H");
        psi.ArgumentList.Add("User-Agent: " + ua);
        psi.ArgumentList.Add("-H");
        psi.ArgumentList.Add("Referer: https://pan.baidu.com/");
        if (cookies.Length > 0)
        {
            psi.ArgumentList.Add("-H");
            psi.ArgumentList.Add("Cookie: " + cookies);
        }
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
}
