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

    readonly Dictionary<int, string> cacheFiles = new();
    readonly HashSet<int> downloading = new();
    readonly object cacheLock = new();

    public string? CachedFile(int i)
    {
        lock (cacheLock) return cacheFiles.TryGetValue(i, out string? f) && File.Exists(f) ? f : null;
    }

    public void AddCache(int i, string file)
    {
        lock (cacheLock) cacheFiles[i] = file;
    }

    public void DropCache(int i)
    {
        string? f;
        lock (cacheLock)
        {
            if (downloading.Contains(i)) return;
            if (!cacheFiles.TryGetValue(i, out f)) return;
            cacheFiles.Remove(i);
        }
        try { if (f != null) File.Delete(f); } catch { }
    }

    public bool TryBeginDownload(int i)
    {
        lock (cacheLock) return downloading.Add(i);
    }

    public void EndDownload(int i)
    {
        lock (cacheLock) downloading.Remove(i);
    }

    public void ClearCache()
    {
        Dictionary<int, string> files;
        lock (cacheLock)
        {
            files = new Dictionary<int, string>(cacheFiles);
            cacheFiles.Clear();
        }
        foreach (var f in files.Values)
            try { File.Delete(f); } catch { }
    }
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
            foreach (var s in sessions.Values) s.ClearCache();
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
        HlsSession? s;
        lock (lockObj)
        {
            sessions.TryGetValue(id, out s);
            sessions.Remove(id);
        }
        s?.ClearCache();
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
            string path = ctx.Request.Url?.AbsolutePath ?? "";
            // 请求进到这里却没后续 "hls seg N" 就说明会话已被释放或索引越界
            Diag.Log("hls req " + path + " i=" + (q["i"] ?? "-") +
                " session=" + (id.Length > 8 ? id.Substring(0, 8) : id) + (s == null ? " UNKNOWN" : ""));
            if (s == null) { ctx.Response.StatusCode = 404; ctx.Response.Close(); return; }
            if (path == "/play")
            {
                var sb = new StringBuilder();
                int maxDur = 1;
                foreach (var seg in s.Segments) maxDur = Math.Max(maxDur, (int)Math.Ceiling(seg.Dur));
                sb.Append("#EXTM3U\n#EXT-X-TARGETDURATION:").Append(maxDur).Append('\n');
                for (int i = 0; i < s.Segments.Count; i++)
                {
                    string dur = s.Segments[i].Dur.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
                    sb.Append("#EXTINF:").Append(dur).Append(",\n");
                    sb.Append("http://127.0.0.1:" + port + "/seg?session=" + s.Id + "&i=" + i + "\n");
                }
                sb.Append("#EXT-X-ENDLIST\n");
                byte[] data = Encoding.UTF8.GetBytes(sb.ToString());
                Diag.Log("hls playlist served segs=" + s.Segments.Count + " targetDur=" + maxDur);
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

                // 玩家要第 i 段时就去取第 i+1 段：等第 i 段交付完再预取就赶不上下一次请求
                PrefetchNext(s, i);
                string? file = s.CachedFile(i);
                if (file != null)
                {
                    Diag.Log("hls seg " + i + " served from cache size=" + new FileInfo(file).Length);
                    s.DropCache(i - 3);
                    ServeCachedFile(ctx, file);
                    return;
                }
                // 缓存未命中：边下边转发，绝不先把整段下完再回（非会员一段要几十秒，播放器会直接饿死）
                Diag.Log("hls seg " + i + " passthrough");
                FetchSegment(ctx, s, i);
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

    static string TmpPath(HlsSession s, int i) =>
        Path.Combine(Path.GetTempPath(), "xlhls_" + s.Id + "_" + i + ".tmp");

    static string HeaderArgs(HlsSession s) =>
        "-H \"User-Agent: " + s.Ua.Replace("\"", "") + "\" " +
        "-H \"Referer: " + s.Referer.Replace("\"", "") + "\" " +
        "-H \"Cookie: " + s.Cookies.Replace("\"", "") + "\" ";

    static string SegmentUrl(HlsSession s, int i) => "\"" + s.Segments[i].Url.Replace("\"", "") + "\"";

    static string SegmentArgs(HlsSession s, int i) =>
        "-s -L -m 60 --retry 1 " + HeaderArgs(s) + SegmentUrl(s, i);

    // 边下边转发给播放器：只有没人占用这段的下载槽、且请求是整段时才顺手落一份缓存
    static void FetchSegment(HttpListenerContext ctx, HlsSession s, int i)
    {
        string range = ctx.Request.Headers["Range"] ?? "";
        bool whole = range.Length == 0 || range == "bytes=0-";
        bool owns = whole && s.TryBeginDownload(i);
        string tmp = TmpPath(s, i);
        var args = new StringBuilder(SegmentArgs(s, i));
        if (!owns && range.StartsWith("bytes=")) args.Append(" -r " + range.Substring(6));
        var psi = new ProcessStartInfo("curl.exe", args.ToString())
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        Process? p = null;
        FileStream? tee = null;
        long total = 0;
        try
        {
            p = Process.Start(psi);
            if (p == null) { ctx.Response.StatusCode = 502; ctx.Response.Close(); return; }
            var errTask = p.StandardError.ReadToEndAsync();
            using var src = p.StandardOutput.BaseStream;
            ctx.Response.ContentType = "video/MP2T";
            if (range.Length > 0 && !owns) ctx.Response.StatusCode = 206;
            tee = owns ? File.Create(tmp) : null;
            byte[] buf = new byte[65536];
            int n;
            var dst = ctx.Response.OutputStream;
            while ((n = src.Read(buf, 0, buf.Length)) > 0)
            {
                dst.Write(buf, 0, n);
                total += n;
                tee?.Write(buf, 0, n);
            }
            p.WaitForExit(65000);
            try { errTask.Wait(2000); } catch { }
            int exit = p.HasExited ? p.ExitCode : -1;
            if (owns && total > 0 && exit == 0) s.AddCache(i, tmp);
            else if (exit != 0)
                Diag.Log("hls seg " + i + " curl exit=" + exit +
                    (errTask.IsCompleted ? " " + errTask.Result.Trim() : ""));
            ctx.Response.Close();
        }
        catch
        {
            try { ctx.Response.Abort(); } catch { }
        }
        finally
        {
            try { tee?.Dispose(); } catch { }
            if (owns)
            {
                if (total <= 0) { try { if (File.Exists(tmp)) File.Delete(tmp); } catch { } }
                s.EndDownload(i);
            }
        }
    }

    // 播放器切到下一段时要等"起 curl + TLS 握手"（150~300ms），这段空档就是掉帧来源。
    // 命中缓存时交付是瞬间的，玩家紧接着就要下下段，所以只提前一段永远慢一拍（实测缓存命中和未命中会交替出现）。
    // 这里提前两段：同一时刻至多两个预取在跑，且每段有下载槽去重。
    const int Ahead = 2;

    static void PrefetchNext(HlsSession s, int i)
    {
        for (int off = 1; off <= Ahead; off++)
        {
            int n = i + off;
            if (n >= s.Segments.Count) return;
            if (s.CachedFile(n) != null || !s.TryBeginDownload(n)) continue;
            PrefetchOne(s, n);
        }
    }

    static void PrefetchOne(HlsSession s, int n) => Task.Run(() => PrefetchToCache(s, n));

    /// <summary>开播前先落盘前几段：否则播放器第一帧就得等网络，起步那几秒必然掉帧。</summary>
    public static void Warmup(string sessionId, int count)
    {
        HlsSession? s;
        lock (lockObj) { sessions.TryGetValue(sessionId, out s); }
        if (s == null) return;
        var pending = new List<Task>();
        for (int i = 0; i < count && i < s.Segments.Count; i++)
        {
            if (s.CachedFile(i) != null || !s.TryBeginDownload(i)) continue;
            int n = i;
            pending.Add(Task.Run(() => PrefetchToCache(s, n)));
        }
        if (pending.Count == 0) return;
        try { Task.WaitAll(pending.ToArray(), TimeSpan.FromSeconds(3)); } catch { }
    }

    // 调用方需已持有该段的下载槽（TryBeginDownload）
    static void PrefetchToCache(HlsSession s, int n)
    {
        string tmp = TmpPath(s, n);
        try
        {
            var psi = new ProcessStartInfo("curl.exe",
                "-s -L -m 60 --retry 1 -o \"" + tmp + "\" " + HeaderArgs(s) + SegmentUrl(s, n))
            {
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var p = Process.Start(psi);
            if (p == null) return;
            p.WaitForExit(70000);
            bool ok = p.HasExited && p.ExitCode == 0 && File.Exists(tmp) && new FileInfo(tmp).Length > 0;
            if (!ok)
            {
                try { if (File.Exists(tmp)) File.Delete(tmp); } catch { }
                Diag.Log("hls prefetch " + n + " failed exit=" + (p.HasExited ? p.ExitCode : -1));
                return;
            }
            lock (lockObj)
            {
                if (!sessions.ContainsKey(s.Id)) { try { File.Delete(tmp); } catch { } return; }
            }
            s.AddCache(n, tmp);
            Diag.Log("hls prefetch " + n + " cached size=" + new FileInfo(tmp).Length);
        }
        catch (Exception e)
        {
            try { if (File.Exists(tmp)) File.Delete(tmp); } catch { }
            Diag.Log("hls prefetch " + n + " error: " + e.Message);
        }
        finally
        {
            s.EndDownload(n);
        }
    }

    static void ServeCachedFile(HttpListenerContext ctx, string file)
    {
        try
        {
            long len = new FileInfo(file).Length;
            long offset = 0;
            long to = len - 1;
            int status = 200;
            string range = ctx.Request.Headers["Range"] ?? "";
            if (range.StartsWith("bytes="))
            {
                var parts = range.Substring(6).Split('-');
                if (parts.Length >= 1 && long.TryParse(parts[0], out long from) && from >= 0)
                {
                    if (from >= len) { ctx.Response.StatusCode = 416; ctx.Response.Close(); return; }
                    offset = from;
                    status = 206;
                    if (parts.Length >= 2 && parts[1].Length > 0 && long.TryParse(parts[1], out long end))
                        to = Math.Min(end, len - 1);
                }
            }
            byte[] data = new byte[to - offset + 1];
            using (var fs = File.OpenRead(file))
                fs.Read(data, 0, data.Length);
            ctx.Response.StatusCode = status;
            ctx.Response.ContentType = "video/MP2T";
            ctx.Response.ContentLength64 = data.Length;
            if (status == 206)
                ctx.Response.Headers["Content-Range"] = "bytes " + offset + "-" + to + "/" + len;
            ctx.Response.OutputStream.Write(data, 0, data.Length);
            ctx.Response.Close();
        }
        catch
        {
            try { ctx.Response.Abort(); } catch { }
        }
    }
}
