using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.VisualBasic.FileIO;

namespace CheemsFileManager;

public static class Program
{
    [STAThread]
    public static void Main()
    {
        var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
        app.Run(new Shell());
    }
}

public sealed class FileItem
{
    public required string FullPath { get; init; }
    public required bool IsFolder { get; init; }
    public string Name => Path.GetFileName(FullPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
    public override string ToString() => (IsFolder ? "📁  " : "📄  ") + Name;
}

public sealed class FilePane : Border
{
    public readonly TextBox Address;
    public readonly TextBox Filter;
    public readonly ListBox List;
    public string CurrentPath { get; private set; } = "";
    public event Action<FilePane>? Activated;
    public event Action<string>? FileOpen;

    public FilePane(string title)
    {
        CornerRadius = new CornerRadius(18);
        Background = new SolidColorBrush(Color.FromArgb(185, 18, 30, 53));
        BorderBrush = new SolidColorBrush(Color.FromArgb(95, 134, 210, 240));
        BorderThickness = new Thickness(1);
        Margin = new Thickness(8);
        Padding = new Thickness(14);

        var root = new DockPanel { LastChildFill = true };
        Child = root;
        var top = new StackPanel { Orientation = Orientation.Vertical };
        DockPanel.SetDock(top, Dock.Top);
        root.Children.Add(top);

        top.Children.Add(new TextBlock {
            Text = title, Foreground = Brushes.LightCyan, FontSize = 16,
            FontWeight = FontWeights.SemiBold, Margin = new Thickness(3, 2, 3, 9)
        });
        Address = MakeInput();
        Address.ToolTip = "输入文件夹路径后按 Enter";
        Address.KeyDown += (_, e) => {
            if (e.Key == Key.Enter) { TryNavigate(Address.Text); e.Handled = true; }
        };
        top.Children.Add(Address);

        Filter = MakeInput();
        Filter.Margin = new Thickness(0, 8, 0, 10);
        Filter.ToolTip = "在当前目录按文件名筛选";
        Filter.TextChanged += (_, _) => Refresh();
        top.Children.Add(Filter);

        var quick = new StackPanel { Orientation = Orientation.Horizontal };
        var up = new Button { Content = "↑ 上一级", Padding = new Thickness(10, 5, 10, 5), Margin = new Thickness(0, 0, 8, 10) };
        var home = new Button { Content = "⌂ 主目录", Padding = new Thickness(10, 5, 10, 5), Margin = new Thickness(0, 0, 8, 10) };
        up.Click += (_, _) => { if (Directory.GetParent(CurrentPath) is { } parent) TryNavigate(parent.FullName); };
        home.Click += (_, _) => TryNavigate(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile));
        quick.Children.Add(up);
        quick.Children.Add(home);
        top.Children.Add(quick);

        List = new ListBox {
            Background = new SolidColorBrush(Color.FromArgb(140, 9, 18, 35)),
            Foreground = Brushes.WhiteSmoke, BorderThickness = new Thickness(0),
            FontSize = 13, SelectionMode = SelectionMode.Extended, Padding = new Thickness(5)
        };
        List.GotFocus += (_, _) => Activated?.Invoke(this);
        List.SelectionChanged += (_, _) => Activated?.Invoke(this);
        List.MouseDoubleClick += (_, _) => {
            if (List.SelectedItem is not FileItem item) return;
            if (item.IsFolder) TryNavigate(item.FullPath);
            else FileOpen?.Invoke(item.FullPath);
        };
        root.Children.Add(List);
    }

    private static TextBox MakeInput() => new() {
        Background = new SolidColorBrush(Color.FromRgb(29, 46, 70)),
        Foreground = Brushes.White, BorderBrush = Brushes.SlateGray,
        CaretBrush = Brushes.White, BorderThickness = new Thickness(1),
        Padding = new Thickness(8, 6, 8, 6), FontSize = 12
    };

    public IReadOnlyList<FileItem> Selected => List.SelectedItems.Cast<FileItem>().ToArray();

    public void TryNavigate(string path)
    {
        try { Navigate(path); }
        catch (Exception ex) { MessageBox.Show("无法打开目录：" + ex.Message, "Cheems File Manager"); }
    }

    public void Navigate(string path)
    {
        var absolute = Path.GetFullPath(Environment.ExpandEnvironmentVariables(path));
        if (!Directory.Exists(absolute)) throw new DirectoryNotFoundException(absolute);
        CurrentPath = absolute;
        Address.Text = absolute;
        Refresh();
        Activated?.Invoke(this);
    }

    public void Refresh()
    {
        if (string.IsNullOrEmpty(CurrentPath) || !Directory.Exists(CurrentPath)) return;
        try
        {
            string pattern = Filter.Text.Trim();
            bool Match(string path) => Path.GetFileName(path).Contains(pattern, StringComparison.OrdinalIgnoreCase);
            var directories = Directory.EnumerateDirectories(CurrentPath)
                .Where(Match).OrderBy(x => x).Select(x => new FileItem { FullPath = x, IsFolder = true });
            var files = Directory.EnumerateFiles(CurrentPath)
                .Where(Match).OrderBy(x => x).Select(x => new FileItem { FullPath = x, IsFolder = false });
            List.ItemsSource = directories.Concat(files).ToList();
        }
        catch (Exception ex)
        {
            List.ItemsSource = new[] { new FileItem { FullPath = "读取失败：" + ex.Message, IsFolder = false } };
        }
    }
}

public sealed class Shell : Window
{
    private readonly FilePane left = new("左侧工作区");
    private readonly FilePane right = new("右侧工作区");
    private FilePane active;
    private readonly TextBlock status = new() {
        Foreground = Brushes.LightGray, FontSize = 12,
        Margin = new Thickness(17, 10, 17, 12), Text = "Cheems File Manager V0.1"
    };

    public Shell()
    {
        active = left;
        Title = "Cheems File Manager · V0.1";
        Width = 1200; Height = 760; MinWidth = 780; MinHeight = 520;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = new LinearGradientBrush(
            Color.FromRgb(12, 24, 47), Color.FromRgb(24, 69, 92), 30);

        var root = new DockPanel { LastChildFill = true };
        Content = root;

        DockPanel.SetDock(status, Dock.Bottom);
        root.Children.Add(status);

        var header = new StackPanel { Orientation = Orientation.Vertical, Margin = new Thickness(14, 16, 14, 4) };
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        header.Children.Add(new TextBlock {
            Text = "CHEEMS  /  FILE MANAGER", Foreground = Brushes.White,
            FontWeight = FontWeights.Bold, FontSize = 24,
            Margin = new Thickness(8, 0, 0, 10)
        });

        var tools = new WrapPanel();
        header.Children.Add(tools);
        Add(tools, "⧉ 复制到另一栏", CopySelected);
        Add(tools, "⇄ 移动到另一栏", MoveSelected);
        Add(tools, "＋ 新建文件夹", NewFolder);
        Add(tools, "✎ 重命名", RenameSelected);
        Add(tools, "♻ 回收站", SendToRecycleBin);
        Add(tools, "▣ 文本编辑", EditSelected);
        Add(tools, "▤ 创建 ZIP", ZipSelected);
        Add(tools, "⇲ 解压 ZIP", UnzipSelected);
        Add(tools, "↻ 刷新", RefreshBoth);

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        Grid.SetColumn(left, 0);
        Grid.SetColumn(right, 1);
        grid.Children.Add(left);
        grid.Children.Add(right);
        root.Children.Add(grid);

        left.Activated += p => Activate(p);
        right.Activated += p => Activate(p);
        left.FileOpen += OpenFile;
        right.FileOpen += OpenFile;
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        left.Navigate(home);
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        right.Navigate(Directory.Exists(desktop) ? desktop : home);
        Activate(left);
        KeyDown += (_, e) => {
            if (e.Key == Key.F5) { RefreshBoth(); e.Handled = true; }
            if (e.Key == Key.F2) { RenameSelected(); e.Handled = true; }
        };
    }

    private FilePane Other => ReferenceEquals(active, left) ? right : left;
    private void Activate(FilePane p)
    {
        active = p;
        status.Text = (p == left ? "左侧" : "右侧") + "已选中 · " + p.CurrentPath;
    }
    private void RefreshBoth() { left.Refresh(); right.Refresh(); }
    private static void Add(Panel tools, string label, Action action)
    {
        var button = new Button {
            Content = label, Padding = new Thickness(12, 8, 12, 8),
            Margin = new Thickness(4, 4, 4, 6), MinWidth = 98,
            Background = new SolidColorBrush(Color.FromRgb(47, 86, 109)),
            Foreground = Brushes.White,
            BorderBrush = new SolidColorBrush(Color.FromRgb(106, 168, 196))
        };
        button.Click += (_, _) => {
            try { action(); }
            catch (Exception ex) { MessageBox.Show(ex.Message, "Cheems File Manager", MessageBoxButton.OK, MessageBoxImage.Warning); }
        };
        tools.Children.Add(button);
    }
    private IReadOnlyList<FileItem> RequireSelection()
    {
        var selected = active.Selected;
        if (selected.Count == 0) status.Text = "请先选择文件或文件夹。";
        return selected;
    }
    private static string Unique(string path)
    {
        if (!File.Exists(path) && !Directory.Exists(path)) return path;
        var parent = Path.GetDirectoryName(path)!;
        var basename = Path.GetFileNameWithoutExtension(path);
        var ext = Path.GetExtension(path);
        for (int i = 2; i < 10000; i++)
        {
            var candidate = Path.Combine(parent, $"{basename} ({i}){ext}");
            if (!File.Exists(candidate) && !Directory.Exists(candidate)) return candidate;
        }
        throw new IOException("没有可用的目标文件名。");
    }
    private static void CopyItem(string source, string destFolder)
    {
        bool directory = Directory.Exists(source);
        var target = Unique(Path.Combine(destFolder, Path.GetFileName(source)));
        if (directory)
        {
            var resolvedSource = Path.GetFullPath(source).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            var resolvedTarget = Path.GetFullPath(target).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            if (resolvedTarget.StartsWith(resolvedSource, StringComparison.OrdinalIgnoreCase))
                throw new IOException("不能把文件夹复制到它自己的内部。");
            Directory.CreateDirectory(target);
            foreach (var subdir in Directory.EnumerateDirectories(source)) CopyItem(subdir, target);
            foreach (var file in Directory.EnumerateFiles(source))
                File.Copy(file, Path.Combine(target, Path.GetFileName(file)), false);
        }
        else File.Copy(source, target, false);
    }
    private void CopySelected()
    {
        var selected = RequireSelection();
        if (selected.Count == 0) return;
        foreach (var item in selected) CopyItem(item.FullPath, Other.CurrentPath);
        RefreshBoth();
        status.Text = $"已复制 {selected.Count} 项到另一栏。";
    }
    private void MoveSelected()
    {
        var selected = RequireSelection();
        if (selected.Count == 0) return;
        if (MessageBox.Show($"确定把 {selected.Count} 项移动到\n{Other.CurrentPath} ？",
                "确认移动", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        foreach (var item in selected)
        {
            var dest = Path.Combine(Other.CurrentPath, item.Name);
            if (File.Exists(dest) || Directory.Exists(dest)) throw new IOException("目标已经存在：" + dest);
            if (item.IsFolder) Directory.Move(item.FullPath, dest);
            else File.Move(item.FullPath, dest);
        }
        RefreshBoth();
        status.Text = "移动已完成。";
    }
    private void NewFolder()
    {
        var name = Prompt("新建文件夹", "新文件夹名称", "New Folder");
        if (name is null) return;
        CheckName(name);
        var dest = Path.Combine(active.CurrentPath, name);
        if (File.Exists(dest) || Directory.Exists(dest)) throw new IOException("文件或文件夹已存在。");
        Directory.CreateDirectory(dest);
        RefreshBoth();
    }
    private void RenameSelected()
    {
        var selected = RequireSelection();
        if (selected.Count != 1) return;
        var item = selected[0];
        var name = Prompt("重命名", "新名称", item.Name);
        if (name is null) return;
        CheckName(name);
        var dest = Path.Combine(Path.GetDirectoryName(item.FullPath)!, name);
        if (File.Exists(dest) || Directory.Exists(dest)) throw new IOException("目标名称已存在。");
        if (item.IsFolder) Directory.Move(item.FullPath, dest);
        else File.Move(item.FullPath, dest);
        RefreshBoth();
    }
    private static void CheckName(string name)
    {
        if (string.IsNullOrWhiteSpace(name) || name == "." || name == ".." ||
            name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0 ||
            name.EndsWith(' ') || name.EndsWith('.'))
            throw new ArgumentException("请使用合法的单个文件名。");
    }
    private void SendToRecycleBin()
    {
        var selected = RequireSelection();
        if (selected.Count == 0) return;
        if (MessageBox.Show($"将 {selected.Count} 项送入 Windows 回收站？\n请先确认不是重要文件。",
            "确认删除", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        foreach (var item in selected)
        {
            if (item.IsFolder) FileSystem.DeleteDirectory(item.FullPath, UIOption.OnlyErrorDialogs, RecycleOption.SendToRecycleBin);
            else FileSystem.DeleteFile(item.FullPath, UIOption.OnlyErrorDialogs, RecycleOption.SendToRecycleBin);
        }
        RefreshBoth();
    }
    private static readonly HashSet<string> TextTypes = new(StringComparer.OrdinalIgnoreCase) {
        ".txt", ".md", ".json", ".xml", ".html", ".css", ".js", ".ts", ".cs",
        ".py", ".java", ".cpp", ".c", ".h", ".yaml", ".yml", ".ini", ".cfg",
        ".log", ".csv", ".svg", ".sh", ".bat", ".ps1", ".smali"
    };
    private void OpenFile(string path)
    {
        if (path.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) { Unzip(path); return; }
        if (!TextTypes.Contains(Path.GetExtension(path))) {
            status.Text = "此格式暂不支持内置打开。";
            return;
        }
        OpenEditor(path);
    }
    private void EditSelected()
    {
        var selected = RequireSelection();
        if (selected.Count != 1 || selected[0].IsFolder) return;
        if (!TextTypes.Contains(Path.GetExtension(selected[0].FullPath)))
            throw new IOException("此版本只允许编辑常见文本扩展名。");
        OpenEditor(selected[0].FullPath);
    }
    private void OpenEditor(string path)
    {
        if (new FileInfo(path).Length > 2 * 1024 * 1024)
            throw new IOException("V0.1 文本编辑器只支持 2MB 以下的文件。");
        var editor = new Window {
            Title = "编辑 · " + Path.GetFileName(path), Owner = this,
            Width = 900, Height = 650, Background = new SolidColorBrush(Color.FromRgb(20, 31, 50))
        };
        var panel = new DockPanel();
        var save = new Button { Content = "保存 Ctrl+S", Padding = new Thickness(16, 9, 16, 9) };
        DockPanel.SetDock(save, Dock.Top);
        panel.Children.Add(save);
        var text = new TextBox {
            AcceptsReturn = true, AcceptsTab = true, TextWrapping = TextWrapping.NoWrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            FontFamily = new FontFamily("Consolas"), FontSize = 14,
            Foreground = Brushes.White, Background = new SolidColorBrush(Color.FromRgb(12, 20, 36)),
            Padding = new Thickness(14), Text = File.ReadAllText(path)
        };
        panel.Children.Add(text);
        void Save() { File.WriteAllText(path, text.Text); status.Text = "已保存：" + path; }
        save.Click += (_, _) => Save();
        editor.KeyDown += (_, e) => {
            if ((Keyboard.Modifiers & ModifierKeys.Control) != 0 && e.Key == Key.S) {
                Save(); e.Handled = true;
            }
        };
        editor.Content = panel;
        editor.ShowDialog();
        RefreshBoth();
    }
    private void ZipSelected()
    {
        var selected = RequireSelection();
        if (selected.Count == 0) return;
        var output = Unique(Path.Combine(active.CurrentPath, "Cheems-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".zip"));
        using (var archive = ZipFile.Open(output, ZipArchiveMode.Create))
        {
            foreach (var item in selected)
                AddZip(archive, item.FullPath, item.Name);
        }
        RefreshBoth();
        status.Text = "已创建压缩包：" + output;
    }
    private static void AddZip(ZipArchive archive, string path, string zipPath)
    {
        if (Directory.Exists(path))
        {
            var files = Directory.GetFiles(path);
            var dirs = Directory.GetDirectories(path);
            if (files.Length == 0 && dirs.Length == 0)
                archive.CreateEntry(zipPath.Replace(Path.DirectorySeparatorChar, '/') + "/");
            foreach (var f in files)
                archive.CreateEntryFromFile(f, (zipPath + "/" + Path.GetFileName(f)).Replace(Path.DirectorySeparatorChar, '/'));
            foreach (var d in dirs)
                AddZip(archive, d, zipPath + "/" + Path.GetFileName(d));
        }
        else archive.CreateEntryFromFile(path, zipPath.Replace(Path.DirectorySeparatorChar, '/'));
    }
    private void UnzipSelected()
    {
        var selected = RequireSelection();
        if (selected.Count != 1 || selected[0].IsFolder) return;
        Unzip(selected[0].FullPath);
    }
    private void Unzip(string path)
    {
        if (!path.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
            throw new IOException("请选择 ZIP 文件。");
        var folder = Unique(Path.Combine(Path.GetDirectoryName(path)!, Path.GetFileNameWithoutExtension(path)));
        Directory.CreateDirectory(folder);
        ZipFile.ExtractToDirectory(path, folder);
        RefreshBoth();
        status.Text = "已解压到：" + folder;
    }

    private string? Prompt(string title, string label, string initial)
    {
        var dialog = new Window { Title = title, Owner = this, Width = 420, Height = 190,
            WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize };
        var stack = new StackPanel { Margin = new Thickness(18) };
        stack.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 0, 0, 12) });
        var input = new TextBox { Text = initial, Margin = new Thickness(0, 0, 0, 14) };
        stack.Children.Add(input);
        var ok = new Button { Content = "确认", HorizontalAlignment = HorizontalAlignment.Right,
            Padding = new Thickness(20, 7, 20, 7), IsDefault = true };
        ok.Click += (_, _) => { dialog.DialogResult = true; dialog.Close(); };
        stack.Children.Add(ok);
        dialog.Content = stack;
        input.SelectAll(); input.Focus();
        return dialog.ShowDialog() == true ? input.Text : null;
    }
}
