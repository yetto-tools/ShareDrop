using System.Collections.ObjectModel;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace ShareDrop.Core;

public sealed record Device(string Id, string Name, string Platform, string Address, int Port, DateTime LastSeen);

/// <summary>Anuncia este equipo y escucha a los demás por broadcast UDP (Wi-Fi, USB tethering, etc.).</summary>
public sealed class Discovery : IDisposable
{
    public const int Port = 47801;

    private readonly Store _store;
    private readonly Dictionary<string, Device> _seen = new();
    private readonly object _lock = new();
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;

    /// <summary>Se dispara (en un hilo de fondo) cuando cambia la lista.</summary>
    public event Action<IReadOnlyList<Device>>? Changed;

    public Discovery(Store store) => _store = store;

    public void Start()
    {
        _cts = new CancellationTokenSource();
        _udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true, ExclusiveAddressUse = false };
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, Port));
        var ct = _cts.Token;
        _ = Task.Run(() => Listen(ct), ct);
        _ = Task.Run(() => Announce(ct), ct);
    }

    private async Task Announce(CancellationToken ct)
    {
        var msg = Encoding.UTF8.GetBytes(new JsonObject
        {
            ["app"] = "sharedrop", ["v"] = 1, ["id"] = _store.DeviceId,
            ["name"] = _store.DeviceName, ["platform"] = "windows", ["port"] = Engine.TcpPort,
        }.ToJsonString());
        while (!ct.IsCancellationRequested)
        {
            foreach (var ep in BroadcastEndpoints())
            {
                try { await _udp!.SendAsync(msg, ep, ct); } catch { /* interfaz caída */ }
            }
            Prune();
            try { await Task.Delay(2000, ct); } catch (OperationCanceledException) { return; }
        }
    }

    private static IEnumerable<IPEndPoint> BroadcastEndpoints()
    {
        var set = new HashSet<IPAddress> { IPAddress.Broadcast };
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask is null) continue;
                var ip = ua.Address.GetAddressBytes();
                var mask = ua.IPv4Mask.GetAddressBytes();
                var bc = new byte[4];
                for (int i = 0; i < 4; i++) bc[i] = (byte)(ip[i] | ~mask[i]);
                set.Add(new IPAddress(bc));
            }
        }
        return set.Select(a => new IPEndPoint(a, Port));
    }

    private async Task Listen(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var r = await _udp!.ReceiveAsync(ct);
                var j = JsonNode.Parse(r.Buffer)?.AsObject();
                if (j is null || j["app"]?.GetValue<string>() != "sharedrop") continue;
                var id = j["id"]!.GetValue<string>();
                if (id == _store.DeviceId) continue;
                var d = new Device(id, j["name"]!.GetValue<string>(), j["platform"]?.GetValue<string>() ?? "?",
                    r.RemoteEndPoint.Address.ToString(), j["port"]!.GetValue<int>(), DateTime.UtcNow);
                bool isNew;
                lock (_lock) { isNew = !_seen.ContainsKey(id) || _seen[id].Address != d.Address || _seen[id].Name != d.Name; _seen[id] = d; }
                if (isNew) Notify();
            }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch { /* datagrama ajeno o malformado */ }
        }
    }

    private void Prune()
    {
        bool changed;
        lock (_lock)
        {
            var limit = DateTime.UtcNow.AddSeconds(-8);
            var dead = _seen.Where(kv => kv.Value.LastSeen < limit).Select(kv => kv.Key).ToList();
            foreach (var k in dead) _seen.Remove(k);
            changed = dead.Count > 0;
        }
        if (changed) Notify();
    }

    private void Notify()
    {
        List<Device> snapshot;
        lock (_lock) snapshot = _seen.Values.OrderBy(d => d.Name).ToList();
        Changed?.Invoke(snapshot);
    }

    public void Dispose()
    {
        _cts?.Cancel();
        _udp?.Dispose();
    }
}
