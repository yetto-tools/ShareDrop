using System.IO;
using System.Collections.ObjectModel;
using System.Net.NetworkInformation;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using ShareDrop.Core;

namespace ShareDrop;

public partial class MainWindow : Window, IPairingUi
{
    private readonly Engine _engine = new();
    private readonly ObservableCollection<TransferVM> _transfers = new();
    private Window? _codeWindow;

    public MainWindow()
    {
        InitializeComponent();
        _engine.Ui = this;
        TransferList.ItemsSource = _transfers;

        _engine.Discovery.Changed += devices => Dispatcher.Invoke(() => ShowDevices(devices));
        _engine.TransferChanged += t => Dispatcher.Invoke(() => ApplyTransfer(t));

        try
        {
            _engine.Start();
            Subtitle.Text = $"{_engine.Store.DeviceName} · recibe en {_engine.ReceiveDir}";
        }
        catch (Exception e)
        {
            Subtitle.Text = $"No se pudo iniciar: {e.Message}";
        }
        Closed += (_, _) => _engine.Dispose();
    }

    private void ShowDevices(IReadOnlyList<Device> devices)
    {
        DeviceList.ItemsSource = devices.Select(d => new DeviceVM(d, _engine.Store.IsPaired(d.Id))).ToList();
        EmptyHint.Visibility = devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ApplyTransfer(TransferInfo t)
    {
        var vm = _transfers.FirstOrDefault(x => x.Id == t.Id);
        if (vm is null) _transfers.Insert(0, new TransferVM(t));
        else vm.Update(t);
    }

    private static Device? DeviceOf(object sender) => ((sender as FrameworkElement)?.DataContext as DeviceVM)?.Device;

    private void Send_Click(object sender, RoutedEventArgs e)
    {
        var device = DeviceOf(sender);
        if (device is null) return;
        var dlg = new OpenFileDialog { Multiselect = true, Title = $"Enviar a {device.Name}" };
        if (dlg.ShowDialog(this) == true) _ = _engine.SendAsync(device, dlg.FileNames);
    }

    private void Device_Drop(object sender, DragEventArgs e)
    {
        var device = DeviceOf(sender);
        if (device is null || !e.Data.GetDataPresent(DataFormats.FileDrop)) return;
        var paths = ((string[])e.Data.GetData(DataFormats.FileDrop)!).Where(File.Exists).ToList();
        if (paths.Count > 0) _ = _engine.SendAsync(device, paths);
    }

    // ---------- IPairingUi (se llama desde hilos de fondo) ----------

    public void ShowCode(string peerName, string code) => Dispatcher.Invoke(() =>
    {
        _codeWindow = new Window
        {
            Title = $"Emparejar con {peerName}", Width = 340, Height = 200, Owner = this,
            WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize,
            Content = new StackPanel
            {
                Margin = new Thickness(20),
                Children =
                {
                    new TextBlock { Text = $"Escribe este código en {peerName}:", TextWrapping = TextWrapping.Wrap },
                    new TextBlock { Text = code, FontSize = 40, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 12, 0, 0) },
                },
            },
        };
        _codeWindow.Show();
    });

    public void HideCode() => Dispatcher.Invoke(() =>
    {
        _codeWindow?.Close();
        _codeWindow = null;
    });

    public string? AskCode(string peerName) => Dispatcher.Invoke(() =>
    {
        var box = new TextBox { MaxLength = 6, FontSize = 24, Margin = new Thickness(0, 12, 0, 12) };
        var ok = new Button { Content = "Aceptar", IsDefault = true, IsEnabled = false, Padding = new Thickness(16, 6, 16, 6) };
        var cancel = new Button { Content = "Cancelar", IsCancel = true, Padding = new Thickness(16, 6, 16, 6), Margin = new Thickness(0, 0, 8, 0) };
        var win = new Window
        {
            Title = $"Emparejar con {peerName}", Width = 340, Height = 240, Owner = this,
            WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize,
        };
        box.TextChanged += (_, _) => ok.IsEnabled = box.Text.Length == 6 && box.Text.All(char.IsDigit);
        ok.Click += (_, _) => win.DialogResult = true;
        win.Content = new StackPanel
        {
            Margin = new Thickness(20),
            Children =
            {
                new TextBlock { Text = $"Escribe el código de 6 dígitos que se muestra en {peerName}:", TextWrapping = TextWrapping.Wrap },
                box,
                new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Children = { cancel, ok } },
            },
        };
        win.Loaded += (_, _) => box.Focus();
        return win.ShowDialog() == true ? box.Text : null;
    });
}
