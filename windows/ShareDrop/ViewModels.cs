using System.ComponentModel;
using System.Windows;
using System.Windows.Media;
using ShareDrop.Core;

namespace ShareDrop;

public sealed class DeviceVM
{
    public DeviceVM(Device d, bool paired)
    {
        Device = d;
        Name = d.Name;
        Detail = $"{d.Platform} · {d.Address}" + (paired ? " · emparejado" : "");
    }

    public Device Device { get; }
    public string Name { get; }
    public string Detail { get; }
}

public sealed class TransferVM : INotifyPropertyChanged
{
    private TransferInfo _t;

    public TransferVM(TransferInfo t) => _t = t;

    public long Id => _t.Id;

    public void Update(TransferInfo t)
    {
        _t = t;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(string.Empty));
    }

    public string Title => (_t.Incoming ? "↓ de " : "↑ a ") + _t.Peer;
    public string File => string.IsNullOrEmpty(_t.File) ? "…" : _t.File;
    public double Fraction => _t.Total > 0 ? Math.Clamp((double)_t.Done / _t.Total, 0, 1) : 0;
    public bool Indeterminate => _t.State == TransferState.Active && _t.Total <= 0;
    public Visibility BarVisibility => _t.State == TransferState.Active ? Visibility.Visible : Visibility.Collapsed;

    public string Status => _t.State switch
    {
        TransferState.Active => $"{Size(_t.Done)} / {Size(_t.Total)}",
        TransferState.Finished => $"Completado · {Size(_t.Total)}",
        _ => $"Falló: {_t.Error}",
    };

    public Brush StatusBrush => _t.State == TransferState.Failed ? Brushes.Firebrick : Brushes.Gray;

    private static string Size(long b) => b switch
    {
        < 0 => "?",
        >= 1L << 30 => $"{b / (double)(1L << 30):F2} GB",
        >= 1L << 20 => $"{b / (double)(1L << 20):F1} MB",
        >= 1L << 10 => $"{b / 1024.0:F0} KB",
        _ => $"{b} B",
    };

    public event PropertyChangedEventHandler? PropertyChanged;
}
