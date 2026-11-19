using System.IO;
using System.Net;
using System.Net.Sockets;
using ShareDrop.Core;
using Xunit;

namespace ShareDrop.Tests;

public class HandshakeTests
{
    private sealed class FakeUi : IPairingUi
    {
        public string? ShownCode;
        public string? CodeToType;
        public void ShowCode(string peerName, string code) => ShownCode = code;
        public void HideCode() { }
        public string? AskCode(string peerName) => CodeToType ?? ShownCode;
    }

    private static Store NewStore() => new(Directory.CreateTempSubdirectory("sharedrop-test").FullName);

    private static (Channel client, Channel server) Connect(TcpListener l)
    {
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        var acc = l.AcceptTcpClientAsync();
        var c = new TcpClient();
        c.Connect(IPAddress.Loopback, port);
        return (new Channel(c), new Channel(acc.Result));
    }

    [Fact]
    public void Pairing_then_reconnect_uses_stored_secret_and_encrypts_both_ways()
    {
        var a = NewStore();
        var b = NewStore();
        var uiA = new FakeUi();
        var uiB = new FakeUi();
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();

        // 1ª conexión: emparejamiento (B muestra el código, A lo "escribe").
        var (c1, s1) = Connect(l);
        var respond = Task.Run(() => Handshake.Respond(s1, b, uiB));
        // A necesita el código de B: lo leemos cuando B lo genere.
        uiA.CodeToType = null;
        var ui = new LinkedUi(uiA, uiB);
        var peerB = Handshake.Initiate(c1, a, ui);
        var peerA = respond.Result;
        Assert.Equal(b.DeviceId, peerB.Id);
        Assert.Equal(a.DeviceId, peerA.Id);
        Assert.True(a.IsPaired(b.DeviceId));
        Assert.True(b.IsPaired(a.DeviceId));
        Assert.Equal(Convert.ToHexString(a.SecretFor(b.DeviceId)!), Convert.ToHexString(b.SecretFor(a.DeviceId)!));

        c1.Send(T.Data, [1, 2, 3, 4]);
        var got = s1.Recv();
        Assert.Equal(T.Data, got.Type);
        Assert.Equal(new byte[] { 1, 2, 3, 4 }, got.Payload);
        s1.Send(T.Done);
        Assert.Equal(T.Done, c1.Recv().Type);
        c1.Dispose(); s1.Dispose();

        // 2ª conexión: ya emparejados, no debe pedir código.
        var noUi = new FakeUi { CodeToType = null };
        var (c2, s2) = Connect(l);
        var respond2 = Task.Run(() => Handshake.Respond(s2, b, noUi));
        Handshake.Initiate(c2, a, new ThrowingUi());
        respond2.Wait();
        Assert.Null(noUi.ShownCode);
        var big = new byte[600_000];
        Random.Shared.NextBytes(big);
        var reader = Task.Run(() => s2.Recv()); // el receptor lee en paralelo, como en la app
        c2.Send(T.Data, big, big.Length);
        Assert.True(reader.Wait(10_000));
        Assert.Equal(big, reader.Result.Payload);
        l.Stop();
    }

    [Fact]
    public void Wrong_code_is_rejected()
    {
        var a = NewStore();
        var b = NewStore();
        var uiB = new FakeUi();
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var (c, s) = Connect(l);
        var respond = Task.Run(() => Handshake.Respond(s, b, uiB));
        var wrong = new FakeUi { CodeToType = "000000" == uiB.ShownCode ? "111111" : "000000" };
        var ex = Assert.ThrowsAny<IOException>(() => Handshake.Initiate(c, a, wrong));
        Assert.Throws<AggregateException>(() => respond.Wait());
        Assert.False(b.IsPaired(a.DeviceId));
        l.Stop();
    }

    [Fact]
    public void Tampered_frame_is_rejected()
    {
        var key = Crypto.Random(32);
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var (c, s) = Connect(l);
        c.EnableCrypto(key, true);
        s.EnableCrypto(Crypto.Random(32), false); // clave distinta
        c.Send(T.Data, [9]);
        Assert.ThrowsAny<Exception>(() => s.Recv());
        l.Stop();
    }

    // Vectores generados por el núcleo en Rust (desktop/core/tests/vectors.rs): si coinciden, ambos hablan igual.
    [Fact]
    public void Matches_rust_protocol_vectors()
    {
        var secret = Crypto.PinSecret("123456");
        Assert.Equal("adcbd24f80aee01833f4e97a4cbb5995803553a12175b809f8f8ce367c1782ae", Convert.ToHexString(secret).ToLowerInvariant());
        var nc = Enumerable.Repeat((byte)1, 16).ToArray();
        var ns = Enumerable.Repeat((byte)2, 16).ToArray();
        Assert.Equal("a2a57c19d100b20b62d96f8fcae5ff389644e7d649f3db9d2e97796a44bdc88b",
            Convert.ToHexString(Crypto.Hmac(secret, "C"u8.ToArray(), nc, ns)).ToLowerInvariant());
        Assert.Equal("3750690739c92e4e5ee97bd1e326bd0deb04dba952fc0e53191944e08a992d4f",
            Convert.ToHexString(Crypto.Hmac(secret, "key"u8.ToArray(), nc, ns)).ToLowerInvariant());

        var key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        using var aes = new System.Security.Cryptography.AesGcm(key, 16);
        var nonce = new byte[12];
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(nonce, 1);
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(nonce.AsSpan(4), 5);
        byte[] plain = [8, 1, 2, 3];
        var ct = new byte[plain.Length];
        var tag = new byte[16];
        aes.Encrypt(nonce, plain, ct, tag);
        var body = new byte[8].Concat(ct).Concat(tag).ToArray();
        body[7] = 5; // contador 5 en big-endian
        Assert.Equal("0000000000000005b6876f16de6ff0fccea411e01ca8713fdca2479d", Convert.ToHexString(body).ToLowerInvariant());
    }

    /// <summary>A escribe el código que B (responder) acaba de mostrar.</summary>
    private sealed class LinkedUi(FakeUi initiator, FakeUi responder) : IPairingUi
    {
        public void ShowCode(string peerName, string code) { }
        public void HideCode() { }
        public string? AskCode(string peerName)
        {
            for (int i = 0; i < 200 && responder.ShownCode is null; i++) Thread.Sleep(10);
            return responder.ShownCode;
        }
    }

    private sealed class ThrowingUi : IPairingUi
    {
        public void ShowCode(string peerName, string code) => throw new InvalidOperationException("no debe emparejar");
        public void HideCode() { }
        public string? AskCode(string peerName) => throw new InvalidOperationException("no debe pedir código");
    }
}
