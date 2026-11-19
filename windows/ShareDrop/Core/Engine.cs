using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace ShareDrop.Core;

public enum TransferState { Active, Finished, Failed }

public sealed record TransferInfo(long Id, string Peer, string File, long Done, long Total, bool Incoming,
    TransferState State, string? Error = null);

public sealed class Engine : IDisposable
{
    public const int TcpPort = 47800;
    public const int Chunk = 512 * 1024;

    public Store Store { get; } = new();
    public Discovery Discovery { get; }
    public IPairingUi Ui { get; set; } = null!;
    public string ReceiveDir { get; }

    /// <summary>Se dispara (en hilos de fondo) con cada cambio de una transferencia.</summary>
    public event Action<TransferInfo>? TransferChanged;

    private TcpListener? _listener;
    private long _ids;

    public Engine()
    {
        Discovery = new Discovery(Store);
        ReceiveDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "ShareDrop");
        Directory.CreateDirectory(ReceiveDir);
    }

    public void Start()
    {
        _listener = new TcpListener(IPAddress.Any, TcpPort);
        _listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _listener.Start();
        Discovery.Start();
        _ = Task.Run(AcceptLoop);
    }

    private async Task AcceptLoop()
    {
        while (_listener is not null)
        {
            try
            {
                var c = await _listener.AcceptTcpClientAsync();
                _ = Task.Run(() => HandleIncoming(c));
            }
            catch { return; }
        }
    }

    // ---------- Envío ----------

    public Task SendAsync(Device device, IReadOnlyList<string> paths) => Task.Run(() => RunSend(device, paths));

    private void RunSend(Device device, IReadOnlyList<string> paths)
    {
        var files = paths.Select(p => new FileInfo(p)).ToList();
        long tid = Interlocked.Increment(ref _ids);
        var cur = new TransferInfo(tid, device.Name, files.FirstOrDefault()?.Name ?? "", 0, 0, false, TransferState.Active);
        TransferChanged?.Invoke(cur);
        try
        {
            var client = new TcpClient { NoDelay = true, SendBufferSize = 1 << 20 };
            if (!client.ConnectAsync(device.Address, device.Port).Wait(5000))
                throw new IOException("No se pudo conectar (¿firewall o red con aislamiento?)");
            using var ch = new Channel(client);
            Handshake.Initiate(ch, Store, Ui);

            var offer = new JsonObject
            {
                ["files"] = new JsonArray(files.Select(f => (JsonNode)new JsonObject { ["name"] = f.Name, ["size"] = f.Length }).ToArray()),
            };
            ch.Send(T.Offer, Encoding.UTF8.GetBytes(offer.ToJsonString()));
            var reply = ch.Recv();
            if (reply.Type != T.Accept) throw new IOException(ErrorMessage(reply.Payload) ?? "Rechazado");

            var buf = new byte[Chunk];
            foreach (var f in files)
            {
                cur = cur with { File = f.Name, Done = 0, Total = f.Length };
                TransferChanged?.Invoke(cur);
                ch.Send(T.FileStart, Encoding.UTF8.GetBytes(new JsonObject { ["name"] = f.Name, ["size"] = f.Length }.ToJsonString()));
                long sent = 0;
                long lastUi = 0;
                using (var fs = new FileStream(f.FullName, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16, FileOptions.SequentialScan))
                {
                    int n;
                    while ((n = fs.Read(buf, 0, buf.Length)) > 0)
                    {
                        ch.Send(T.Data, buf, n);
                        sent += n;
                        long now = Environment.TickCount64;
                        if (now - lastUi > 150) { lastUi = now; TransferChanged?.Invoke(cur with { Done = sent }); }
                    }
                }
                ch.Send(T.FileEnd);
                cur = cur with { Done = sent, Total = sent };
                TransferChanged?.Invoke(cur);
            }
            ch.Send(T.Done);
            var ack = ch.Recv();
            if (ack.Type != T.Done) throw new IOException(ErrorMessage(ack.Payload) ?? "Sin confirmación");
            TransferChanged?.Invoke(cur with { State = TransferState.Finished });
        }
        catch (Exception e)
        {
            TransferChanged?.Invoke(cur with { State = TransferState.Failed, Error = e.Message });
        }
    }

    // ---------- Recepción ----------

    private void HandleIncoming(TcpClient client)
    {
        client.NoDelay = true;
        client.ReceiveBufferSize = 1 << 20;
        long tid = Interlocked.Increment(ref _ids);
        var cur = new TransferInfo(tid, (client.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "?", "", 0, 0, true, TransferState.Active);
        FileStream? fs = null;
        string? partial = null;
        try
        {
            using var ch = new Channel(client);
            var peer = Handshake.Respond(ch, Store, Ui);
            cur = cur with { Peer = peer.Name };
            long received = 0, lastUi = 0;
            while (true)
            {
                var f = ch.Recv();
                switch (f.Type)
                {
                    case T.Offer:
                        ch.Send(T.Accept);
                        break;
                    case T.FileStart:
                    {
                        var j = JsonNode.Parse(f.Payload)!.AsObject();
                        var name = Path.GetFileName(j["name"]!.GetValue<string>().Replace('\\', '/'));
                        foreach (var bad in Path.GetInvalidFileNameChars()) name = name.Replace(bad, '_');
                        if (string.IsNullOrWhiteSpace(name)) name = "archivo";
                        partial = UniquePath(name);
                        fs = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1 << 16);
                        received = 0;
                        cur = cur with { File = Path.GetFileName(partial), Done = 0, Total = j["size"]?.GetValue<long>() ?? -1 };
                        TransferChanged?.Invoke(cur);
                        break;
                    }
                    case T.Data:
                        if (fs is null) throw new IOException("DATA sin FILE_START");
                        fs.Write(f.Payload);
                        received += f.Payload.Length;
                        long now = Environment.TickCount64;
                        if (now - lastUi > 150) { lastUi = now; TransferChanged?.Invoke(cur with { Done = received }); }
                        break;
                    case T.FileEnd:
                        fs?.Dispose();
                        fs = null;
                        partial = null;
                        cur = cur with { Done = received, Total = received };
                        TransferChanged?.Invoke(cur);
                        break;
                    case T.Done:
                        ch.Send(T.Done);
                        TransferChanged?.Invoke(cur with { State = TransferState.Finished });
                        return;
                    case T.Error:
                        throw new IOException(ErrorMessage(f.Payload) ?? "Error remoto");
                    default:
                        throw new IOException($"Tipo inesperado {f.Type}");
                }
            }
        }
        catch (Exception e)
        {
            fs?.Dispose();
            if (partial is not null) try { File.Delete(partial); } catch { /* ya no existe */ }
            TransferChanged?.Invoke(cur with { State = TransferState.Failed, Error = e.Message });
        }
    }

    private string UniquePath(string name)
    {
        var p = Path.Combine(ReceiveDir, name);
        var ext = Path.GetExtension(name);
        var stem = Path.GetFileNameWithoutExtension(name);
        for (int i = 1; File.Exists(p); i++) p = Path.Combine(ReceiveDir, $"{stem} ({i}){ext}");
        return p;
    }

    private static string? ErrorMessage(byte[] payload)
    {
        try { return JsonNode.Parse(payload)?["message"]?.GetValue<string>(); } catch { return null; }
    }

    public void Dispose()
    {
        _listener?.Stop();
        _listener = null;
        Discovery.Dispose();
    }
}
