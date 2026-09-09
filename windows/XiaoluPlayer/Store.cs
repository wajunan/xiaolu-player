using System.IO;
using System.Text.Json;

namespace XiaoluPlayer;

class PlaybackRecord
{
    public string uri = "";
    public string title = "";
    public long pos;
    public long dur;
    public long ts;
    public long fsId = -1;
    public string path = "";
}

class AppConfig
{
    public string cookies = "";
    public float defaultSpeed = 1.0f;
    public bool rememberSpeed = true;
    public float lastSpeed = 1.0f;
    public bool autoResume = true;
}

static class Store
{
    static readonly JsonSerializerOptions JsonOpts = new() { IncludeFields = true, WriteIndented = true };
    static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "XiaoluPlayer");
    static string ConfigPath => Path.Combine(Dir, "config.json");
    static string RecordsPath => Path.Combine(Dir, "records.json");

    public static AppConfig Config = new();
    static List<PlaybackRecord> records = new();

    public static void Init()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            if (File.Exists(ConfigPath))
            {
                var c = JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(ConfigPath), JsonOpts);
                if (c != null) Config = c;
            }
            if (File.Exists(RecordsPath))
            {
                var r = JsonSerializer.Deserialize<List<PlaybackRecord>>(File.ReadAllText(RecordsPath), JsonOpts);
                if (r != null) records = r;
            }
        }
        catch { }
    }

    public static void SaveConfig()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(ConfigPath, JsonSerializer.Serialize(Config, JsonOpts));
        }
        catch { }
    }

    public static List<PlaybackRecord> Records() => records;

    public static PlaybackRecord? Find(string uri) => records.Find(r => r.uri == uri);

    public static void SaveRecord(string uri, string title, long pos, long dur, long fsId = -1, string path = "")
    {
        var r = Find(uri);
        if (r == null)
        {
            r = new PlaybackRecord { uri = uri, title = title };
            records.Insert(0, r);
        }
        r.pos = pos; r.dur = dur; r.title = title; r.fsId = fsId; r.path = path ?? "";
        r.ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        records.Sort((a, b) => b.ts.CompareTo(a.ts));
        while (records.Count > 60) records.RemoveAt(records.Count - 1);
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(RecordsPath, JsonSerializer.Serialize(records, JsonOpts));
        }
        catch { }
    }
}
