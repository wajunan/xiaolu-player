using LibVLCSharp.Shared;
using System.IO;

namespace XiaoluPlayer;

static class SelfTest
{
    public static int Run(string[] args)
    {
        string log = Arg(args, "--log") ?? Path.Combine(Path.GetTempPath(), "xiaolu_selftest.log");
        string mode = Arg(args, "--mode") ?? "baidu";
        int secs = int.TryParse(Arg(args, "--secs"), out int s) ? s : 15;
        void Log(string line)
        {
            try { File.AppendAllText(log, DateTime.Now.ToString("HH:mm:ss") + " " + line + "\n"); } catch { }
        }
        try
        {
            string url = "";
            var opts = new List<string> { ":network-caching=2500", ":http-verbose" };
            if (mode == "baidu")
            {
                string path = Arg(args, "--path") ?? "";
                string ckFile = Arg(args, "--cookies-file") ?? "";
                if (path.Length == 0 || !File.Exists(ckFile))
                {
                    Log("RESULT: FAIL missing path/cookies-file");
                    return 2;
                }
                string cookies = File.ReadAllText(ckFile).Trim();
                string local = "";
                try
                {
                    var link = BaiduClient.GetStreamLink(cookies, path);
                    Log("probe ok chosen=" + BaiduClient.LabelOf(link.CurrentType) +
                        " avail=" + string.Join(",", link.Qualities.ConvertAll(q => q.Label)));
                    var h = BaiduClient.StreamHeaders(cookies);
                    var pl = BaiduClient.FetchPlaylist(cookies, path, link.CurrentType);
                    Log("playlist segs=" + pl.segs.Count + " dur0=" + pl.segs[0].Dur);
                    local = HlsProxy.Serve(pl.segs, cookies, h["User-Agent"], h["Referer"]);
                    Log("proxy=" + local);
                }
                catch (BaiduApiException e)
                {
                    Log("RESULT: FAIL probe errno=" + e.Errno + " " + e.Message);
                    return 3;
                }
                url = local;
                opts.Clear();
                opts.Add(":network-caching=2500");
            }
            else
            {
                url = Arg(args, "--url") ?? "";
                if (url.Length == 0) { Log("RESULT: FAIL missing url"); return 2; }
                if (Arg(args, "--ua") is string ua && ua.Length > 0) opts.Add(":http-user-agent=" + ua);
                if (Arg(args, "--referer") is string rf && rf.Length > 0) opts.Add(":http-referrer=" + rf);
                if (Arg(args, "--cookies") is string ck && ck.Length > 0) opts.Add(":http-cookies=" + ck);
            }
            Log("url=" + (url.Length > 150 ? url.Substring(0, 150) : url));
            Core.Initialize();
            using var lib = new LibVLC("--no-video", "--aout=dummy", "--intf=dummy", "--no-stats", "--no-osd");
            lib.Log += (sender, e) =>
            {
                string m = (e.Message ?? "").Trim();
                if (m.Length == 0) return;
                if (m.IndexOf("http", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("cookie", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("access", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("option", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("hls", StringComparison.OrdinalIgnoreCase) < 0 &&
                    m.IndexOf("playlist", StringComparison.OrdinalIgnoreCase) < 0 &&
                    e.Level != LibVLCSharp.Shared.LogLevel.Error && e.Level != LibVLCSharp.Shared.LogLevel.Warning) return;
                Log("vlc[" + e.Level + "] " + (m.Length > 400 ? m.Substring(0, 400) : m));
            };
            using var media = new Media(lib, url, FromType.FromLocation, opts.ToArray());
            using var mp = new MediaPlayer(media);
            mp.Play();
            long maxTime = 0;
            bool played = false;
            for (int i = 0; i < secs * 2; i++)
            {
                Thread.Sleep(500);
                try
                {
                    long t = mp.Time;
                    if (t > maxTime) maxTime = t;
                    if (mp.State == VLCState.Playing) played = true;
                    Log("tick state=" + mp.State + " time=" + t + " len=" + mp.Length);
                    if (played && maxTime > 2000 && i > 6) break;
                }
                catch (Exception e) { Log("tick err=" + e.Message); }
            }
            mp.Stop();
            if (played && maxTime > 2000)
            {
                Log("RESULT: SUCCESS maxTime=" + maxTime);
                return 0;
            }
            Log("RESULT: FAIL played=" + played + " maxTime=" + maxTime);
            return 4;
        }
        catch (Exception e)
        {
            Log("RESULT: FAIL exception " + e.GetType().Name + ": " + e.Message);
            return 5;
        }
    }

    static string CurlCheck(string url, string cookies)
    {
        try
        {
            var (code, output) = Curl.Get(url, cookies,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36", 25);
            if (code != 0) return "curl exit=" + code;
            string head = output.Length > 160 ? output.Substring(0, 160) : output;
            return "len=" + output.Length + " head=" + head.Replace("\n", "\\n").Replace("\r", "");
        }
        catch (Exception e) { return "err=" + e.Message; }
    }

    static string? Arg(string[] args, string name)
    {
        for (int i = 0; i + 1 < args.Length; i++)
            if (args[i] == name) return args[i + 1];
        return null;
    }
}
