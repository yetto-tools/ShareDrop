using System.IO;
using System.Text.Json;

namespace ShareDrop.Core;

/// <summary>Identidad local y secretos de los equipos emparejados (%APPDATA%\ShareDrop\store.json).</summary>
public sealed class Store
{
    private sealed class Data
    {
        public string Id { get; set; } = Guid.NewGuid().ToString();
        public string Name { get; set; } = Environment.MachineName;
        public Dictionary<string, string> Peers { get; set; } = new();
    }

    private readonly string _path;
    private readonly Data _data;
    private readonly object _lock = new();

    public Store(string? dir = null)
    {
        dir ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ShareDrop");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "store.json");
        try { _data = JsonSerializer.Deserialize<Data>(File.ReadAllText(_path)) ?? new Data(); }
        catch { _data = new Data(); }
        Save();
    }

    public string DeviceId => _data.Id;
    public string DeviceName => _data.Name;

    public byte[]? SecretFor(string id)
    {
        lock (_lock) return _data.Peers.TryGetValue(id, out var h) ? Convert.FromHexString(h) : null;
    }

    public bool IsPaired(string id) => SecretFor(id) is not null;

    public void SavePeer(string id, byte[] secret)
    {
        lock (_lock)
        {
            _data.Peers[id] = Convert.ToHexString(secret).ToLowerInvariant();
            Save();
        }
    }

    private void Save() => File.WriteAllText(_path, JsonSerializer.Serialize(_data));
}
