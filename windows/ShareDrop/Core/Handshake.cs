using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace ShareDrop.Core;

public interface IPairingUi
{
    /// <summary>Responder: muestra el código hasta que termine el emparejamiento.</summary>
    void ShowCode(string peerName, string code);
    void HideCode();

    /// <summary>Iniciador: bloquea hasta que el usuario escribe el código del otro equipo; null si cancela.</summary>
    string? AskCode(string peerName);
}

public sealed record Peer(string Id, string Name);

public static class Handshake
{
    private static byte[] Json(params (string k, object v)[] kv)
    {
        var o = new JsonObject();
        foreach (var (k, v) in kv)
            o[k] = v is bool b ? JsonValue.Create(b) : JsonValue.Create(v.ToString());
        return Encoding.UTF8.GetBytes(o.ToJsonString());
    }

    private static JsonObject Expect(Frame f, int type)
    {
        var obj = JsonNode.Parse(f.Payload)?.AsObject();
        if (f.Type == T.Error) throw new IOException(obj?["message"]?.GetValue<string>() ?? "Error remoto");
        if (f.Type != type) throw new IOException($"Se esperaba el tipo {type} y llegó {f.Type}");
        return obj!;
    }

    private static byte[] Proof(byte[] secret, string who, byte[] nc, byte[] ns) =>
        Crypto.Hmac(secret, Encoding.ASCII.GetBytes(who), nc, ns);

    private static byte[] Hex(JsonObject o, string key) => Convert.FromHexString(o[key]!.GetValue<string>());
    private static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

    /// <summary>Lado cliente (quien se conecta).</summary>
    public static Peer Initiate(Channel ch, Store store, IPairingUi ui)
    {
        var nc = Crypto.Random(16);
        ch.Send(T.Hello, Json(("id", store.DeviceId), ("name", store.DeviceName), ("nonce", Hex(nc))));
        var h = Expect(ch.Recv(), T.Hello);
        var peer = new Peer(h["id"]!.GetValue<string>(), h["name"]!.GetValue<string>());
        var ns = Hex(h, "nonce");

        var stored = store.SecretFor(peer.Id);
        bool pairing = !(h["paired"]!.GetValue<bool>() && stored is not null);
        byte[] secret;
        if (pairing)
        {
            var code = ui.AskCode(peer.Name);
            if (code is null)
            {
                ch.Send(T.Error, Json(("message", "Emparejamiento cancelado")));
                throw new IOException("Emparejamiento cancelado");
            }
            secret = Crypto.PinSecret(code.Trim());
        }
        else secret = stored!;

        ch.Send(T.Auth, Json(("proof", Hex(Proof(secret, "C", nc, ns)))));
        var ok = Expect(ch.Recv(), T.AuthOk);
        if (!Crypto.Eq(Hex(ok, "proof"), Proof(secret, "S", nc, ns)))
            throw new IOException("El otro equipo no pudo autenticarse");
        ch.EnableCrypto(Crypto.Hmac(secret, Encoding.ASCII.GetBytes("key"), nc, ns), isClient: true);

        if (pairing)
        {
            var s = Expect(ch.Recv(), T.PairSecret);
            store.SavePeer(peer.Id, Hex(s, "secret"));
        }
        return peer;
    }

    /// <summary>Lado servidor (quien acepta la conexión).</summary>
    public static Peer Respond(Channel ch, Store store, IPairingUi ui)
    {
        var h = Expect(ch.Recv(), T.Hello);
        var peer = new Peer(h["id"]!.GetValue<string>(), h["name"]!.GetValue<string>());
        var nc = Hex(h, "nonce");
        var ns = Crypto.Random(16);

        var stored = store.SecretFor(peer.Id);
        bool pairing = stored is null;
        byte[] secret;
        if (pairing)
        {
            var code = RandomNumberGenerator.GetInt32(1_000_000).ToString("D6");
            ui.ShowCode(peer.Name, code);
            secret = Crypto.PinSecret(code);
        }
        else secret = stored!;

        try
        {
            ch.Send(T.Hello, Json(("id", store.DeviceId), ("name", store.DeviceName), ("nonce", Hex(ns)), ("paired", !pairing)));
            var a = Expect(ch.Recv(), T.Auth);
            if (!Crypto.Eq(Hex(a, "proof"), Proof(secret, "C", nc, ns)))
            {
                ch.Send(T.Error, Json(("message", "Código o secreto incorrecto")));
                throw new IOException($"Autenticación fallida con {peer.Name}");
            }
            ch.Send(T.AuthOk, Json(("proof", Hex(Proof(secret, "S", nc, ns)))));
            ch.EnableCrypto(Crypto.Hmac(secret, Encoding.ASCII.GetBytes("key"), nc, ns), isClient: false);

            if (pairing)
            {
                var fresh = Crypto.Random(32);
                ch.Send(T.PairSecret, Json(("secret", Hex(fresh))));
                store.SavePeer(peer.Id, fresh);
            }
        }
        finally
        {
            if (pairing) ui.HideCode();
        }
        return peer;
    }
}
