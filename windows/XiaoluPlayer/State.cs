namespace XiaoluPlayer;

static class PlayerState
{
    public static float Speed = 1.0f;
}

public class PlayRequest
{
    public string Uri = "";
    public string Title = "";
    public string UserAgent = "";
    public string Referer = "";
    public string Cookies = "";
    public long FsId = -1;
    public string BaiduPath = "";
    public List<StreamQuality> Qualities = new();
    public string StreamType = "";
    public long StartMs;
}
