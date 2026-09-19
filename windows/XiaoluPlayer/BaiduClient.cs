using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace XiaoluPlayer;

class BaiduApiException : Exception
{
    public int Errno;
    public BaiduApiException(int errno, string message) : base(message) { Errno = errno; }
}

class PanFile
{
    public long FsId;
    public string Name = "";
    public bool IsDir;
    public string Path = "";
    public long Size;
    public long Mtime;
    public int Category;
}

class AllVideoResult
{
    public List<PanFile> Videos = new();
    public int Scans;
    public int DirsFound;
    public int FilesSeen;
    public bool Capped;
}

public class StreamQuality
{
    public string Type = "";
    public string Label = "";
}

class StreamLink
{
    public string Url = "";
    public List<StreamQuality> Qualities = new();
    public string CurrentType = "";
}

class PanListResponse
{
    public int errno = -1;
    public List<PanListEntry> list = new();
}

class PanListEntry
{
    [JsonPropertyName("fs_id")] public long FsId;
    [JsonPropertyName("server_filename")] public string Name = "";
    public int isdir;
    public string path = "";
    public long size;
    [JsonPropertyName("server_mtime")] public long mtime;
    public int category = -1;
}

class ApiDownloadResponse
{
    public int errno = -1;
    public List<ApiDownloadDlink> dlink = new();
}

class ApiDownloadDlink
{
    public string? dlink;
}

class StreamProbeResponse
{
    public int errno = -1;
    public string? adToken;
    public int adTime;
    public int ltime;
}

class SignVariableResult
{
    public string? sign1;
    public string? sign3;
    public string? timestamp;
    public string? bdstoken;
}

static class BaiduClient
{
    static readonly JsonSerializerOptions JsonOpts = new() { IncludeFields = true, PropertyNameCaseInsensitive = true };

    const string UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    const string DL_UA = "pan.baidu.com";
    const string Host = "https://pan.baidu.com";
    const string AppId = "250528";

    static readonly string[] VideoExts = {
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "f4v", "ts", "m2ts",
        "rmvb", "rm", "webm", "m4v", "ogv", "3gp", "3g2", "mpg", "mpeg"
    };

    public static readonly List<StreamQuality> StreamTypes = new()
    {
        new StreamQuality { Type = "M3U8_AUTO_1080", Label = "1080p" },
        new StreamQuality { Type = "M3U8_AUTO_720", Label = "720p" },
        new StreamQuality { Type = "M3U8_AUTO_480", Label = "480p" },
    };

    public static bool IsVideoName(string name)
    {
        int i = name.LastIndexOf('.');
        if (i < 0) return false;
        string ext = name.Substring(i + 1).ToLowerInvariant();
        return ((IList<string>)VideoExts).Contains(ext);
    }

    /// 自然排序：数字按数值比较（"第2集" 排在 "第10集" 前），忽略大小写。
    public static int NaturalCompare(string a, string b)
    {
        int i = 0, j = 0;
        while (i < a.Length && j < b.Length)
        {
            char ca = a[i], cb = b[j];
            bool da = char.IsAsciiDigit(ca), db = char.IsAsciiDigit(cb);
            if (da && db)
            {
                int si = i, sj = j;
                while (i < a.Length && char.IsAsciiDigit(a[i])) i++;
                while (j < b.Length && char.IsAsciiDigit(b[j])) j++;
                string na = a[si..i].TrimStart('0'), nb = b[sj..j].TrimStart('0');
                if (na.Length != nb.Length) return na.Length - nb.Length;
                int c = string.CompareOrdinal(na, nb);
                if (c != 0) return c;
                continue;
            }
            char la = char.ToLowerInvariant(ca), lb = char.ToLowerInvariant(cb);
            if (la != lb) return la - lb;
            i++; j++;
        }
        return (a.Length - i) - (b.Length - j);
    }

    public static string LabelOf(string type)
    {
        var q = StreamTypes.Find(x => x.Type == type);
        return q != null ? q.Label : type;
    }

    public static string TypeOf(string url)
    {
        var m = Regex.Match(url, "type=(M3U8_AUTO_\\d+)");
        return m.Success ? m.Groups[1].Value : "";
    }

    static string UrlEncode(string s)
    {
        var sb = new StringBuilder();
        foreach (byte b in Encoding.UTF8.GetBytes(s))
        {
            if ((b >= 48 && b <= 57) || (b >= 65 && b <= 90) || (b >= 97 && b <= 122) ||
                b == 46 || b == 45 || b == 42 || b == 95) sb.Append((char)b);
            else if (b == 32) sb.Append('+');
            else sb.Append('%').Append(b.ToString("X2"));
        }
        return sb.ToString();
    }

    static string Get(string url, string cookies)
    {
        var (code, output) = Curl.Get(url, cookies, UA);
        if (code != 0) throw new BaiduApiException(0, "request failed, curl exit=" + code);
        return output;
    }

    static T Decode<T>(string body) where T : class
    {
        try
        {
            var v = JsonSerializer.Deserialize<T>(body, JsonOpts);
            if (v == null) throw new Exception("null");
            return v;
        }
        catch (Exception e)
        {
            string head = body.Length > 200 ? body.Substring(0, 200) : body;
            throw new BaiduApiException(0, "bad response: " + e.Message + " body=" + head);
        }
    }

    public static List<PanFile> List(string cookies, string dir)
    {
        var entries = new List<PanListEntry>();
        int start = 0;
        const int limit = 100;

        while (true)
        {
            string enc = UrlEncode(dir);
            string url = Host + "/api/list?clienttype=0&app_id=" + AppId + "&web=1" +
                "&dir=" + enc + "&order=time&limit=" + limit + "&showempty=0&desc=1&start=" + start;
            Diag.Log("list GET start=" + start + " dir=" + dir);
            string body = Get(url, cookies);
            if (start == 0) Diag.Log("list raw body=" + Limit(body, 20000));

            var resp = Decode<PanListResponse>(body);
            if (resp.errno != 0)
            {
                Diag.Log("list errno=" + resp.errno + " start=" + start);
                throw Errno(resp.errno, body);
            }
            if (resp.list == null || resp.list.Count == 0) break;

            entries.AddRange(resp.list);
            int page = resp.list.Count;
            start += page;
            if (page < limit || start >= 1000) break;
        }

        var files = new List<PanFile>();
        foreach (var e in entries)
        {
            files.Add(new PanFile
            {
                FsId = e.FsId, Name = e.Name, IsDir = e.isdir == 1,
                Path = e.path, Size = e.size, Mtime = e.mtime, Category = e.category
            });
        }
        Diag.Log("list ok dir=" + dir + " count=" + files.Count + " cookieNames=" + string.Join(",", CookieNames(cookies)));
        return files;
    }

    public static AllVideoResult ListAllVideos(string cookies, string root,
        int maxScans = 500, int maxVideos = 2000, int maxFiles = 50000,
        Action<AllVideoResult>? progress = null)
    {
        var res = new AllVideoResult();
        var videos = new List<PanFile>();
        var queue = new ConcurrentQueue<string>();
        var visited = new HashSet<string>();
        var errors = new List<Exception>();
        int scans = 0;
        int busy = 0;
        int filesSeen = 0;
        int videoCount = 0;
        int dirsFound = 0;
        bool stop = false;
        object videosSync = new();
        object errorsSync = new();

        queue.Enqueue(root);
        visited.Add(root);
        int workerCount = Math.Clamp(maxScans <= 0 ? 1 : Math.Min(3, maxScans), 1, 3);
        var workers = new List<Task>();
        for (int w = 0; w < workerCount; w++)
        {
            workers.Add(Task.Run(async () =>
            {
                while (true)
                {
                    if (Volatile.Read(ref stop) || Volatile.Read(ref scans) >= maxScans) break;
                    if (!queue.TryDequeue(out string? dir))
                    {
                        if (Volatile.Read(ref busy) == 0) break;
                        await Task.Delay(50);
                        continue;
                    }

                    int currentScan = Interlocked.Increment(ref scans);
                    Interlocked.Increment(ref busy);
                    try
                    {
                        var list = List(cookies, dir);
                        foreach (var f in list)
                        {
                            if (Volatile.Read(ref stop) || currentScan >= maxScans) break;
                            if (f.IsDir)
                            {
                                Interlocked.Increment(ref dirsFound);
                                bool add = false;
                                lock (visited)
                                {
                                    if (visited.Add(f.Path) && Volatile.Read(ref scans) + queue.Count < maxScans)
                                    {
                                        queue.Enqueue(f.Path);
                                        add = true;
                                    }
                                }
                                if (!add && dirsFound >= maxScans) Volatile.Write(ref stop, true);
                            }
                            else
                            {
                                int seen = Interlocked.Increment(ref filesSeen);
                                if (f.Category == 1 || IsVideoName(f.Name))
                                {
                                    int vc = Interlocked.Increment(ref videoCount);
                                    if (vc <= maxVideos)
                                    {
                                        lock (videosSync) videos.Add(f);
                                    }
                                    else
                                    {
                                        Volatile.Write(ref stop, true);
                                        break;
                                    }
                                }
                                if (seen >= maxFiles)
                                {
                                    Volatile.Write(ref stop, true);
                                    break;
                                }
                            }
                        }

                        List<PanFile> snapshot;
                        lock (videosSync) snapshot = new List<PanFile>(videos);
                        var progressRes = new AllVideoResult
                        {
                            Videos = snapshot,
                            Scans = Volatile.Read(ref scans),
                            DirsFound = Volatile.Read(ref dirsFound),
                            FilesSeen = Volatile.Read(ref filesSeen),
                            Capped = Volatile.Read(ref stop)
                        };
                        progress?.Invoke(progressRes);
                    }
                    catch (Exception e)
                    {
                        Volatile.Write(ref stop, true);
                        lock (errorsSync) errors.Add(e);
                    }
                    finally
                    {
                        Interlocked.Decrement(ref busy);
                    }
                }
            }));
        }
        Task.WaitAll(workers.ToArray());

        if (errors.Count > 0) throw errors[0];
        res.Scans = Volatile.Read(ref scans);
        res.DirsFound = Volatile.Read(ref dirsFound);
        res.FilesSeen = Volatile.Read(ref filesSeen);
        lock (videosSync) res.Videos = new List<PanFile>(videos);
        res.Videos.Sort((a, b) => b.Mtime.CompareTo(a.Mtime));
        res.Capped = Volatile.Read(ref stop) || queue.Count > 0 || res.Scans >= maxScans;
        return res;
    }

    static readonly Dictionary<string, (DateTime at, bool ok, string err)> probeCache = new();
    static readonly Dictionary<string, (DateTime at, string err)> allFailCache = new();
    static readonly object probeLock = new();

    public static StreamLink GetStreamLink(string cookies, string path, long fsId = -1)
    {
        // 每个探测都会向百度发一次请求；一旦触发风控(-6)串行探完三个会让界面长时间无响应，
        // 所以命中一档就停，其余按"高一档可用则低一档通常可用"给出候选。
        for (int i = 0; i < StreamTypes.Count; i++)
        {
            var q = StreamTypes[i];
            try
            {
                string url = StreamingUrlFor(cookies, path, q.Type);
                var avail = StreamTypes.GetRange(i, StreamTypes.Count - i);
                lock (probeLock) allFailCache.Remove(path);
                return new StreamLink { Url = url, Qualities = avail, CurrentType = q.Type };
            }
            catch (Exception e)
            {
                Diag.Log("streaming probe " + q.Type + " unusable: " + e.Message);
            }
        }

        // 全档失败后还要再打四个接口，连点只会把账号摁得更深；先冷却 60 秒
        lock (probeLock)
        {
            if (allFailCache.TryGetValue(path, out var bad) && (DateTime.Now - bad.at).TotalSeconds < 60)
                throw new BaiduApiException(0, "暂时取不到播放地址，已暂停重试 1 分钟（" + bad.err + "）");
        }
        Diag.Log("streaming all qualities failed path=" + path);
        try
        {
            string link = ResolveDlinkFallback(cookies, path, fsId);
            lock (probeLock) allFailCache.Remove(path);
            return new StreamLink { Url = link, Qualities = new List<StreamQuality>(), CurrentType = "" };
        }
        catch (Exception e)
        {
            lock (probeLock) allFailCache[path] = (DateTime.Now, e.Message);
            throw;
        }
    }

    // 探测通过不代表取到播放列表：非会员的 1080p 会给 adToken 却把 m3u8 拒成 error_code 31062，
    // 停在探测结果上就会永远"加载中"，所以这里按档位从高往低逐档试到能用为止。
    public static (string url, List<HlsSeg> segs, string type) FetchPlaylistDowngrade(
        string cookies, string path, string preferredType, List<StreamQuality> qualities)
    {
        var types = new List<string>();
        if (preferredType.Length > 0) types.Add(preferredType);
        foreach (var q in qualities) if (!types.Contains(q.Type)) types.Add(q.Type);
        if (types.Count == 0) types.Add("M3U8_AUTO_480");
        Exception last = null;
        foreach (string t in types)
        {
            try
            {
                var r = FetchPlaylist(cookies, path, t);
                if (t != preferredType) Diag.Log("playlist downgraded to " + t + " (preferred " + preferredType + ")");
                return (r.url, r.segs, t);
            }
            catch (Exception e)
            {
                last = e;
                Diag.Log("playlist " + t + " failed: " + e.Message);
            }
        }
        throw last ?? new BaiduApiException(0, "playlist unavailable");
    }

    public static (string url, List<HlsSeg> segs) FetchPlaylist(string cookies, string path, string type)
    {
        string url = StreamingUrlFor(cookies, path, type);
        var (code, text) = Curl.Get(url, cookies, UA);
        if (code != 0) throw new BaiduApiException(0, "fetch playlist failed, curl exit=" + code);
        if (text.IndexOf("#EXTM3U") < 0)
        {
            Diag.Log("playlist invalid type=" + type + " body=" + Limit(text, 200));
            throw new BaiduApiException(0, "playlist invalid: " + (text.Length > 120 ? text.Substring(0, 120) : text));
        }
        var segs = new List<HlsSeg>();
        double dur = 10;
        foreach (var raw in text.Split('\n'))
        {
            string line = raw.Trim();
            if (line.Length == 0) continue;
            if (line.StartsWith("#EXTINF:"))
            {
                string d = line.Substring(8);
                int ci = d.IndexOf(',');
                if (ci >= 0) d = d.Substring(0, ci);
                if (!double.TryParse(d, System.Globalization.NumberStyles.Float,
                    System.Globalization.CultureInfo.InvariantCulture, out dur)) dur = 10;
            }
            else if (!line.StartsWith("#") && line.StartsWith("http"))
            {
                segs.Add(new HlsSeg { Dur = dur, Url = line });
                dur = 10;
            }
        }
        if (segs.Count == 0) throw new BaiduApiException(0, "playlist has no segments");
        Diag.Log("playlist ok type=" + type + " segs=" + segs.Count);
        return (url, segs);
    }

    public static string StreamingUrlFor(string cookies, string path, string type)
    {
        string key = path + "|" + type;
        lock (probeLock)
        {
            if (probeCache.TryGetValue(key, out var hit) && (DateTime.Now - hit.at).TotalMinutes < 3)
            {
                if (!hit.ok) throw new BaiduApiException(0, "streaming(" + type + ") " + hit.err);
            }
        }

        string enc = UrlEncode(path);
        string baseUrl = Host + "/api/streaming?path=" + enc + "&app_id=" + AppId + "&clienttype=0" +
            "&type=" + type + "&vip=0";
        string probeBody = Get(baseUrl + "&nom3u8=1", cookies);
        var probe = Decode<StreamProbeResponse>(probeBody);
        if (string.IsNullOrEmpty(probe.adToken))
        {
            string err = "no adToken errno=" + probe.errno;
            lock (probeLock) probeCache[key] = (DateTime.Now, false, err);
            Diag.Log("streaming probe FAIL " + type + " " + err + " path=" + path);
            throw new BaiduApiException(probe.errno, "streaming(" + type + ") " + err);
        }
        lock (probeLock) probeCache[key] = (DateTime.Now, true, "");
        Diag.Log("streaming probe ok " + type + " path=" + path);
        return baseUrl + "&isplayer=1&check_blue=1&adToken=" + UrlEncode(probe.adToken);
    }

    public static string GetDlink(string cookies, long fsId, string path)
    {
        try { return StreamingUrlFor(cookies, path, "M3U8_AUTO_1080"); }
        catch (Exception e) { Diag.Log("dlink streaming fail: " + e.Message); }
        return ResolveDlinkFallback(cookies, path, fsId);
    }

    static string ResolveDlinkFallback(string cookies, string path, long fsId = -1)
    {
        if (fsId < 0) fsId = FindFsId(cookies, path);
        var steps = new (string Name, Func<string> Run)[]
        {
            ("api/download", () => fsId >= 0 ? ApiDownloadDlink(cookies, fsId)
                : throw new BaiduApiException(0, "缺少 fs_id")),
            ("pcs",          () => PcsDlink(cookies, path)),
            ("filemetas",    () => FilemetasDlink(cookies, fsId)),
            ("playerinfo",   () => PlayerInfoDlink(cookies, path)),
        };
        var attempts = new List<string>();
        foreach (var (name, run) in steps)
        {
            try
            {
                string link = run();
                if (!IsDlinkUsable(link, cookies)) throw new BaiduApiException(0, "地址已失效");
                Diag.Log("dlink ok via " + name + " path=" + path);
                return link;
            }
            catch (Exception e)
            {
                attempts.Add(name + ": " + e.Message);
                Diag.Log("dlink " + name + " fail: " + e.Message);
            }
        }
        throw new BaiduApiException(0, "获取播放地址失败：\n" + string.Join("\n", attempts));
    }

    static long FindFsId(string cookies, string path)
    {
        int i = path.LastIndexOf('/');
        if (i <= 0) return -1;
        try
        {
            foreach (var f in List(cookies, path.Substring(0, i)))
                if (string.Equals(f.Path, path, StringComparison.Ordinal)) return f.FsId;
        }
        catch (Exception e) { Diag.Log("find fsid fail: " + e.Message); }
        return -1;
    }

    static string PcsDlink(string cookies, string path)
    {
        string enc = UrlEncode(path).Replace("+", "%20");
        string url = "https://pcs.baidu.com/rest/2.0/pcs/file?method=download&path=" + enc + "&app_id=" + AppId;
        var (code, loc) = Curl.GetRedirect(url, cookies, UA);
        Diag.Log("pcs http=" + code);
        if (code >= 300 && code <= 399 && loc.Length > 0) return loc;
        throw new BaiduApiException(0, "pcs 无跳转地址 HTTP " + code);
    }

    class FileMetaEntry
    {
        [JsonPropertyName("fs_id")] public long FsId;
        public string? dlink;
        public int errno;
    }

    class FileMetaResponse
    {
        public int errno = -1;
        public List<FileMetaEntry> info = new();
        public List<FileMetaEntry> list = new();
    }

    static string FilemetasDlink(string cookies, long fsId)
    {
        if (fsId < 0) throw new BaiduApiException(0, "filemetas 需要 fs_id");
        string token = Bdstoken(cookies);
        string fsids = UrlEncode("[{\"fs_id\":" + fsId + "}]");
        string url = Host + "/api/filemetas?app_id=" + AppId + "&dlink=1&fsids=" + fsids +
            "&clienttype=0&web=1&bdstoken=" + UrlEncode(token);
        var resp = Decode<FileMetaResponse>(Get(url, cookies));
        if (resp.errno != 0) throw Errno(resp.errno, "");
        var metas = resp.info.Count > 0 ? resp.info : resp.list;
        string? link = metas.Find(x => x.FsId == fsId)?.dlink;
        if (string.IsNullOrEmpty(link)) throw new BaiduApiException(0, "filemetas 无 dlink");
        return link;
    }

    static string PlayerInfoDlink(string cookies, string path)
    {
        string st = "";
        try
        {
            string stBody = Get(Host + "/api/getstoken?clienttype=0&web=1", cookies);
            st = JsonStr(stBody, "stoken") ?? "";
        }
        catch { }
        string url = Host + "/api/playerinfo?path=" + UrlEncode(path) + "&clienttype=0&vip=2" +
            "&need_full=1&context=api&web=1&t=" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() +
            (st.Length > 0 ? "&stoken=" + UrlEncode(st) : "");
        string body = Get(url, cookies);
        using var doc = JsonDocument.Parse(body);
        if (doc.RootElement.TryGetProperty("errno", out var en) && en.GetInt32() != 0)
            throw Errno(en.GetInt32(), "");
        if (doc.RootElement.TryGetProperty("dlink", out var dl) && dl.ValueKind == JsonValueKind.String)
        {
            string? v = dl.GetString();
            if (!string.IsNullOrEmpty(v)) return v;
        }
        if (doc.RootElement.TryGetProperty("streams", out var streams) && streams.ValueKind == JsonValueKind.Array)
        {
            foreach (var s in streams.EnumerateArray())
            {
                if (s.TryGetProperty("url", out var u) && u.ValueKind == JsonValueKind.String &&
                    !string.IsNullOrEmpty(u.GetString()))
                    return u.GetString()!;
            }
        }
        throw new BaiduApiException(0, "playerinfo 无播放地址");
    }

    static string? JsonStr(string body, string name)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String)
                return v.GetString();
        }
        catch { }
        return null;
    }

    static string ApiDownloadDlink(string cookies, long fsId)
    {
        var s = FetchSign(cookies);
        var q = new StringBuilder();
        q.Append("fidlist=").Append(UrlEncode("[" + fsId + "]"));
        q.Append("&sign=").Append(UrlEncode(s.sign));
        q.Append("&timestamp=").Append(s.timestamp);
        if (!string.IsNullOrEmpty(s.bdstoken)) q.Append("&bdstoken=").Append(UrlEncode(s.bdstoken));
        q.Append("&web=1&clienttype=0&app_id=").Append(AppId).Append("&channel=chunlei&territory=cn");
        string body = Get(Host + "/api/download?" + q, cookies);
        var resp = Decode<ApiDownloadResponse>(body);
        if (resp.errno != 0) throw Errno(resp.errno, body);
        string? link = resp.dlink.Count > 0 ? resp.dlink[0].dlink : null;
        if (string.IsNullOrEmpty(link)) throw new BaiduApiException(0, "api/download no dlink " + body);
        return link;
    }

    static bool IsDlinkUsable(string link, string cookies)
    {
        int code = Curl.RangeCheck(link, cookies, DL_UA);
        return code >= 200 && code <= 299;
    }

    class PanSignV2 { public string sign = ""; public string timestamp = ""; public string bdstoken = ""; }

    static PanSignV2 FetchSign(string cookies)
    {
        string fields = UrlEncode("[\"sign1\",\"sign2\",\"sign3\",\"timestamp\",\"bdstoken\"]");
        string url = Host + "/api/gettemplatevariable?fields=" + fields + "&clienttype=0&app_id=" + AppId;
        string body = Get(url, cookies);
        SignVariableResult? r = null;
        try
        {
            using var doc = JsonDocument.Parse(body);
            var result = doc.RootElement.GetProperty("result");
            r = new SignVariableResult
            {
                sign1 = OptStr(result, "sign1"),
                sign3 = OptStr(result, "sign3"),
                timestamp = OptStr(result, "timestamp"),
                bdstoken = OptStr(result, "bdstoken")
            };
        }
        catch { }
        if (r == null || string.IsNullOrEmpty(r.sign1) || string.IsNullOrEmpty(r.sign3) || string.IsNullOrEmpty(r.timestamp))
            throw new BaiduApiException(0, "gettemplatevariable missing sign " + body);
        string token = r.bdstoken ?? "";
        if (string.IsNullOrEmpty(token))
        {
            try { token = Bdstoken(cookies); } catch { }
        }
        byte[] enc = Sign2(r.sign3, r.sign1);
        string sign = Convert.ToBase64String(enc);
        return new PanSignV2 { sign = sign, timestamp = r.timestamp ?? "", bdstoken = token };
    }

    static string? OptStr(JsonElement el, string name)
    {
        if (el.TryGetProperty(name, out JsonElement v) && v.ValueKind == JsonValueKind.String) return v.GetString();
        return null;
    }

    static byte[] Sign2(string key, string data)
    {
        char[] j = key.ToCharArray();
        char[] r = data.ToCharArray();
        int[] a = new int[256];
        int[] p = new int[256];
        byte[] o = new byte[r.Length];
        int v = j.Length;
        int u = 0, i = 0, k = 0;
        if (v == 0) return o;
        for (int q = 0; q < 256; q++) { a[q] = j[q % v]; p[q] = q; }
        for (int q = 0; q < 256; q++)
        {
            u = (u + p[q] + a[q]) % 256;
            int t = p[q]; p[q] = p[u]; p[u] = t;
        }
        u = 0;
        for (int q = 0; q < r.Length; q++)
        {
            i = (i + 1) % 256;
            u = (u + p[i]) % 256;
            int t = p[i]; p[i] = p[u]; p[u] = t;
            k = p[(p[i] + p[u]) % 256];
            o[q] = (byte)(r[q] ^ k);
        }
        return o;
    }

    public static string Bdstoken(string cookies)
    {
        string fields = UrlEncode("[\"bdstoken\"]");
        string body = Get(Host + "/api/gettemplatevariable?fields=" + fields + "&clienttype=0&web=1", cookies);
        try
        {
            using var doc = JsonDocument.Parse(body);
            string? t = OptStr(doc.RootElement.GetProperty("result"), "bdstoken");
            if (!string.IsNullOrEmpty(t)) return t;
        }
        catch { }
        string html = Get(Host + "/disk/main", cookies);
        var m = Regex.Match(html, "\"bdstoken\"\\s*:\\s*\"([^\"]+)\"");
        if (m.Success) return m.Groups[1].Value;
        throw new BaiduApiException(0, "cannot get bdstoken: " + body);
    }

    static BaiduApiException Errno(int code, string body = "")
    {
        return code switch
        {
            -9 => new BaiduApiException(code, "login expired or security check, please login again " + body),
            -6 => new BaiduApiException(code, "auth failed, please login again " + body),
            -8 or 1 => new BaiduApiException(code, "security check failed or network error " + body),
            12 => new BaiduApiException(code, "baidu busy, retry later " + body),
            25788 => new BaiduApiException(code, "too frequent, retry later " + body),
            _ => new BaiduApiException(code, "baidu error " + code + " " + body),
        };
    }

    static string Limit(string s, int max)
    {
        if (string.IsNullOrEmpty(s)) return "";
        return s.Length <= max ? s : s.Substring(0, max);
    }

    static IEnumerable<string> CookieNames(string cookies)
    {
        foreach (var part in cookies.Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            string p = part.Trim();
            int i = p.IndexOf('=');
            if (i > 0) yield return p.Substring(0, i);
        }
    }

    public static Dictionary<string, string> StreamHeaders(string cookies)
    {
        return new Dictionary<string, string>
        {
            { "User-Agent", DL_UA },
            { "Referer", Host + "/" },
            { "Cookie", cookies }
        };
    }
}
