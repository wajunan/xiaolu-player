using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;

namespace XiaoluPlayer;

class HlsSeg
{
    public double Dur = 10;
    public string Url = "";
}

class HlsSession
{
    public string Id = "";
    public List<HlsSeg> Segments = new();
    public string Cookies = "";
    public string Ua = "";
    public string Referer = "";
}

static class HlsProxy
{
    static HttpListener? listener;
    static int port;
    static readonly Dictionary<string, HlsSession> sessions = new();
    static readonly object lockObj = new();
    static bool running;

    public static string Start()
    {
        lock (lockObj)
        {
            if (running) return "http://127.0.0.1:" + port + "/";
            for (int p = 17890; p < 17920; p++)
            {
                try
                {
                    var l = new HttpListener();
                    l.Prefixes.Add("http://127.0.0.1:" + p + "/");
                    l.Start();
                    listener = l;
                    port = p;
                    running = true;
                    break;
                }
                catch { }
            }
            if (!running) throw new Exception("cannot start local proxy");
            Task.Run(Loop);
            return "http://127.0.0.1:" + port + "/";
        }
    }

    public static void Stop()
    {
        lock (lockObj)
        {
            running = false;
            try { listener?.Stop(); } catch { }
            listener = null;
            sessions.Clear();
        }
    }

    public static string Serve(List<HlsSeg> segments, string cookies, string ua, string referer)
    {
        Start();
        var s = new HlsSession
        {
            Id = Guid.NewGuid().ToString("N"),
            Segments = segments, Cookies = cookies, Ua = ua, Referer = referer
        };
        lock (lockObj) { sessions[s.Id] = s; }
        return "http://127.0.0.1:" + port + "/play?session=" + s.Id;
    }

    public static void Release(string id)
    {
        lock (lockObj) { sessions.Remove(id); }
    }

    static async Task Loop()
    {
        var l = listener;
        if (l == null) return;
        while (running)
        {
            HttpListenerContext? ctx = null;
            try { ctx = await l.GetContextAsync(); }
            catch { break; }
            if (ctx == null) break;
            _ = Task.Run(() => Handle(ctx));
        }
    }

    static void Handle(HttpListenerContext ctx)
    {
        try
        {
            var q = ctx.Request.QueryString;
            string id = q["session"] ?? "";
            HlsSession? s;
            lock (lockObj) { sessions.TryGetValue(id, out s); }
            if (s == null) { ctx.Response.StatusCode = 404; ctx.Response.Close(); return; }
            string path = ctx.Request.Url?.AbsolutePath ?? "";
            if (path == "/play")
            {
                var sb = new StringBuilder();
                sb.Append("#EXTM3U\n#EXT-X-TARGETDURATION:15\n");
                for (int i = 0; i < s.Segments.Count; i++)
                {
                    string dur = s.Segments[i].Dur.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
                    sb.Append("#EXTINF:").Append(dur).Append(",\n");
                    sb.Append("http://127.0.0.1:" + port + "/seg?session=" + s.Id + "&i=" + i + "\n");
                }
                sb.Append("#EXT-X-ENDLIST\n");
                byte[] data = Encoding.UTF8.GetBytes(sb.ToString());
                ctx.Response.ContentType = "application/vnd.apple.mpegurl";
                ctx.Response.ContentLength64 = data.Length;
                ctx.Response.OutputStream.Write(data, 0, data.Length);
                ctx.Response.Close();
                return;
            }
            if (path == "/seg")
            {
                if (!int.TryParse(q["i"], out int i) || i < 0 || i >= s.Segments.Count)
                { ctx.Response.StatusCode = 404; ctx.Response.Close(); return; }
                FetchSegment(ctx, s, s.Segments[i].Url);
                return;
            }
            ctx.Response.StatusCode = 404;
            ctx.Response.Close();
        }
        catch
        {
            try { ctx.Response.Abort(); } catch { }
        }
    }

    static void FetchSegment(HttpListenerContext ctx, HlsSession s, string url)
    {
        string range = ctx.Request.Headers["Range"] ?? "";
        var args = new StringBuilder("-s -m 60 ");
        if (range.Length > 0 && range.StartsWith("bytes="))
            args.Append("-r " + range.Substring(6) + " ");
        args.Append("\"" + url.Replace("\"", "") + "\"");
        args.Append(" -H \"User-Agent: " + s.Ua + "\"");
        args.Append(" -H \"Referer: " + s.Referer + "\"");
        args.Append(" -H \"Cookie: " + s.Cookies.Replace("\"", "") + "\"");
        var psi = new ProcessStartInfo("curl.exe", args.ToString())
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        try
        {
            using var p = Process.Start(psi);
            if (p == null) { ctx.Response.StatusCode = 502; ctx.Response.Close(); return; }
            var errTask = p.StandardError.ReadToEndAsync();
            using var src = p.StandardOutput.BaseStream;
            ctx.Response.ContentType = "video/MP2T";
            if (range.Length > 0) ctx.Response.StatusCode = 206;
            byte[] buf = new byte[65536];
            int n;
            var dst = ctx.Response.OutputStream;
            while ((n = src.Read(buf, 0, buf.Length)) > 0)
                dst.Write(buf, 0, n);
            p.WaitForExit(65000);
            try { errTask.Wait(2000); } catch { }
            ctx.Response.Close();
        }
        catch
        {
            try { ctx.Response.Abort(); } catch { }
        }
    }
}
