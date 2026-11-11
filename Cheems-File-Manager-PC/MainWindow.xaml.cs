using System;
using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Web.WebView2.Core;

namespace CheemsFileManagerPC;

public partial class MainWindow : Window
{
    private const string AppOrigin = "https://cfm.local/";
    private readonly JsonSerializerOptions _json = new(JsonSerializerDefaults.Web);

    public MainWindow()
    {
        InitializeComponent();
        Loaded += async (_, _) => await StartBrowserAsync();
    }

    private async Task StartBrowserAsync()
    {
        try
        {
            string userData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "CheemsFileManagerPC", "WebView2");
            Directory.CreateDirectory(userData);

            var env = await CoreWebView2Environment.CreateAsync(userDataFolder: userData);
            await Browser.EnsureCoreWebView2Async(env);
            var core = Browser.CoreWebView2;

            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreBrowserAcceleratorKeysEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsWebMessageEnabled = true;
            core.NewWindowRequested += (_, e) => e.Handled = true;
            core.NavigationStarting += (_, e) =>
            {
                if (!e.Uri.StartsWith(AppOrigin, StringComparison.OrdinalIgnoreCase))
                    e.Cancel = true;
            };

            core.AddWebResourceRequestedFilter("https://cfm.local/*",
                CoreWebView2WebResourceContext.All);
            core.WebResourceRequested += ServeLocalFile;
            core.WebMessageReceived += OnWebMessage;
            core.Navigate(AppOrigin + "index.html");
        }
        catch (Exception exception)
        {
            MessageBox.Show(
                "CFM 未能启动内置界面。\n\n" + exception.Message +
                "\n\n请确认已安装 Microsoft Edge WebView2 Runtime。" +
                "\n不要关闭 Windows 的安全功能。",
                "Cheems File Manager — 启动失败",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
    }

    private void ServeLocalFile(object? sender, CoreWebView2WebResourceRequestedEventArgs e)
    {
        var uri = new Uri(e.Request.Uri);
        if (!uri.Host.Equals("cfm.local", StringComparison.OrdinalIgnoreCase)) return;

        string requested = uri.AbsolutePath.TrimStart('/');
        string? mime = requested switch
        {
            "index.html" => "text/html; charset=utf-8",
            "style.css" => "text/css; charset=utf-8",
            "app.js" => "text/javascript; charset=utf-8",
            _ => null
        };
        if (mime is null)
        {
            e.Response = Browser.CoreWebView2.Environment.CreateWebResourceResponse(
                Stream.Null, 404, "Not Found", "Content-Type: text/plain");
            return;
        }

        Stream? resource = Assembly.GetExecutingAssembly()
            .GetManifestResourceStream("CFM.UI." + requested);
        if (resource is null)
        {
            e.Response = Browser.CoreWebView2.Environment.CreateWebResourceResponse(
                Stream.Null, 404, "Not Found", "Content-Type: text/plain");
            return;
        }

        // Only CFM's three embedded files can be loaded; no network resources are needed.
        string headers = "Content-Type: " + mime + "\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; " +
            "script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; frame-src 'self' about:; " +
            "connect-src 'none'; object-src 'none'; base-uri 'none'; " +
            "form-action 'none'\r\n";
        e.Response = Browser.CoreWebView2.Environment.CreateWebResourceResponse(
            resource, 200, "OK", headers);
    }

    private async void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (!e.Source.StartsWith(AppOrigin, StringComparison.OrdinalIgnoreCase)) return;
        int id = -1;
        try
        {
            using var document = JsonDocument.Parse(e.WebMessageAsJson);
            var root = document.RootElement;
            id = root.GetProperty("id").GetInt32();
            string action = root.GetProperty("action").GetString() ?? "";
            JsonElement args = root.TryGetProperty("args", out var provided)
                ? provided.Clone() : default;

            // File operations never execute on the UI thread.
            object? output = await Task.Run(() => FileOperations.Execute(action, args));
            Reply(new { id, ok = true, data = output });
        }
        catch (Exception exception)
        {
            Reply(new { id, ok = false, error = exception.Message });
        }
    }

    private void Reply(object response)
    {
        try
        {
            Browser.CoreWebView2?.PostWebMessageAsJson(
                JsonSerializer.Serialize(response, _json));
        }
        catch (Exception)
        {
            // Window may already be closed while an async operation finishes.
        }
    }
}
