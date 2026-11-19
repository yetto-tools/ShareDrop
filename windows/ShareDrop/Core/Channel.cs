using System.IO;
using System.Buffers.Binary;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace ShareDrop.Core;

public static class T
{
    public const int Hello = 1, Auth = 2, AuthOk = 3, PairSecret = 4, Offer = 5, Accept = 6,
        FileStart = 7, Data = 8, FileEnd = 9, Done = 10, Error = 12;
}

public sealed record Frame(int Type, byte[] Payload);

public static class Crypto
{
    public static byte[] Random(int n) => RandomNumberGenerator.GetBytes(n);

    public static byte[] Hmac(byte[] key, params byte[][] parts)
    {
        using var h = new HMACSHA256(key);
        h.Initialize();
        foreach (var p in parts) h.TransformBlock(p, 0, p.Length, null, 0);
        h.TransformFinalBlock([], 0, 0);
        return h.Hash!;
    }

    public static byte[] PinSecret(string code) =>
        SHA256.HashData(System.Text.Encoding.UTF8.GetBytes("sharedrop-pin:" + code));

    public static bool Eq(byte[] a, byte[] b) => CryptographicOperations.FixedTimeEquals(a, b);
}

/// <summary>Canal de tramas sobre TCP. En claro hasta EnableCrypto; después AES-256-GCM.</summary>
public sealed class Channel : IDisposable
{
    public const int MaxFrame = 8 * 1024 * 1024;

    private readonly TcpClient _client;
    private readonly Stream _stream;
    private readonly object _sendLock = new();
    private AesGcm? _aes;
    private int _sendPrefix, _recvPrefix;
    private long _sendCtr, _recvCtr;

    public Channel(TcpClient client)
    {
        _client = client;
        _stream = new BufferedStream(client.GetStream(), 1 << 16);
    }

    public string RemoteAddress => (_client.Client.RemoteEndPoint as System.Net.IPEndPoint)?.Address.ToString() ?? "?";

    public void EnableCrypto(byte[] sessionKey, bool isClient)
    {
        _aes = new AesGcm(sessionKey, 16);
        _sendPrefix = isClient ? 1 : 2;
        _recvPrefix = isClient ? 2 : 1;
    }

    private static void Nonce(Span<byte> n, int prefix, long ctr)
    {
        BinaryPrimitives.WriteInt32BigEndian(n, prefix);
        BinaryPrimitives.WriteInt64BigEndian(n[4..], ctr);
    }

    public void Send(int type, byte[]? payload = null, int len = -1)
    {
        payload ??= [];
        if (len < 0) len = payload.Length;
        lock (_sendLock)
        {
            Span<byte> hdr = stackalloc byte[4];
            if (_aes is null)
            {
                BinaryPrimitives.WriteInt32BigEndian(hdr, 1 + len);
                _stream.Write(hdr);
                _stream.WriteByte((byte)type);
                _stream.Write(payload, 0, len);
            }
            else
            {
                var plain = new byte[1 + len];
                plain[0] = (byte)type;
                Buffer.BlockCopy(payload, 0, plain, 1, len);
                long ctr = _sendCtr++;
                Span<byte> nonce = stackalloc byte[12];
                Nonce(nonce, _sendPrefix, ctr);
                var ct = new byte[plain.Length];
                var tag = new byte[16];
                _aes.Encrypt(nonce, plain, ct, tag);
                BinaryPrimitives.WriteInt32BigEndian(hdr, 8 + ct.Length + 16);
                _stream.Write(hdr);
                Span<byte> c8 = stackalloc byte[8];
                BinaryPrimitives.WriteInt64BigEndian(c8, ctr);
                _stream.Write(c8);
                _stream.Write(ct);
                _stream.Write(tag);
            }
            _stream.Flush();
        }
    }

    private void ReadFully(byte[] buf, int len)
    {
        int off = 0;
        while (off < len)
        {
            int n = _stream.Read(buf, off, len - off);
            if (n <= 0) throw new IOException("Conexión cerrada");
            off += n;
        }
    }

    public Frame Recv()
    {
        var hdr = new byte[4];
        ReadFully(hdr, 4);
        int len = BinaryPrimitives.ReadInt32BigEndian(hdr);
        if (len < 1 || len > MaxFrame) throw new IOException($"Trama inválida ({len})");
        var body = new byte[len];
        ReadFully(body, len);
        if (_aes is null) return new Frame(body[0], body[1..]);

        if (len < 8 + 16 + 1) throw new IOException("Trama cifrada demasiado corta");
        long ctr = BinaryPrimitives.ReadInt64BigEndian(body);
        if (ctr != _recvCtr) throw new IOException("Contador fuera de orden");
        _recvCtr++;
        Span<byte> nonce = stackalloc byte[12];
        Nonce(nonce, _recvPrefix, ctr);
        int ctLen = len - 8 - 16;
        var plain = new byte[ctLen];
        _aes.Decrypt(nonce, body.AsSpan(8, ctLen), body.AsSpan(8 + ctLen, 16), plain);
        return new Frame(plain[0], plain[1..]);
    }

    public void Dispose()
    {
        _aes?.Dispose();
        _client.Dispose();
    }
}
