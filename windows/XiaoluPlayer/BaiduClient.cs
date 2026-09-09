using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

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
        string enc = UrlEncode(dir);
        string url = Host + "/api/list?clienttype=0&app_id=" + AppId + "&web=1" +
            "&dir=" + enc + "&order=time&limit=100&showempty=0&desc=1&start=0";
        string body = Get(url, cookies);
        var resp = Decode<PanListResponse>(body);
        if (resp.errno != 0) throw Errno(resp.errno, body);
        var files = new List<PanFile>();
        foreach (var e in resp.list)
        {
            files.Add(new PanFile
            {
                FsId = e.FsId, Name = e.Name, IsDir = e.isdir == 1,
                Path = e.path, Size = e.size, Mtime = e.mtime, Category = e.category
            });
        }
        return files;
    }

    public static StreamLink GetStreamLink(string cookies, string path)
    {
        var ok = new List<(StreamQuality, string)>();
        Exception? lastErr = null;
        foreach (var q in StreamTypes)
        {
            try
            {
                string url = StreamingUrlFor(cookies, path, q.Type);
                ok.Add((q, url));
            }
            catch (Exception e)
            {
                lastErr = e;
            }
        }
        if (ok.Count == 0) throw lastErr ?? new BaiduApiException(0, "no stream quality available");
        return new StreamLink { Url = ok[0].Item2, Qualities = ok.ConvertAll(x => x.Item1), CurrentType = ok[0].Item1.Type };
    }

    public static (string url, List<HlsSeg> segs) FetchPlaylist(string cookies, string path, string type)
    {
        string url = StreamingUrlFor(cookies, path, type);
        var (code, text) = Curl.Get(url, cookies, UA);
        if (code != 0) throw new BaiduApiException(0, "fetch playlist failed, curl exit=" + code);
        if (text.IndexOf("#EXTM3U") < 0)
            throw new BaiduApiException(0, "playlist invalid: " + (text.Length > 120 ? text.Substring(0, 120) : text));
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
        return (url, segs);
    }

    public static string StreamingUrlFor(string cookies, string path, string type)
    {
        string enc = UrlEncode(path);
        string baseUrl = Host + "/api/streaming?path=" + enc + "&app_id=" + AppId + "&clienttype=0" +
            "&type=" + type + "&vip=0";
        string probeBody = Get(baseUrl + "&nom3u8=1", cookies);
        var probe = Decode<StreamProbeResponse>(probeBody);
        if (string.IsNullOrEmpty(probe.adToken))
            throw new BaiduApiException(probe.errno, "streaming(" + type + ") no adToken errno=" + probe.errno + " " + probeBody);
        return baseUrl + "&isplayer=1&check_blue=1&adToken=" + UrlEncode(probe.adToken);
    }

    public static string GetDlink(string cookies, long fsId, string path)
    {
        try { return GetStreamLink(cookies, path).Url; } catch { }
        try
        {
            string link = ApiDownloadDlink(cookies, fsId);
            if (IsDlinkUsable(link, cookies)) return link;
            throw new BaiduApiException(0, "api/download dlink expired");
        }
        catch (BaiduApiException) { throw; }
        catch (Exception e) { throw new BaiduApiException(0, "get play url failed: " + e.Message); }
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
